package com.endsight.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/**
 * The picker a {@link Setting.Color} row opens: the sixteen Minecraft colours, a
 * saturation/value square with a hue bar beside it, a hex field and a Chroma chip.
 *
 * Built from fills only, like everything else here: the square is a grid of 3px cells
 * and the bar is a stack of 1px rows, which is a thousand-odd rects a frame and only
 * while the picker is open.
 *
 * Hue, saturation and value are kept here rather than read back from the colour each
 * frame. A colour with no saturation has no hue, so dragging the value to black and
 * back would snap the hue to red - the picker remembers where you were instead, and
 * the colour is written from that.
 */
final class ColorPicker {

    private static final int[] PRESETS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    private static final int SQ = 120, SQ_H = 81, CELL = 3;   // the square and its cell
    private static final int BAR = 10;                          // hue bar width
    private static final int FIELD_W = 66;
    static final int W = 8 + SQ + 6 + BAR + 8 + FIELD_W + 8;
    static final int H = 8 + 9 + 8 + SQ_H + 8;

    private Setting.Color setting;
    private float hue, sat, val;
    /** 0 nothing, 1 the square, 2 the bar. */
    private int drag;
    private boolean typing;
    private String hex = "";

    // geometry, set by layout() each frame and again before a click
    private int x, y;

    Setting.Color setting() {
        return setting;
    }

    boolean open() {
        return setting != null;
    }

    boolean typing() {
        return typing;
    }

    void open(Setting.Color c) {
        setting = c;
        drag = 0;
        typing = false;
        int v = c.get().getAsInt();
        float[] hsv = hsv(v == Setting.Color.CHROMA ? 0xFF2BFF6A : v);
        hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
    }

    void close() {
        setting = null;
        drag = 0;
        typing = false;
    }

    /** Under the row's right edge, or above it when the page runs out below. */
    void layout(int rowX, int rowY, int rowW, int rowH, int minX, int maxX, int bottom) {
        x = Math.max(minX, Math.min(rowX + rowW - W, maxX - W));
        y = rowY + rowH + 2;
        if (y + H > bottom) y = rowY - H - 2;
    }

    // ── geometry ─────────────────────────────────────────────────────────────

    private int presetsY() { return y + 8; }
    private int sqX() { return x + 8; }
    private int sqY() { return y + 8 + 9 + 8; }
    private int barX() { return sqX() + SQ + 6; }
    private int fieldX() { return barX() + BAR + 8; }
    private int fieldY() { return sqY(); }
    private int chromaY() { return sqY() + 26; }

    // ── drawing ──────────────────────────────────────────────────────────────

    void draw(GuiGraphicsExtractor g, Font font, int mx, int my) {
        if (setting == null) return;
        Draw.roundedOutline(g, x, y, W, H, Theme.RADIUS, Theme.line(), Theme.raised());

        // the sixteen, 9px each with 3 between, hovered one a pixel bigger
        for (int i = 0; i < PRESETS.length; i++) {
            int px = sqX() + i * 12, py = presetsY();
            boolean hov = in(px - 1, py - 1, 11, 11, mx, my);
            Draw.roundedRect(g, px - (hov ? 1 : 0), py - (hov ? 1 : 0), hov ? 11 : 9, hov ? 11 : 9, 2,
                    0xFF000000 | PRESETS[i]);
        }

        // saturation across, value down
        for (int cy = 0; cy < SQ_H; cy += CELL) {
            float v = 1f - cy / (float) (SQ_H - CELL);
            for (int cx = 0; cx < SQ; cx += CELL) {
                float s = cx / (float) (SQ - CELL);
                Draw.rect(g, sqX() + cx, sqY() + cy, CELL, CELL, 0xFF000000 | Mth.hsvToRgb(hue, s, v));
            }
        }
        for (int cy = 0; cy < SQ_H; cy++) {
            Draw.rect(g, barX(), sqY() + cy, BAR, 1, 0xFF000000 | Mth.hsvToRgb(cy / (float) (SQ_H - 1), 1f, 1f));
        }

        // markers: a ring on the square, a bar across the hue
        int kx = sqX() + Math.round(sat * (SQ - 1)), ky = sqY() + Math.round((1f - val) * (SQ_H - 1));
        ring(g, kx, ky, 4, 0xFF000000);
        ring(g, kx, ky, 3, 0xFFFFFFFF);
        int hy = sqY() + Math.round(hue * (SQ_H - 1));
        Draw.rect(g, barX() - 1, hy - 2, BAR + 2, 4, 0xFF000000);
        Draw.rect(g, barX() - 1, hy - 1, BAR + 2, 2, 0xFFFFFFFF);

        // hex field
        int value = setting.get().getAsInt();
        boolean chroma = value == Setting.Color.CHROMA;
        String text = typing ? "#" + hex + (System.currentTimeMillis() / 500 % 2 == 0 ? "_" : "")
                : chroma ? "chroma" : Setting.Color.hex(value);
        Draw.roundedOutline(g, fieldX(), fieldY(), FIELD_W, 20, Theme.RADIUS - 2,
                typing ? Theme.accent() : Theme.line(), Theme.bg());
        Draw.text(g, font, text, fieldX() + 6, fieldY() + 6, typing || !chroma ? Theme.text() : Theme.dim());

        // chroma chip, accent-filled while it is the value
        boolean hov = in(fieldX(), chromaY(), FIELD_W, 20, mx, my);
        int live = Setting.Color.live(Setting.Color.CHROMA);
        Draw.roundedRect(g, fieldX(), chromaY(), FIELD_W, 20, Theme.RADIUS - 2,
                chroma ? live : hov ? Theme.hover() : Theme.surface());
        Draw.textCentered(g, font, "Chroma", fieldX() + FIELD_W / 2, chromaY() + 6,
                chroma ? Theme.bg() : Theme.text());
    }

