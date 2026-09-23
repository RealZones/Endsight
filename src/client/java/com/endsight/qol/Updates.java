package com.endsight.qol;

import com.endsight.hud.Toast;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A word when a newer Endsight is out.
 *
 * Asks GitHub for the latest release - the same one the workflow publishes on every
 * push - a few seconds after you are in a world and every five minutes after that,
 * so someone already playing hears about an update within minutes of it going live,
 * not the next time they restart. Five minutes is a twelfth of GitHub's unsigned
 * allowance per address, which leaves room for a whole house on one connection. One toast and one chat line per version, with the link; nothing
 * is downloaded and nothing repeats until the version changes again.
 *
 * Off the render thread: the request runs on its own thread and only the result is
 * handed back to the client, because a stalled GitHub must not stall the game.
 */
public final class Updates {

    private Updates() {
    }

    private static final String LATEST = "https://api.github.com/repos/RealZones/Endsight/releases/latest";
    private static final long FIRST_MS = 8_000;
    private static final long EVERY_MS = 5 * 60_000;

    private static long startedAt, lastCheck;
    private static String told = "";
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public static void init() {
        startedAt = System.currentTimeMillis();
        // Set by the updater at pre-launch - only the endsight-autoupdate jar has one.
        boolean updater = System.getProperty("endsight.updater") != null;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // With the updater installed it does the asking, once a launch, and leaves
            // what it found in a system property; this only says it, once. A download
            // link would be telling someone to do by hand what is already done.
            if (updater) {
                updaterSaid(mc);
                return;
            }
            if (mc.player == null) return;
            long now = System.currentTimeMillis();
            if (now - startedAt < FIRST_MS || now - lastCheck < EVERY_MS) return;
            lastCheck = now;
            check();
        });
    }

    /** The version this jar was built as, from the mod's own metadata. */
    public static String current() {
        return FabricLoader.getInstance().getModContainer("endsight")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
    }

    private static void check() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Endsight/" + current())
                .timeout(Duration.ofSeconds(15)).GET().build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(res -> {
            if (res.statusCode() != 200) return;
            try {
                JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
                // Tags are "0.4.0-1b50718": the version, then the commit it was cut from.
                String tag = o.get("tag_name").getAsString();
                String version = tag.contains("-") ? tag.substring(0, tag.indexOf('-')) : tag;
                String url = o.get("html_url").getAsString();
                Minecraft.getInstance().execute(() -> found(version, url));
            } catch (RuntimeException ignored) {
                // A page that is not the release JSON - GitHub down, a proxy - is no update.
            }
        });
    }

    private static void found(String version, String url) {
        if (!newer(version, current()) || version.equals(told)) return;
        told = version;
        Toast.big("Endsight Update Available", "Version " + version);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.sendSystemMessage(Component.literal("")
                .append(Component.literal("[Endsight] ").withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal("Update available: " + version + " (you have " + current() + "). ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Download").withStyle(s -> s
                        .withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(url))))));
    }

    private static String updaterTold = "";

    /** Fifteen seconds: long enough to be read from the title screen, then out of the way. */
    private static final net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId UPDATE_TOAST =
            new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(15_000L);

    /**
     * An update is installed as the game closes - Fabric has every jar open before any
     * mod runs, so the launch it is found on cannot be the launch it runs on. So the
     * launch goes on as normal and a small box says the new version is waiting.
     *
     * Minecraft's own toast rather than ours: ours is drawn with the HUD, which does not
     * exist on the title screen, and the title screen is where the download usually
     * lands. Not while the loading screen is up, or it would time out behind it.
     */
    private static void updaterSaid(Minecraft mc) {
        String s = System.getProperty("endsight.update", "");
        if (s.equals(updaterTold) || !(s.startsWith("ready:") || s.startsWith("stuck:") || s.startsWith("failed"))) return;
        // What went wrong is said plainly, with the updater's code (E1-E7, listed in
        // EndsightUpdater), so the player can just say the code. "stuck" is an update
        // that was downloaded and announced but is still not in on the next launch.
        if (!s.startsWith("ready:")) {
            if (mc.player == null) return;                  // a chat line, so it waits for a world
            updaterTold = s;
            String[] p = s.split(":");
            String code = p[p.length - 1].startsWith("E") ? p[p.length - 1] : "E1";
            boolean offline = code.equals("E1");
            String text = s.startsWith("stuck:") ? "Update " + p[1] + " couldn't install (" + code + "). Ask Fear."
                    : offline ? "Couldn't check for updates (E1)."
                    : "Couldn't download the update (" + code + "). Ask Fear.";
            mc.player.sendSystemMessage(Component.literal("")
                    .append(Component.literal("[Endsight] ").withStyle(ChatFormatting.LIGHT_PURPLE))
                    .append(Component.literal(text).withStyle(offline ? ChatFormatting.GRAY : ChatFormatting.RED)));
            return;
        }
        if (mc.getOverlay() != null) return;
        updaterTold = s;
        net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.getToastManager(), UPDATE_TOAST,
                Component.literal("Endsight " + s.substring(6) + " downloaded"),
                Component.literal("Relaunch to update"));
    }

    /** 0.10.0 beats 0.9.1: compared a number at a time, not as strings. */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
