package com.endsight.qol;

import com.endsight.hud.HudLayout;
import com.endsight.hud.HudPlacementScreen;
import com.endsight.hud.Readout;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What has dropped, on screen: this session, or ever.
 *
 * One row per item with its count, newest at the top, so a haul reads at a glance -
 * "12 Summoning Eye, 2 Golden Eye" - rather than as a scroll of identical lines. Sits
 * with the other readouts and moves with them.
 *
 * Two counts are kept. The session's starts empty each launch. The total lives in
 * {@code config/endsight/drops-total.txt} and is written on every drop - drops are rare
 * enough that a write each is nothing, and a crash an hour later then costs nothing.
 * One mode button switches the readout between them, as Damage Numbers does.
 */
public final class DropTracker {

    private DropTracker() {
    }

    private static final int MAX_ROWS = 8;
    private static final String SESSION = "Session", TOTAL = "Total";

    private static boolean enabled = false;
    private static String minTier = "Any drop";
    private static String mode = SESSION;
    /** Item -> count, in first-seen order; drawn newest first. */
    private static final Map<String, Integer> session = new LinkedHashMap<>();
    private static final Map<String, Integer> total = new LinkedHashMap<>();

    public static Module module() {
        return new Module("qol.droptracker", "Drop Tracker",
                "Every drop counted on screen, this session or all-time.", "Trackers",
                () -> enabled, v -> enabled = v,
                concat(List.of(
                        new Setting.Choice("Mode",
                                "This session's drops, or every drop since you started keeping count.",
                                List.of(SESSION, TOTAL), () -> mode, v -> mode = v),
                        new Setting.Choice("Show from",
                                "Lowest tier worth a row. Crazy rare and RNGesus count as legendary.",
                                Drops.TIERS, () -> minTier, v -> minTier = v),
                        new Setting.Action("Move readout",
                                "Drag it, and every other readout, where you want.",
                                "Move", HudPlacementScreen::open),
                        new Setting.Action("Reset",
                                "Clear whichever count is showing.",
                                "Reset", DropTracker::reset),
                        new Setting.Action("Reload tiers",
                                "Re-read config/endsight/drops.txt after editing it.",
                                "Reload", Drops::reload)),
                        Drops.tierNotes()));
    }

    private static List<Setting> concat(List<Setting> a, List<Setting> b) {
        List<Setting> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    public static void init() {
        load();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            Drops.Drop d = Drops.parse(message.getString());
            if (d == null || !Drops.passes(d, minTier)) return;
            session.merge(d.item(), 1, Integer::sum);
            total.merge(d.item(), 1, Integer::sum);
            save();
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "drops"),
                (g, delta) -> draw(g));
        HudLayout.register("qol.droptracker", "Drop Tracker", 0.011f, 0.201f,
                (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    private static void reset() {
        if (mode.equals(TOTAL)) {
            total.clear();
            save();
        } else {
            session.clear();
        }
    }

    // ── the total, on disk ────────────────────────────────────────────────────

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("drops-total.txt");
    }

    private static void load() {
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                int tab = line.lastIndexOf('\t');
                if (tab <= 0) continue;
                try {
                    total.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1).trim()));
                } catch (NumberFormatException ignored) {
                    // A hand-edited line that is not a number: skipped, not fatal.
                }
            }
        } catch (IOException e) {
            System.err.println("[Endsight] could not read drops-total.txt: " + e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            List<String> lines = new ArrayList<>();
            total.forEach((item, n) -> lines.add(item + "\t" + n));
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Endsight] could not write drops-total.txt: " + e);
        }
    }

    // ── drawing ───────────────────────────────────────────────────────────────

    private static void draw(GuiGraphicsExtractor g) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        Font font = mc.font;
        int[] size = drawAt(null, font, 0, 0, false);
        drawAt(g, font,
                HudLayout.x("qol.droptracker", size[0], mc.getWindow().getGuiScaledWidth()),
                HudLayout.y("qol.droptracker", size[1], mc.getWindow().getGuiScaledHeight()),
                false);
    }

    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        List<String[]> rows = new ArrayList<>();
        if (sample) {
            rows.add(new String[]{"Golden Eye", "2"});
            rows.add(new String[]{"Summoning Eye", "12"});
        } else {
            List<Map.Entry<String, Integer>> all = new ArrayList<>((mode.equals(TOTAL) ? total : session).entrySet());
            for (int i = all.size() - 1; i >= 0 && rows.size() < MAX_ROWS; i--) {
                rows.add(new String[]{all.get(i).getKey(), String.valueOf(all.get(i).getValue())});
            }
            // Drawn even with nothing to show. Hidden until the first drop, it looked
            // switched off - and with Summoning Eye tiered common, "Rare and up" meant
            // a whole zealot session could pass without it ever appearing.
            if (rows.isEmpty()) rows.add(new String[]{"None yet", "-"});
        }
        String title = mode.equals(TOTAL) ? "DROPS  TOTAL" : "DROPS";
        int labelW = 0, valueW = 0;
        for (String[] r : rows) {
            labelW = Math.max(labelW, font.width(r[0]));
            valueW = Math.max(valueW, font.width(r[1]));
        }
        int w = Math.max(font.width(title) + 20, 8 + labelW + 12 + valueW);
        int h = Readout.ROW_H + 3 + rows.size() * (Readout.ROW_H + 2);

        if (g != null) {
            Draw.rect(g, x, y, 2, Readout.ROW_H - 1, Theme.accent());
            Draw.text(g, font, title, x + 8, y, Theme.muted());
            int ry = y + Readout.ROW_H + 3;
            for (String[] r : rows) {
                Draw.text(g, font, r[0], x + 8, ry, Theme.dim());
                Draw.textRight(g, font, r[1], x + w, ry, Theme.text());
                ry += Readout.ROW_H + 2;
            }
        }
        return new int[]{w, h};
    }
}
