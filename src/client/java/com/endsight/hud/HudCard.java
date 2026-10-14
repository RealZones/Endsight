package com.endsight.hud;

import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/** Compact tracker backing and readable columns, without changing other HUD modules. */
public final class HudCard {
    private static final int PAD = 4, HEADER_H = 12, ROW_H = 12, GAP = 14;

    private HudCard() { }

    public record Row(String label, String value, int colour) {
        public Row(String label, String value) { this(label, value, Theme.text()); }
    }

    public record TableRow(String label, String first, String second) { }

    private static int labelColour() {
        // Menu dim text disappeared over End stone. The backing and this brighter
        // secondary text preserve hierarchy without asking the world to be dark.
        return Draw.lerp(Theme.muted(), Theme.text(), 0.55f);
    }

    private static void frame(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                              String title, String state, int stateColour) {
        Draw.roundedRect(g, x, y, w, h, 3, Draw.alpha(Theme.bg(), 0.76f));
        Draw.text(g, font, title, x + PAD, y + PAD, Theme.text());
        Draw.textRight(g, font, state, x + w - PAD, y + PAD, stateColour);
    }

    private static int titleWidth(Font font, String title, String state) {
        return PAD * 2 + font.width(title) + GAP + font.width(state);
    }

    private static int rowWidth(Font font, Row row) {
        return PAD * 2 + font.width(row.label()) + GAP + font.width(row.value());
    }

    private static int rowsHeight(int count) { return count == 0 ? 0 : count * ROW_H - 2; }

    public static int[] draw(GuiGraphicsExtractor g, Font font, int x, int y,
                             String title, String state, int stateColour, List<Row> rows, float progress) {
        int w = Math.max(120, titleWidth(font, title, state));
        for (Row row : rows) w = Math.max(w, rowWidth(font, row));
        int h = PAD * 2 + HEADER_H + rowsHeight(rows.size());
        if (g == null) return new int[]{w, h};
        frame(g, font, x, y, w, h, title, state, stateColour);
        int ry = y + PAD + HEADER_H;
        for (Row row : rows) {
            Draw.text(g, font, row.label(), x + PAD, ry, labelColour());
            Draw.textRight(g, font, row.value(), x + w - PAD, ry, row.colour());
            ry += ROW_H;
        }
        if (progress >= 0) {
            // The stage indicator fits in bottom padding, so an alive/rising change
            // does not resize the card or push a neighbouring timer around.
            int barW = w - PAD * 2;
            Draw.rect(g, x + PAD, y + h - 2, barW, 1, Draw.alpha(Theme.text(), 0.18f));
            Draw.rect(g, x + PAD, y + h - 2, Math.round(barW * Math.clamp(progress, 0f, 1f)), 1, Theme.obs());
        }
        return new int[]{w, h};
    }

    /** Separate numeric columns keep powder type, session gain and rate unambiguous. */
    public static int[] table(GuiGraphicsExtractor g, Font font, int x, int y,
                              String title, String state, int stateColour,
                              String firstTitle, String secondTitle, List<TableRow> rows, List<Row> footer) {
        int labels = 0, first = font.width(firstTitle), second = font.width(secondTitle);
        for (TableRow row : rows) {
            labels = Math.max(labels, font.width(row.label()));
            first = Math.max(first, font.width(row.first()));
            second = Math.max(second, font.width(row.second()));
        }
        int w = Math.max(146, Math.max(titleWidth(font, title, state), PAD * 2 + labels + first + second + GAP * 2));
        for (Row row : footer) w = Math.max(w, rowWidth(font, row));
        int tableH = rows.isEmpty() ? 0 : 13 + rowsHeight(rows.size());
        int gap = !rows.isEmpty() && !footer.isEmpty() ? 4 : 0;
        int h = PAD * 2 + HEADER_H + tableH + gap + rowsHeight(footer.size());
        if (g == null) return new int[]{w, h};
        frame(g, font, x, y, w, h, title, state, stateColour);
        int ry = y + PAD + HEADER_H;
        int secondRight = x + w - PAD, firstRight = secondRight - second - GAP;
        if (!rows.isEmpty()) {
            Draw.textRight(g, font, firstTitle, firstRight, ry, labelColour());
            Draw.textRight(g, font, secondTitle, secondRight, ry, labelColour());
            ry += 13;
            for (TableRow row : rows) {
                Draw.text(g, font, row.label(), x + PAD, ry, labelColour());
                Draw.textRight(g, font, row.first(), firstRight, ry, Theme.text());
                Draw.textRight(g, font, row.second(), secondRight, ry, Theme.obs());
                ry += ROW_H;
            }
            ry -= 2;
        }
        ry += gap;
        for (Row row : footer) {
            Draw.text(g, font, row.label(), x + PAD, ry, labelColour());
            Draw.textRight(g, font, row.value(), x + w - PAD, ry, row.colour());
            ry += ROW_H;
        }
        return new int[]{w, h};
    }
}
