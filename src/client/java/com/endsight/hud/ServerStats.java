package com.endsight.hud;

import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.debugchart.LocalSampleLogger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * FPS, ping and the server's tick rate, in one pill.
 *
 * Ping is measured, not read: the tab list's latency is what the server chooses to
 * put there, and this one puts zero. So once a second a ping request goes out - the
 * same packet the F3 network chart sends - and the pong comes back through vanilla's
 * own monitor into its sample log, which is read here. A real round trip, from this
 * side. TPS is not something a client is told, so it is worked out from the one clue
 * there is - the time-sync packet the server sends every twenty of ITS ticks. Between
 * packets this client advances the world clock itself, one per tick, so a packet from
 * a server keeping perfect time changes nothing; one from a server running slow snaps
 * the clock back. Every snap is a moment the clock is exactly the server's, so ticks
 * between snaps over seconds between snaps is the server's rate. Ten seconds of them,
 * good to about half a tick; a server that sends no snap for a while is keeping time.
 *
 * Drawn as a pill and not a readout block because it is three numbers, not a table,
 * and a row of small facts wants to read in one glance, left to right.
 */
public final class ServerStats {

    private ServerStats() {
    }

    private static boolean enabled = false;
    private static boolean fps = true;
    private static boolean ping = true;
    private static boolean tps = true;

    public static Module module() {
        return new Module("hud.stats", "Ping / TPS",
                "FPS, your ping and the server's tick rate, in a pill.", "Visual",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Toggle("FPS", "Frames per second.", () -> fps, v -> fps = v),
                        new Setting.Toggle("Ping", "Round trip to the server, measured once a second.", () -> ping, v -> ping = v),
                        new Setting.Toggle("TPS", "Server ticks per second, worked out from its clock.", () -> tps, v -> tps = v),
                        new Setting.Action("Move readout",
                                "Drag it, and every other readout, where you want.",
                                "Move", HudPlacementScreen::open)));
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(ServerStats::tick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "stats"),
                (g, delta) -> draw(g));
        HudLayout.register("hud.stats", "Ping / TPS", 0.5f, 0.79f,
                (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    // ── the server's clock ────────────────────────────────────────────────────

    private static long lastGameTime = Long.MIN_VALUE;
    private static long lastSnapMs;
    /** (wall ms, game time) at each snap, the last ten seconds of them. */
    private static final ArrayDeque<long[]> snaps = new ArrayDeque<>();
    private static double rate = 20;

    private static void tick(Minecraft mc) {
        if (mc.level == null) {
            lastGameTime = Long.MIN_VALUE;
            snaps.clear();
            rate = 20;
            return;
        }
        long gt = mc.level.getGameTime();
        long now = System.currentTimeMillis();
        if (enabled && ping) requestPing(mc, now);
        if (lastGameTime != Long.MIN_VALUE && gt - lastGameTime != 1) {
            snaps.addLast(new long[]{now, gt});
            lastSnapMs = now;
            while (snaps.size() > 1 && now - snaps.peekFirst()[0] > 10_000) snaps.pollFirst();
            long[] a = snaps.peekFirst(), b = snaps.peekLast();
            double secs = (b[0] - a[0]) / 1000.0;
            if (secs >= 2) rate = Math.max(0, Math.min(20, (b[1] - a[1]) / secs));
        } else if (now - lastSnapMs > 4_000) {
            rate = 20;
        }
        lastGameTime = gt;
    }

    private static long lastRequestMs;

    /** Once a second, ask. Vanilla logs the answer whether or not its chart is open. */
    private static void requestPing(Minecraft mc, long now) {
        if (mc.getConnection() == null || now - lastRequestMs < 1_000) return;
        lastRequestMs = now;
        mc.getConnection().send(new ServerboundPingRequestPacket(Util.getMillis()));
    }

    /** The newest measured round trip; the tab list's number if none has come back yet. */
    private static int latency(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) return -1;
        LocalSampleLogger log = mc.getDebugOverlay().getPingLogger();
        if (log.size() > 0) return (int) log.get(log.size() - 1);
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    // ── drawing ───────────────────────────────────────────────────────────────

    private static final int H = 16;
    private static final int PAD = 8;
    private static final int GAP = 14;

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.level == null || mc.options.hideGui) return;
        int[] size = drawAt(null, mc.font, 0, 0, false);
        drawAt(g, mc.font,
                HudLayout.x("hud.stats", size[0], mc.getWindow().getGuiScaledWidth()),
                HudLayout.y("hud.stats", size[1], mc.getWindow().getGuiScaledHeight()), false);
    }

    /** Label, value, value colour - one per stat that is switched on. */
    private static List<String[]> parts(Minecraft mc, boolean sample) {
        List<String[]> out = new ArrayList<>();
        if (fps) out.add(new String[]{"FPS", sample ? "240" : String.valueOf(mc.getFps())});
        if (ping) {
            int ms = sample ? 48 : latency(mc);
            out.add(new String[]{"PING", ms < 0 ? "-" : ms + "ms"});
        }
        if (tps) out.add(new String[]{"TPS", sample ? "20.0" : String.format("%.1f", rate)});
        return out;
    }

    /** Text at 85% of the font: the pill is a glance, and full-size digits shout. */
    private static final float TEXT = 0.85f;

    private static int width(Font font, String s) {
        return Math.round(font.width(s) * TEXT);
    }

    private static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int colour) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(TEXT, TEXT);
        Draw.text(g, font, s, 0, 0, colour);
        pose.popMatrix();
    }

    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        Minecraft mc = Minecraft.getInstance();
        List<String[]> parts = parts(mc, sample);
        if (parts.isEmpty()) return new int[]{0, 0};

        int w = PAD;
        for (String[] p : parts) w += 5 + 4 + width(font, p[0]) + 4 + width(font, p[1]) + GAP;
        w += PAD - GAP;

        if (g != null) {
            // Solid dark grey on purpose, and the same in every palette: a see-through
            // pill reads as part of the scenery behind it, near-black reads as a hole in
            // the screen, and a number you have to squint at is not a readout.
            Draw.roundedRect(g, x, y, w, H, H / 2, 0xFF2D2D31);
            int cx = x + PAD;
            // Glyphs are seven tall at full size; centre those, not the font's line box.
            int ty = y + Math.round((H - 7 * TEXT) / 2f);
            for (int i = 0; i < parts.size(); i++) {
                String[] p = parts.get(i);
                if (i > 0) {
                    // A hairline between stats, stopping short of the top and bottom.
                    Draw.rect(g, cx - GAP / 2, y + 4, 1, H - 8, Draw.alpha(Theme.text(), 0.22f));
                }
                // The same dot the sidebar puts before "Endsight", so the two read as one thing.
                Draw.roundedRect(g, cx, y + H / 2 - 2, 5, 5, 2, Theme.accent());
                cx += 5 + 4;
                text(g, font, p[0], cx, ty, Theme.muted());
                cx += width(font, p[0]) + 4;
                boolean bad = p[0].equals("TPS") && !sample && rate < 18
                        || p[0].equals("PING") && !sample && latency(mc) > 300;
                text(g, font, p[1], cx, ty, bad ? Theme.neg() : Theme.text());
                cx += width(font, p[1]) + GAP;
            }
        }
        return new int[]{w, H};
    }
}
