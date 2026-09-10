package com.endsight.ui;

import java.util.List;

/**
 * A named colour scheme, and the maths that turns seven colours into a full UI.
 *
 * Ported from the desk dashboard's theme picker so the mod and the website can wear
 * the same palette and actually match. A palette lists only the colours that carry
 * MEANING - accent is a forecast or a selection, pos is realised money or a healthy
 * state, neg needs looking at, obs is a measurement - plus text at three weights.
 *
 * Every surface is DERIVED, never listed. Each level is a target contrast ratio
 * against the background, blended along the palette's own muted tint, so a new
 * palette keeps its hue, gets the same depth as every other one, and needs no
 * hand-tuning. Copying literal surface colours from another product is what made
 * the dashboard look flat before it worked this way: at 1.04 contrast the fill is
 * invisible and the 1px border ends up doing all the work.
 */
public record Palette(String name,
                      int bg, int accent, int text, int muted, int dim,
                      int pos, int neg, int obs, boolean light) {

    // ── the palettes ──────────────────────────────────────────────────────────

    public static final Palette DESK = new Palette("Desk",
            0x0A0A0C, 0x9B8CF5, 0xECE9E4, 0x8E8D96, 0x5A5964, 0x46B06A, 0xD2635D, 0x4FC3C7, false);

    public static final List<Palette> ALL = List.of(
            DESK,
            new Palette("Midnight Violet",
                    0x0C0A12, 0xA855F7, 0xECE8F0, 0x827C8C, 0x5C5866, 0x50D28C, 0xF05A6E, 0x64B4FF, false),
            new Palette("Steel Blue",
                    0x0A0A0B, 0x4682B4, 0xE6E6E6, 0x7A7A7A, 0x5F5F5F, 0x3FBF7F, 0xE05A5A, 0x5DA9E9, false),
            new Palette("Crimson Noir",
                    0x080808, 0xDC2626, 0xF0F0F0, 0x828282, 0x5A5A5A, 0x46C86E, 0xFF5050, 0x50AAFF, false),
            new Palette("Forest Emerald",
                    0x0D1410, 0x22C55E, 0xE8F0EA, 0x78877E, 0x56625B, 0x2DDC78, 0xE15050, 0x4BAFFF, false),
            new Palette("Sunset Horizon",
                    0x160E12, 0xFF7D41, 0xF5EEEB, 0x91827D, 0x695F5A, 0x50CD7D, 0xF05F5F, 0x69AFFF, false),
            new Palette("Arctic Frost",
                    0xECF2F8, 0x0084FF, 0x1E2630, 0x788291, 0xA0A8B4, 0x23B46E, 0xDC4646, 0x0091FF, true));

    // ── the surface ladder ────────────────────────────────────────────────────

    /**
     * The ground everything sits on.
     *
     * Driven toward near-black first so panels have somewhere to sit ABOVE. A light
     * palette inverts: darkening its panels would drop the muted text drawn for them
     * to unreadable, so its page greys down and its panels climb toward white, which
     * is the direction those text colours were chosen against.
     */
    public int ground() {
        return 0xFF000000 | mix(bg, light ? 0xFFFFFF : 0x000000, light ? 0.10f : 0.60f);
    }

    private int tint() {
        return light ? 0xFFFFFF : muted;
    }

    public int input()   { return step(1.06f); }   // fields read recessed
    public int hair()    { return step(1.18f); }   // row dividers
    public int surface() { return step(1.32f); }   // panels, sidebar
    public int line()    { return step(1.62f); }   // borders
    public int raised()  { return step(1.80f); }   // cards inside panels
    public int hover()   { return step(2.05f); }   // a card under the cursor

    /** Solve for the blend that hits a contrast ratio against the ground. */
    private int step(float target) {
        int base = ground() & 0xFFFFFF;
        int hue = tint();
        float lo = 0f, hi = 1f;
        for (int i = 0; i < 20; i++) {
            float m = (lo + hi) / 2f;
            if (contrast(base, mix(base, hue, m)) < target) lo = m; else hi = m;
        }
        return 0xFF000000 | mix(base, hue, (lo + hi) / 2f);
    }

    // ── colour maths ──────────────────────────────────────────────────────────

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + ((((b >> 16) & 0xFF)) - ((a >> 16) & 0xFF)) * t);
        int g = Math.round(((a >> 8) & 0xFF) + ((((b >> 8) & 0xFF)) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static float toLinear(int c) {
        float v = c / 255f;
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4);
    }

    private static float luminance(int rgb) {
        return 0.2126f * toLinear((rgb >> 16) & 0xFF)
                + 0.7152f * toLinear((rgb >> 8) & 0xFF)
                + 0.0722f * toLinear(rgb & 0xFF);
    }

    private static float contrast(int a, int b) {
        float x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + 0.05f) / (Math.min(x, y) + 0.05f);
    }

    // ── opaque accessors, so callers never deal with the missing alpha ────────

    public int accentColor() { return 0xFF000000 | accent; }
    public int textColor()   { return 0xFF000000 | text; }
    public int mutedColor()  { return 0xFF000000 | muted; }
    public int dimColor()    { return 0xFF000000 | dim; }
    public int posColor()    { return 0xFF000000 | pos; }
    public int negColor()    { return 0xFF000000 | neg; }
    public int obsColor()    { return 0xFF000000 | obs; }
}
