package com.endsight.ui;

import java.util.List;

/**
 * The palette in use, plus the geometry every screen shares.
 *
 * Colours come from the current {@link Palette} rather than being constants, so a
 * theme swap changes the whole UI without a single call site knowing. Geometry
 * stays constant because layout is not a matter of taste.
 *
 * Derived surfaces are cached on swap, not solved per call: each one is a 20-step
 * binary search over a contrast function, and a card asks for four of them, so
 * solving on demand would mean thousands of searches a frame for values that only
 * change when someone clicks a swatch.
 */
public final class Theme {

    private Theme() {
    }

    private static Palette current = Palette.DESK;

    private static int ground, input, hair, surface, line, raised, hover;

    static {
        recompute();
    }

    public static Palette palette() {
        return current;
    }

    public static List<Palette> palettes() {
        return Palette.ALL;
    }

    public static void set(Palette p) {
        if (p == null || p == current) return;
        current = p;
        recompute();
    }

    private static void recompute() {
        ground  = current.ground();
        input   = current.input();
        hair    = current.hair();
        surface = current.surface();
        line    = current.line();
        raised  = current.raised();
        hover   = current.hover();
    }

    // ── ground ────────────────────────────────────────────────────────────────
    public static int bg()      { return ground; }
    public static int surface() { return surface; }
    public static int raised()  { return raised; }
    public static int hover()   { return hover; }
    public static int input()   { return input; }
    public static int line()    { return line; }
    public static int hair()    { return hair; }

    // ── meaning ───────────────────────────────────────────────────────────────
    public static int text()   { return current.textColor(); }
    public static int muted()  { return current.mutedColor(); }
    public static int dim()    { return current.dimColor(); }
    public static int accent() { return current.accentColor(); }
    public static int pos()    { return current.posColor(); }
    public static int neg()    { return current.negColor(); }
    public static int obs()    { return current.obsColor(); }

    /**
     * Wash over the game behind the panel.
     *
     * Lighter on a light palette: a near-black scrim under a near-white panel is a
     * hard edge that makes the panel look pasted on rather than sitting above.
     */
    public static int scrim() {
        return current.light() ? 0x99202020 : 0xB3000000;
    }

    // ── geometry, which is not a matter of taste ──────────────────────────────
    // Generous on purpose. The first pass was correct and cramped - every element
    // was where it belonged and the whole thing still read as a settings dialog,
    // because what separates a client menu from a mod menu is mostly the space
    // around things and how soft the corners are, not the parts themselves.
    /**
     * The panel as a share of the screen rather than a fixed inset.
     *
     * A fixed inset is a different proportion on every monitor - near-fullscreen on a
     * laptop and a floating box on an ultrawide. A share looks the same everywhere,
     * and "make it a bit smaller" becomes one number instead of a guess.
     */
    public static final float PANEL_W_PCT = 0.92f;
    public static final float PANEL_H_PCT = 0.90f;

    public static int panelW(int screenW) { return Math.round(screenW * PANEL_W_PCT); }
    public static int panelH(int screenH) { return Math.round(screenH * PANEL_H_PCT); }
    public static int panelX(int screenW) { return (screenW - panelW(screenW)) / 2; }
    public static int panelY(int screenH) { return (screenH - panelH(screenH)) / 2; }

    public static final int RADIUS      = 10;
    public static final int RADIUS_LG   = 14;
    public static final int PANEL_INSET = 26;
    public static final int SIDEBAR_W   = 186;
    public static final int HEADER_H    = 58;
    public static final int PAD         = 22;
    public static final int GAP         = 14;
    public static final int CARD_H      = 78;
    public static final int CARD_MIN_W  = 232;
    public static final int SECTION_H   = 40;
    public static final int NAV_H       = 30;
    public static final int NAV_GAP     = 4;

    /**
     * How fast an animated value closes on its target, per second.
     *
     * Framerate-independent easing rather than a fixed step, because a fixed step
     * makes the whole UI feel different at 60fps and 240fps - which is exactly the
     * kind of thing that reads as "mod menu" instead of "client".
     */
    public static final float EASE_FAST = 16f;
    public static final float EASE_SLOW = 9f;
}
