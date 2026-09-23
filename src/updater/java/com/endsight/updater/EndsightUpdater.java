package com.endsight.updater;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Keeps Endsight on the newest release. It is only in endsight-autoupdate-<v>.jar; the
 * plain endsight-<v>.jar has none of it, which is the whole answer to "I don't want it
 * touching my mods" - pick the plain jar and nothing ever updates.
 *
 * Once a launch, before the game opens its window: ask GitHub for the latest release,
 * and if it is not the one installed, download its jar next to the game. It cannot go
 * in on this launch - by the time any mod code runs, Fabric has already opened every
 * jar in mods/, and Windows will not move a jar that is open. So a small window says
 * it is downloaded, the game closes instead of starting, and a shutdown hook starts
 * {@link Swap}, a tiny second process that waits for the game to be gone and then moves
 * the jars. The next launch is the new version.
 *
 * Which release is installed is the commit the jar was built from, stamped into it by
 * GitHub Actions. A jar built anywhere else has none and is never replaced - so a dev
 * build is never swapped for the public release underneath its author.
 *
 * Anything that goes wrong is said in game with a code, so a player can say "E4"
 * instead of sending a log: E1 GitHub did not answer; E2 the download failed; E3 the
 * download was not a genuine Endsight jar; E4 the old jar would not move (another game
 * still open, or an antivirus); E5 the new jar would not go in (the old one was put
 * back); E6 the game took over two minutes to close; E7 the swap never ran at all.
 *
 * What it tells the player goes through a system property the main mod reads on join,
 * so this jar needs nothing from Minecraft and has no reason to break between versions.
 */
public final class EndsightUpdater implements PreLaunchEntrypoint {

    private static final String LATEST = "https://api.github.com/repos/RealZones/Endsight/releases/latest";
    private static final String DOWNLOADS = "https://github.com/RealZones/Endsight/releases/download/";
    private static final Pattern MOD_ID = Pattern.compile("\"id\"\\s*:\\s*\"endsight\"");

    /**
     * Before the game opens its window. An update found here is downloaded, a small
     * window says so, and the game closes instead of starting - the jar is swapped as
     * it goes, so the next launch is the new version. Nothing found, or no way to show
     * the window, and the launch carries on as if this were not here.
     */
    @Override
    public void onPreLaunch() {
        System.setProperty("endsight.updater", "1");           // the mod's Updates defers to this
        String version = run();
        if (version == null) return;
        Path marker = FabricLoader.getInstance().getGameDir().resolve("endsight-update").resolve("ready.txt");
        try {
            // Shown once per update. If it is still waiting on the next launch, the swap
            // did not happen - blocking again would lock the player out of the game, so
            // the launch goes on and the toast in game says it instead.
            String m = Files.readString(marker);
            if (m.contains("shown")) {
                Path result = marker.resolveSibling("swap.txt");
                String code = Files.exists(result) ? Files.readString(result).trim() : "";
                state("stuck:" + version + ":" + (code.startsWith("E") ? code : "E7"));
                return;
            }
            if (!popup(version)) return;
            Files.writeString(marker, m + "shown\n");
        } catch (Exception e) {
            return;
        }
        System.exit(0);
    }

