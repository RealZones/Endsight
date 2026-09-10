package com.endsight.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The palette swatches parked at the foot of a sidebar.
 *
 * Owns its own layout, drawing, hit testing and animation so any screen can drop it
 * in with three calls. Every screen that has a sidebar wants it in the same place -
 * duplicating it is how two screens end up disagreeing about where the theme picker
 * lives, which is exactly the kind of small inconsistency that reads as unfinished.
 */
public final class ThemeRow {

    private static final int SIZE = 18;
    private static final int GAP = 7;
    private static final int PER_ROW = 5;

    private final List<Sw> swatches = new ArrayList<>();
    private final Map<String, Anim> anims = new HashMap<>();

    private record Sw(Palette palette, int x, int y) {
    }

    /** Height this block needs, so a caller can leave room above it. */
    public static int height() {
        int rows = (Palette.ALL.size() + PER_ROW - 1) / PER_ROW;
        return rows * (SIZE + GAP) - GAP + 44;
    }

    public void layout(int panelX, int panelY, int panelH) {
        swatches.clear();
        int rows = (Theme.palettes().size() + PER_ROW - 1) / PER_ROW;
        int top = panelY + panelH - (rows * (SIZE + GAP) - GAP) - 26;
        for (int i = 0; i < Theme.palettes().size(); i++) {
            swatches.add(new Sw(Theme.palettes().get(i),
                    panelX + 16 + (i % PER_ROW) * (SIZE + GAP),
                    top + (i / PER_ROW) * (SIZE + GAP)));
        }
    }

    /**
     * One circle per palette, showing the colour that carries the most meaning in it.
     *
     * The name only appears for whatever the cursor is over, so this stays a row of
     * colours rather than a list of words - the colour IS the label, and reading seven
     * names to find the one you want defeats the point of swatches.
     */
    public void draw(GuiGraphicsExtractor g, Font font, int panelX, int mouseX, int mouseY) {
        if (swatches.isEmpty()) return;
        int top = swatches.getFirst().y();

        Draw.rect(g, panelX, top - 26, Theme.SIDEBAR_W, 1, Theme.hair());
        Draw.text(g, font, "THEME", panelX + 16, top - 18, Theme.dim());

        String hovered = null;
        for (Sw sw : swatches) {
            boolean selected = sw.palette == Theme.palette();
            boolean over = contains(sw.x, sw.y, SIZE, SIZE, mouseX, mouseY);
            if (over) hovered = sw.palette.name();

            float a = anims.computeIfAbsent(sw.palette.name(), k -> new Anim(0f))
                    .to(selected ? 1f : (over ? 0.6f : 0f), Theme.EASE_FAST);

            int r = SIZE / 2;
            if (a > 0.01f) {
                Draw.roundedRect(g, sw.x - 2, sw.y - 2, SIZE + 4, SIZE + 4, r + 2,
                        Draw.alpha(Theme.text(), 0.10f + 0.55f * a));
            }
            Draw.roundedRect(g, sw.x, sw.y, SIZE, SIZE, r, Theme.bg());
            Draw.roundedRect(g, sw.x + 2, sw.y + 2, SIZE - 4, SIZE - 4, r - 2,
                    sw.palette.accentColor());
        }

        String label = hovered != null ? hovered : Theme.palette().name();
        Draw.text(g, font, Draw.fit(font, label, Theme.SIDEBAR_W - 32),
                panelX + 16, swatches.getLast().y() + SIZE + 7,
                hovered != null ? Theme.text() : Theme.dim());
    }

    public boolean click(int mx, int my) {
        for (Sw sw : swatches) {
            if (contains(sw.x, sw.y, SIZE, SIZE, mx, my)) {
                Theme.set(sw.palette);
                return true;
            }
        }
        return false;
    }

    private static boolean contains(int x, int y, int w, int h, int px, int py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
