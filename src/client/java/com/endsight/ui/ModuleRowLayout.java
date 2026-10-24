package com.endsight.ui;

/**
 * Where one module row puts its title, key chip, switch and expand arrow.
 *
 * Drawing and clicking both read these rectangles, and EndsightScreen rebuilds them in
 * layout() every frame, so a click can never test numbers that differ from what was
 * drawn. Narrow windows put the controls below the label instead of letting a key
 * chip cover the module's name.
 */
record ModuleRowLayout(Rect row, Rect label, Rect key, Rect toggle, Rect pin, Rect expand, boolean stacked) {
    private static final int PAD = 8, GAP = 6, LABEL_INSET = 8;
    private static final int TOGGLE_W = 30, TOGGLE_H = 20, EXPAND_W = 18, EXPAND_H = 20;

    enum Target { NONE, KEY, TOGGLE, PIN, EXPAND, ROW }

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
        return of(x, y, w, implemented, hasSettings, implemented);
    }

    static ModuleRowLayout of(int x, int y, int w, boolean implemented, boolean hasSettings, boolean assignedKey) {
        // A shortcut also lives inside the editor. Omit its duplicate chip before it can push the pin outside a narrow row.
        assignedKey &= w >= 150;
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
            right = toggle.x() - GAP;
            if (assignedKey) {
                key = new Rect(right - Keybinds.CHIP_W, controlY + (20 - Keybinds.CHIP_H) / 2,
                        Keybinds.CHIP_W, Keybinds.CHIP_H);
                right = key.x() - GAP;
            }
        }
        Rect pin = new Rect(right - 18, controlY, 18, 20);
        right = pin.x() - GAP;
        int labelX = x + LABEL_INSET;
        // Title on top, description 13px below it: two lines of the 9px font.
        Rect label = new Rect(labelX, y + 7, Math.max(0, stacked ? w - LABEL_INSET - 10 : right - labelX), 24);
        return new ModuleRowLayout(new Rect(x, y, w, height), label, key, toggle, pin, expand, stacked);
    }

    /** Which control a click lands on; the rest of the row opens the module's settings. */
    Target target(double px, double py) {
        if (!row.contains(px, py)) return Target.NONE;
        if (key.contains(px, py)) return Target.KEY;
        if (toggle.contains(px, py)) return Target.TOGGLE;
        if (pin.contains(px, py)) return Target.PIN;
        if (expand.contains(px, py)) return Target.EXPAND;
        return Target.ROW;
    }
}
