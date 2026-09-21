package com.endsight.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;

/**
 * Drawing built from the two calls this version actually gives us: fill and text.
 *
 * No blit, no scissor, no pose stack, no custom RenderPipeline. That is a choice,
 * not a limitation being worked around - a package meant to be copied into another
 * mod should depend on as little of the render API as possible, because that is the
 * part that changes every Minecraft version. Rounded corners cost a handful of extra
 * fills and survive updates that a shader would not.
 */
public final class Draw {

    private Draw() {
    }

    public static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, color);
    }

    /**
     * A rounded rectangle drawn as one body plus two banded caps.
     */
    public static void roundedRect(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                   int radius, int color) {
        if (w <= 0 || h <= 0) return;

        int r = Math.min(radius, Math.min(w, h) / 2);

        if (r <= 0) {
            rect(g, x, y, w, h, color);
            return;
        }

        g.fill(x, y + r, x + w, y + h - r, color);

        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;

            int inset = (int) Math.round(
                    r - Math.sqrt(Math.max(0, (double) r * r - dy * dy))
            );

            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
    }

    /**
     * A rounded rectangle with per-corner control.
     */
    public static void roundedRect(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                   int radius, int color,
                                   boolean tl, boolean tr, boolean bl, boolean br) {
        if (w <= 0 || h <= 0) return;

        int r = Math.min(radius, Math.min(w, h) / 2);

        if (r <= 0 || (!tl && !tr && !bl && !br)) {
            rect(g, x, y, w, h, color);
            return;
        }

        g.fill(x, y + r, x + w, y + h - r, color);

        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;

            int inset = (int) Math.round(
                    r - Math.sqrt(Math.max(0, (double) r * r - dy * dy))
            );

            g.fill(
                    x + (tl ? inset : 0),
                    y + i,
                    x + w - (tr ? inset : 0),
                    y + i + 1,
                    color
            );

            g.fill(
                    x + (bl ? inset : 0),
                    y + h - i - 1,
                    x + w - (br ? inset : 0),
                    y + h - i,
                    color
            );
        }
    }

    /**
     * The settings gear, drawn as a font glyph rather than plotted pixel by pixel.
     *
     * Three hand-drawn versions failed here: rectangles tuned into a washer, a stroked
     * ring that read as a donut, and a 15x13 pixel map that turned to mush at the size
     * it is actually used. The problem was never the tuning - a gear is curves and fine
     * gaps, and neither survives being built from axis-aligned fills at ten pixels.
     *
     * Minecraft loads unifont as a fallback (confirmed in the client log:
     * unifont_all_no_pua), which covers U+2699 GEAR. So the game already ships a gear
     * drawn by someone who does this properly, at exactly the size we want, hinted for
     * a pixel grid. Using it is less code and a better icon.
     *
     * If it ever renders as a box, the fallback font stopped covering it and this needs
     * to go back to drawing - that is the one failure mode to watch for.
     */
    public static void gearIcon(GuiGraphicsExtractor g, Font font, int cx, int cy, int color) {
        String glyph = "⚙";
        g.text(font, glyph, cx - font.width(glyph) / 2, cy - 4, color);
    }

    /**
     * Rounded outline, drawn as a filled shape with a smaller one punched into it.
     */
    public static void roundedOutline(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                      int radius, int border, int fill) {
        roundedRect(g, x, y, w, h, radius, border);
        roundedRect(
                g,
                x + 1,
                y + 1,
                w - 2,
                h - 2,
                Math.max(0, radius - 1),
                fill
        );
    }

    /**
     * A pane of glass: a see-through fill with a faint rim and a lighter line along the
     * top. The rim is what makes a translucent box read as glass rather than as a box
     * nobody filled in; the top line is the light catching its edge. Both are the text
     * colour at low alpha, so they follow the palette - pale on dark, dark on pale.
     */
    public static void glass(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius) {
        if (w <= 2 || h <= 2) return;
        roundedOutline(g, x, y, w, h, radius, alpha(Theme.text(), 0.14f), alpha(Theme.bg(), 0.5f));
        rect(g, x + radius, y + 1, w - radius * 2, 1, alpha(Theme.text(), 0.10f));
    }

    /** A pane inside a pane: no darker, just a rim and a breath of light. */
    public static void glassInner(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius) {
        if (w <= 2 || h <= 2) return;
        roundedOutline(g, x, y, w, h, radius, alpha(Theme.text(), 0.10f), alpha(Theme.text(), 0.04f));
    }

    /** Blend two ARGB colours. t=0 gives a, t=1 gives b. */
    public static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));

        int aa = (a >>> 24) & 0xFF;
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;

        int ba = (b >>> 24) & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;

        int na = (int) (aa + (ba - aa) * t);
        int nr = (int) (ar + (br - ar) * t);
        int ng = (int) (ag + (bg - ag) * t);
        int nb = (int) (ab + (bb - ab) * t);

        return (na << 24) | (nr << 16) | (ng << 8) | nb;
    }

    /** Same colour at a different opacity, for glows and pressed states. */
    public static int alpha(int color, float a) {
        int na = (int) (
                Math.max(0f, Math.min(1f, a)) * 255
        );

        return (na << 24) | (color & 0x00FFFFFF);
    }

    public static void text(GuiGraphicsExtractor g, Font font, String s,
                            int x, int y, int color) {
        g.text(font, s, x, y, color);
    }

    public static void textRight(GuiGraphicsExtractor g, Font font, String s,
                                 int right, int y, int color) {
        g.text(font, s, right - font.width(s), y, color);
    }

    public static void textCentered(GuiGraphicsExtractor g, Font font, String s,
                                    int cx, int y, int color) {
        g.text(font, s, cx - font.width(s) / 2, y, color);
    }

    /**
     * Cut to fit, with an ellipsis when it does not.
     */
    public static String fit(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;

        String ellipsis = "...";
        int room = maxWidth - font.width(ellipsis);

        if (room <= 0) return ellipsis;

        StringBuilder out = new StringBuilder();

        for (int i = 0; i < s.length(); i++) {
            if (font.width(out.toString() + s.charAt(i)) > room) break;
            out.append(s.charAt(i));
        }

        return out.append(ellipsis).toString();
    }

    /**
     * Break text onto at most {@code maxLines}, ellipsising only the last one.
     */
    public static java.util.List<String> wrap(Font font, String s,
                                              int maxWidth, int maxLines) {
        java.util.List<String> out = new java.util.ArrayList<>();

        StringBuilder line = new StringBuilder();

        for (String word : s.split(" ")) {
            String candidate = line.isEmpty()
                    ? word
                    : line + " " + word;

            if (font.width(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }

            if (!line.isEmpty()) {
                out.add(line.toString());
            }

            line.setLength(0);
            line.append(word);

            if (out.size() == maxLines - 1) break;
        }

        if (out.size() < maxLines && !line.isEmpty()) {
            out.add(line.toString());
        }

        if (out.isEmpty()) {
            return java.util.List.of(fit(font, s, maxWidth));
        }

        int last = out.size() - 1;
        int consumed = 0;

        for (String l : out) {
            consumed += l.length() + 1;
        }

        if (consumed < s.length()) {
            out.set(
                    last,
                    fit(font, out.get(last) + " ...", maxWidth)
            );
        }

        return out;
    }

    /** A soft edge under a header or over a scroll boundary. */
    public static void fadeDown(GuiGraphicsExtractor g, int x, int y,
                                int w, int h, int color) {
        for (int i = 0; i < h; i++) {
            float t = 1f - (float) i / h;

            g.fill(
                    x,
                    y + i,
                    x + w,
                    y + i + 1,
                    alpha(color, t * 0.5f)
            );
        }
    }
}