package com.endsight.ui;

/** Profiles use a quiet, fixed canvas so item colours carry the visual detail. */
final class ProfileStyle {
    private static final Palette P = new Palette("Profile",
            0x172027, 0x83CEC2, 0xEDF1F3, 0xA5ADB7, 0x7E8996,
            0x88CA91, 0xEB8B88, 0xE6C27F, false);
    private static final int BG=P.ground(), SURFACE=P.surface(), INPUT=P.input(),
            RAISED=P.raised(), HOVER=P.hover(), HAIR=P.hair(), LINE=P.line();
    static int bg() { return BG; }
    static int surface() { return SURFACE; }
    static int input() { return INPUT; }
    static int raised() { return RAISED; }
    static int hover() { return HOVER; }
    static int hair() { return HAIR; }
    static int line() { return LINE; }
    static int text() { return P.textColor(); }
    static int muted() { return P.mutedColor(); }
    static int dim() { return P.dimColor(); }
    static int accent() { return P.accentColor(); }
    static int pos() { return P.posColor(); }
    static int obs() { return P.obsColor(); }
    static int scrim() { return 0xAC000000; }
}
