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
 * push - shortly after launch and every half hour after that, so someone already
 * playing hears about an update within the half hour of it going live, not the next
 * time they restart. One toast and one chat line per version, with the link; nothing
 * is downloaded and nothing repeats until the version changes again.
 *
 * Off the render thread: the request runs on its own thread and only the result is
 * handed back to the client, because a stalled GitHub must not stall the game.
 */
public final class Updates {

    private Updates() {
    }

    private static final String LATEST = "https://api.github.com/repos/RealZones/Endsight/releases/latest";
    private static final long FIRST_MS = 20_000;
    private static final long EVERY_MS = 30 * 60_000;

    private static long startedAt, lastCheck;
    private static String told = "";
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public static void init() {
        startedAt = System.currentTimeMillis();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
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
        Toast.changed("Update available", current() + " → " + version);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.sendSystemMessage(Component.literal("")
                .append(Component.literal("[Endsight] ").withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal("Update available: " + version + " (you have " + current() + "). ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Download").withStyle(s -> s
                        .withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(url))))));
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