    private static void ring(GuiGraphicsExtractor g, int cx, int cy, int r, int c) {
        Draw.rect(g, cx - r, cy - r, r * 2 + 1, 1, c);
        Draw.rect(g, cx - r, cy + r, r * 2 + 1, 1, c);
        Draw.rect(g, cx - r, cy - r + 1, 1, r * 2 - 1, c);
        Draw.rect(g, cx + r, cy - r + 1, 1, r * 2 - 1, c);
    }

    // ── input ────────────────────────────────────────────────────────────────

    /** True if the click was inside the picker, whether or not it did anything. */
    boolean click(int mx, int my) {
        if (setting == null || !in(x, y, W, H, mx, my)) return false;
        typing = false;
        for (int i = 0; i < PRESETS.length; i++) {
            if (in(sqX() + i * 12 - 1, presetsY() - 1, 12, 12, mx, my)) {
                apply(0xFF000000 | PRESETS[i]);
                return true;
            }
        }
        if (in(sqX(), sqY(), SQ, SQ_H, mx, my)) {
            drag = 1;
            drag(mx, my);
        } else if (in(barX() - 2, sqY(), BAR + 4, SQ_H, mx, my)) {
            drag = 2;
            drag(mx, my);
        } else if (in(fieldX(), fieldY(), FIELD_W, 20, mx, my)) {
            typing = true;
            hex = "";
        } else if (in(fieldX(), chromaY(), FIELD_W, 20, mx, my)) {
            int v = setting.get().getAsInt();
            if (v == Setting.Color.CHROMA) write();
            else setting.set().accept(Setting.Color.CHROMA);
        }
        return true;
    }

    boolean drag(int mx, int my) {
        if (setting == null || drag == 0) return false;
        if (drag == 1) {
            sat = clamp((mx - sqX()) / (float) (SQ - 1));
            val = 1f - clamp((my - sqY()) / (float) (SQ_H - 1));
        } else {
            hue = clamp((my - sqY()) / (float) (SQ_H - 1));
        }
        write();
        return true;
    }

    void release() {
        drag = 0;
    }

    /** A typed character; hex digits only, and six of them make a colour. */
    boolean charTyped(char c) {
        if (!typing) return false;
        if (hex.length() < 6 && Character.digit(c, 16) >= 0) {
            hex += Character.toUpperCase(c);
            if (hex.length() == 6) apply(Setting.Color.parse(hex));
        }
        return true;
    }

    /** Backspace edits, enter and escape finish; true if the key was taken. */
    boolean keyPressed(int key) {
        if (!typing) return false;
        if (key == 259 && !hex.isEmpty()) hex = hex.substring(0, hex.length() - 1);
        else if (key == 257 || key == 256 || key == 335) typing = false;
        return true;
    }

    // ── colour maths ─────────────────────────────────────────────────────────

    private void apply(int argb) {
        float[] hsv = hsv(argb);
        hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        setting.set().accept(argb);
    }

    private void write() {
        setting.set().accept(0xFF000000 | (Mth.hsvToRgb(hue, sat, val) & 0xFFFFFF));
    }

    private static float[] hsv(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0;
        if (d > 0) {
            if (max == r) h = ((g - b) / d) % 6;
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
            if (h < 0) h += 1;
        }
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }

    private static float clamp(float f) {
        return Math.max(0f, Math.min(1f, f));
    }

    private static boolean in(int x, int y, int w, int h, int px, int py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
