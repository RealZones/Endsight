package com.endsight.ui;

/**
 * The profile viewer wears the menu's theme. It had a teal palette of its own, which made
 * opening /pv feel like leaving the mod. Money stays gold in every theme: that colour says coins.
 */
final class ProfileStyle {
    private static final int GOLD = 0xFFE6C27F;
    static int bg() { return Theme.bg(); }
    static int surface() { return Theme.surface(); }
    static int input() { return Theme.input(); }
    static int raised() { return Theme.raised(); }
    static int hover() { return Theme.hover(); }
    static int hair() { return Theme.hair(); }
    static int line() { return Theme.line(); }
    static int text() { return Theme.text(); }
    static int muted() { return Theme.muted(); }
    static int dim() { return Theme.dim(); }
    static int accent() { return Theme.accent(); }
    static int pos() { return Theme.pos(); }
    static int obs() { return GOLD; }
    static int complete() { return GOLD; }
    static int scrim() { return Draw.alpha(Theme.scrim(), .30f); }
}