    /** The small window. False when there is no desktop to put it on. */
    private static boolean popup(String version) {
        // A Mac starts the game on its first thread for the window, and AWT on that
        // thread hangs; there it is the toast in game.
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) return false;
        try {
            System.setProperty("java.awt.headless", "false");
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
            javax.swing.JDialog d = new javax.swing.JOptionPane(
                    "Endsight " + version + " downloaded.\nRelaunch the game to play on it.",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE).createDialog("Endsight");
            d.setAlwaysOnTop(true);
            d.setVisible(true);                               // waits for OK or the X
            d.dispose();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void state(String s) {
        System.setProperty("endsight.update", s);
    }

    /** Checks and downloads. The new version when one is ready to install, else null. */
    private static String run() {
        String code = "E1";                                   // how far it got, for the error it gives
        try {
            ModContainer mod = FabricLoader.getInstance().getModContainer("endsight").orElse(null);
            if (mod == null) return null;
            CustomValue cv = mod.getMetadata().getCustomValue("endsight:commit");
            String commit = cv != null && cv.getType() == CustomValue.CvType.STRING ? cv.getAsString().trim() : "";
            Path jar = jarOf(mod);
            if (commit.isEmpty() || jar == null) {
                state("dev");
                return null;
            }
            Path dir = FabricLoader.getInstance().getGameDir().resolve("endsight-update");
            Files.createDirectories(dir);

            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(LATEST))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "EndsightUpdater")
                    .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                state("failed:E1");
                return null;
            }
            JsonObject release = JsonParser.parseString(res.body()).getAsJsonObject();
            // Tags are "1.0.0-abc1234": the version, then the commit it was cut from.
            // Compared by commit, not version, so a fix pushed under the same number
            // still arrives.
            String tag = release.get("tag_name").getAsString();
            String sha = tag.substring(tag.lastIndexOf('-') + 1);
            if (commit.startsWith(sha)) {
                state("current");
                return null;
            }
            // The kind of jar you have is the kind you get: an endsight-autoupdate jar is
            // replaced by the next endsight-autoupdate jar, never by the plain one that
            // would quietly stop updating. No fallback to the other kind - a release seen
            // mid-upload may have only one of the two up yet.
            String kind = jar.getFileName().toString().startsWith("endsight-autoupdate-") ? "endsight-autoupdate-" : "endsight-";
            Pattern wanted = Pattern.compile("^" + Pattern.quote(kind) + "\\d+(?:\\.\\d+)*\\.jar$");
            JsonObject asset = null;
            for (JsonElement e : release.getAsJsonArray("assets")) {
                if (wanted.matcher(e.getAsJsonObject().get("name").getAsString()).matches()) asset = e.getAsJsonObject();
            }
            code = "E2";
            if (asset == null) {
                state("failed:E2");
                return null;
            }
            String name = asset.get("name").getAsString();
            String url = asset.get("browser_download_url").getAsString();
            long size = asset.get("size").getAsLong();
            if (!url.startsWith(DOWNLOADS)) {                 // only ever from this repo's releases
                state("failed:E2");
                return null;
            }
            Path ready = dir.resolve(name);
            Path marker = dir.resolve("ready.txt");
            boolean have = Files.exists(ready) && Files.size(ready) == size && Files.exists(marker)
                    && Files.readString(marker).startsWith(tag + "\n");
            if (!have) {
                Path part = dir.resolve(name + ".part");
                HttpResponse<Path> dl = http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "EndsightUpdater").timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofFile(part));
                if (dl.statusCode() != 200 || Files.size(part) != size) {
                    Files.deleteIfExists(part);
                    state("failed:E2");
                    return null;
                }
                if (!genuine(part, asset)) {
                    Files.deleteIfExists(part);
                    state("failed:E3");
                    return null;
                }
                Files.move(part, ready, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(dir.resolve("swap.txt"));  // a new update starts with a clean result
                Files.writeString(marker, tag + "\n" + name + "\n");
            }
            state("ready:" + tag.substring(0, tag.lastIndexOf('-')));
            // The helper runs from a copy of this jar. This jar IS the one being replaced -
            // run from it, the helper would hold it open and could never move it.
            Path helper = dir.resolve("helper.jar");
            Files.copy(jar, helper, StandardCopyOption.REPLACE_EXISTING);
            // Worked out now, while the loader can still answer: in a shutdown hook the
            // game is half torn down.
            String java = ProcessHandle.current().info().command().orElse("java");
            String pid = String.valueOf(ProcessHandle.current().pid());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    new ProcessBuilder(java, "-cp", helper.toString(), Swap.class.getName(), pid,
                            jar.toString(), ready.toString(), dir.resolve("previous.jar").toString())
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                } catch (Exception ignored) {
                    // The download keeps; the next close tries again.
                }
            }, "Endsight swap"));
            return tag.substring(0, tag.lastIndexOf('-'));
        } catch (Exception e) {
            state("failed:" + code);
            return null;
        }
    }

    /** The mod's jar in mods/, or null when it is not a plain jar (a dev run, a nested jar). */
    private static Path jarOf(ModContainer mod) {
        ModOrigin o = mod.getOrigin();
        if (o.getKind() != ModOrigin.Kind.PATH) return null;
        List<Path> paths = o.getPaths();
        return paths.size() == 1 && paths.get(0).toString().endsWith(".jar") ? paths.get(0) : null;
    }

    /**
     * Is this really an Endsight jar, and the one GitHub published? It must open as a
     * jar with Endsight's id in it, and match the SHA-256 GitHub lists for the asset
     * when it lists one.
     */
    private static boolean genuine(Path file, JsonObject asset) {
        try {
            if (asset.has("digest") && asset.get("digest").isJsonPrimitive()) {
                String digest = asset.get("digest").getAsString();
                if (digest.startsWith("sha256:")) {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = Files.newInputStream(file)) {
                        byte[] buf = new byte[65536];
                        for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
                    }
                    if (!HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(digest.substring(7))) return false;
                }
            }
            try (ZipFile zip = new ZipFile(file.toFile())) {
                ZipEntry meta = zip.getEntry("fabric.mod.json");
                if (meta == null) return false;
                try (InputStream in = zip.getInputStream(meta)) {
                    return MOD_ID.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8)).find();
                }
            }
        } catch (Exception e) {
            return false;
        }
    }
}
