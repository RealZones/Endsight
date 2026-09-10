package com.endsight.hud;

import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * One line of HUD, drawn without a box.
 *
 * A bordered panel floating over the game reads as a menu that forgot to close rather
 * than as a readout.
 * What a HUD actually needs is for the text to be readable and for its state to be
 * visible at a glance; a border does neither. So there is no border and no fill here:
 * an accent tick on the leading edge marks where the element starts, the text carries
 * its own shadow for contrast, and anything with progress gets a bar that fills.
 *
 * Shared by every HUD module on purpose. Two readouts that drift apart look like two
 * mods, and the whole point of the palette is that everything looks like one thing.
 */
public final class Readout {

    private Readout() {
    }

    public static final int ROW_H = 10;
    private static final int TICK_W = 2;
    private static final int GAP = 6;
    private static final int BAR_H = 2;

    /**
     * @param progress 0..1 to draw a filling bar underneath, or -1 for no bar.
     * @param hot      draw the value and tick in accent rather than plain text.
     * @return the height used, so a caller can stack the next one under it.
     */
    public static int draw(GuiGraphicsExtractor g, Font font, int x, int y, int w,
                           String label, String value, boolean hot, float progress) {
        int accent = hot ? Theme.accent() : Draw.lerp(Theme.line(), Theme.accent(), 0.7f);

        // Leading tick. Marks the element without enclosing it.
        Draw.rect(g, x, y, TICK_W, ROW_H - 1, accent);

        int textX = x + TICK_W + GAP;
        Draw.text(g, font, label, textX, y, Theme.muted());
        Draw.textRight(g, font, value, x + w, y, hot ? Theme.accent() : Theme.text());

        if (progress >= 0) {
            int barY = y + ROW_H + 1;
            int barW = w - (TICK_W + GAP);
            Draw.rect(g, textX, barY, barW, BAR_H, Draw.alpha(Theme.line(), 0.55f));
            int filled = Math.round(barW * Math.max(0f, Math.min(1f, progress)));
            if (filled > 0) Draw.rect(g, textX, barY, filled, BAR_H, accent);
            return ROW_H + 1 + BAR_H;
        }
        return ROW_H;
    }

    /**
     * Height of one row, with or without its progress bar.
     *
     * Exists so a caller can know its own size without rendering itself first. The
     * alternative - drawing once off screen to measure - wastes a full pass every frame
     * and, worse, puts a real copy on screen the moment someone gets the off-screen
     * coordinate wrong. That happened: one readout measured at 0,0 and drew a permanent
     * ghost in the corner.
     */
    public static int height(boolean withBar) {
        return withBar ? ROW_H + 1 + BAR_H : ROW_H;
    }

    /**
     * Width for a label/value pair, so callers can right-align against a screen edge.
     *
     * A minimum keeps a readout from jumping in size every time its value ticks from
     * "9s" to "10s" - a readout that changes width while you watch it is the thing
     * that reads as "odd" even when every individual frame is laid out correctly.
     */
    public static int width(Font font, String label, String value) {
        return Math.max(96, TICK_W + GAP + font.width(label) + 16 + font.width(value));
    }
}
