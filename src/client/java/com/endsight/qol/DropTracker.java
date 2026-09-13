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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What has dropped this session, on screen.
 *
 * One row per item with its count, newest at the top, so a haul reads at a glance -
 * "12 Summoning Eye, 2 Golden Eye" - rather than as a scroll of identical lines. Sits
 * with the other readouts and moves with them.
 */
public final class DropTracker {

    private DropTracker() {
    }

    private static final int MAX_ROWS = 8;

    private static boolean enabled = false;
    private static String minTier = "Rare and up";
    /** Item -> count, in first-seen order; drawn newest first. */
    private static final Map<String, Integer> counts = new LinkedHashMap<>();

    public static Module module() {
        return new Module("qol.droptracker", "Drop Tracker",
                "Every drop this session, counted, on screen.", "Quality of Life",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Choice("Show from",
                                "Lowest tier worth a row. Crazy rare and RNGesus count as legendary.",
                                Drops.TIERS, () -> minTier, v -> minTier = v),
                        new Setting.Action("Move readout",
                                "Drag it, and every other readout, where you want.",
                                "Move", HudPlacementScreen::open),
                        new Setting.Action("Reset",
                                "Clear the list.",
                                "Reset", counts::clear),
                        new Setting.Action("Reload tiers",
                                "Re-read config/endsight/drops.txt after editing it.",
                                "Reload", Drops::reload)));
    }

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            Drops.Drop d = Drops.parse(message.getString());
            if (d == null || !Drops.passes(d, minTier)) return;
            counts.merge(d.item(), 1, Integer::sum);
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "drops"),
                (g, delta) -> draw(g));
        HudLayout.register("qol.droptracker", "Drop Tracker", 0f, 0.7f,
                (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    private static void draw(GuiGraphicsExtractor g) {
        if (!enabled || counts.isEmpty()) return;
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
            List<Map.Entry<String, Integer>> all = new ArrayList<>(counts.entrySet());
            for (int i = all.size() - 1; i >= 0 && rows.size() < MAX_ROWS; i--) {
                rows.add(new String[]{all.get(i).getKey(), String.valueOf(all.get(i).getValue())});
            }
        }
        String title = "DROPS";
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
