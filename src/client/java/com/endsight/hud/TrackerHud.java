package com.endsight.hud;

import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/** A compact colored text readout, with Minecraft shadows rather than a menu card. */
public final class TrackerHud {
    public static final int PAD = 2, HEADER_H = 12, ROW_H = 11;
    private TrackerHud() { }

    public static int[] draw(GuiGraphicsExtractor g, Font font, int x, int y, String title,
                             String state, int stateColour, List<HudCard.Row> rows, float progress) {
        String heading = "§6" + title;
        int w = Math.max(110, PAD * 2 + font.width(heading) + 16 + font.width(state));
        for (HudCard.Row row : rows) w = Math.max(w, PAD * 2 + font.width(row.label()) + 12 + font.width(row.value()));
        int h = PAD * 2 + HEADER_H + rows.size() * ROW_H;
        if (g == null) return new int[]{w, h};
        Draw.text(g, font, heading, x + PAD, y + PAD, Theme.text());
        String shownState = state.startsWith("[") && net.minecraft.client.Minecraft.getInstance().screen != null
                ? "§n" + state : state;
        Draw.textRight(g, font, shownState, x + w - PAD, y + PAD, stateColour);
        int ry = y + PAD + HEADER_H;
        for (HudCard.Row row : rows) {
            Draw.text(g, font, row.label(), x + PAD, ry, Draw.lerp(Theme.muted(), Theme.text(), 0.8f));
            Draw.textRight(g, font, row.value(), x + w - PAD, ry, row.colour());
            ry += ROW_H;
        }
        return new int[]{w, h};
    }
}
