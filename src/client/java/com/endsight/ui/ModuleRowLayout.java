package com.endsight.ui;

/**
 * Where one module row puts its title, key chip, switch and expand arrow.
 *
 * Drawing and clicking both read these rectangles, and EndsightScreen rebuilds them in
 * layout() every frame, so a click can never test numbers that differ from what was
 * drawn. Narrow windows put the controls below the label instead of letting a key
 * chip cover the module's name.
 */
record ModuleRowLayout(Rect row, Rect label, Rect key, Rect toggle, Rect expand, boolean stacked) {
    private static final int PAD = 8, GAP = 8, LABEL_INSET = 34;
    private static final int TOGGLE_W = 30, TOGGLE_H = 20, EXPAND_W = 32, EXPAND_H = 20;

    enum Target { NONE, KEY, TOGGLE, EXPAND, ROW }

    record Rect(int x, int y, int w, int h) {
        /** A control the row does not have: zero-sized, so it never contains a point. */
        static final Rect NONE = new Rect(0, 0, 0, 0);

        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    static int height(int width) { return width < 260 ? 58 : 42; }

    /**
     * Controls are placed right to left so the title gets whatever is left over. A
     * placeholder has no key or switch, and a module without settings has no arrow.
     */
    static ModuleRowLayout of(int x, int y, int w, boolean implemented, boolean hasSettings) {
        boolean stacked = w < 260;
        int height = height(w);
        int controlY = y + (stacked ? 34 : (height - 20) / 2);
        int right = x + w - PAD;
        Rect expand = Rect.NONE, toggle = Rect.NONE, key = Rect.NONE;
        if (hasSettings) {
            expand = new Rect(right - EXPAND_W, controlY, EXPAND_W, EXPAND_H);
            right = expand.x() - GAP;
        }
        if (implemented) {
            toggle = new Rect(right - TOGGLE_W, controlY, TOGGLE_W, TOGGLE_H);
            key = new Rect(toggle.x() - GAP - Keybinds.CHIP_W, controlY + (20 - Keybinds.CHIP_H) / 2,
                    Keybinds.CHIP_W, Keybinds.CHIP_H);
            right = key.x() - GAP;
        }
        int labelX = x + LABEL_INSET;
        // Title on top, description 13px below it: two lines of the 9px font.
        Rect label = new Rect(labelX, y + 7, Math.max(0, stacked ? w - LABEL_INSET - 10 : right - labelX), 24);
        return new ModuleRowLayout(new Rect(x, y, w, height), label, key, toggle, expand, stacked);
    }

    /** Which control a click lands on; the rest of the row opens the module's settings. */
    Target target(double px, double py) {
        if (!row.contains(px, py)) return Target.NONE;
        if (key.contains(px, py)) return Target.KEY;
        if (toggle.contains(px, py)) return Target.TOGGLE;
        if (expand.contains(px, py)) return Target.EXPAND;
        return Target.ROW;
    }
}
