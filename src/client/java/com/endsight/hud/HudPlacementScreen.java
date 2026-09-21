package com.endsight.hud;

import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drag every HUD readout to where you want it, all on one screen.
 *
 * One screen rather than one per module, because position is a relationship: you cannot
 * tell whether the slayer tracker is in the right place without seeing where the dragon
 * timer already is. Opening this from any module's Move button shows all of them.
 *
 * Every element is drawn with SAMPLE data, so a readout that only appears during a fight
 * - the Protector's tier, a boss timer - can still be placed while standing in the hub.
 * That also makes this the answer to "is that module even working": if it draws here, it
 * exists and is positioned; if it never appears in game, the module is off or its trigger
 * has not happened.
 */
public final class HudPlacementScreen extends Screen {

    private final Screen parent;

    private String dragging;
    private int grabX, grabY;
    private final Map<String, int[]> bounds = new HashMap<>();

    public HudPlacementScreen(Screen parent) {
        super(Component.literal("Move HUD"));
        this.parent = parent;
    }

    /**
     * Open it over whatever is showing, and return there on escape.
     *
     * Capturing the current screen as the parent is what makes this feel like a step
     * rather than a detour: you press Move on a settings page, drag, press escape, and
     * you are back on the page you left.
     */
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new HudPlacementScreen(mc.screen));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, Theme.scrim());

        bounds.clear();
        for (String id : HudLayout.ids()) {
            HudLayout.Renderer r = HudLayout.renderer(id);
            if (r == null) continue;

            // Measured, not drawn: an element's size depends on the text it happens to be
            // showing and its position depends on the size, so the size has to come first.
            // Scaled here the way it is drawn, so the box you grab is the box you see.
            int[] size = HudLayout.size(id, font);
            int w = size[0], h = size[1];

            int x = HudLayout.x(id, w, width);
            int y = HudLayout.y(id, h, height);
            bounds.put(id, new int[]{x, y, w, h});

            boolean hot = id.equals(dragging)
                    || (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h);

            Draw.roundedRect(g, x - 3, y - 3, w + 6, h + 6, 4,
                    Draw.alpha(Theme.accent(), hot ? 0.22f : 0.10f));
            HudLayout.draw(id, g, font, true);
            if (hot) {
                Draw.roundedOutline(g, x - 3, y - 3, w + 6, h + 6, 4,
                        Theme.accent(), 0x00000000);
                float s = HudLayout.scale(id);
                if (Math.abs(s - 1f) > 0.01f) {
                    Draw.text(g, font, Math.round(s * 100) + "%", x + w + 6, y, Theme.accent());
                }
            }
        }

        if (dragging != null) {
            int guide = Draw.alpha(Theme.accent(), 0.55f);
            if (snapX != Integer.MIN_VALUE) g.fill(snapX, 0, snapX + 1, height, guide);
            if (snapY != Integer.MIN_VALUE) g.fill(0, snapY, width, snapY + 1, guide);
        }
        String hint = "Drag to place  ·  snaps to edges and centre, shift to skip  ·  Scroll to resize  ·  Right-click to reset  ·  Esc when done";
        Draw.textCentered(g, font, hint, width / 2, height - 26, Theme.muted());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();

        for (Map.Entry<String, int[]> e : bounds.entrySet()) {
            int[] b = e.getValue();
            if (mx < b[0] || mx >= b[0] + b[2] || my < b[1] || my >= b[1] + b[3]) continue;

            if (event.button() == 1) {
                HudLayout.reset(e.getKey());
            } else {
                dragging = e.getKey();
                grabX = mx - b[0];
                grabY = my - b[1];
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging == null) return super.mouseDragged(event, dx, dy);
        int[] b = bounds.get(dragging);
        if (b == null) return true;

        int px = (int) event.x() - grabX, py = (int) event.y() - grabY;
        int w = b[2], h = b[3];
        // Snap: the screen's centre lines, and every other readout's edges and centre -
        // plus sitting flush beside or under one - so a column lines up instead of
        // landing a pixel off. Hold shift to place freely.
        snapX = snapY = Integer.MIN_VALUE;
        if (!event.hasShiftDown()) {
            List<int[]> xs = new ArrayList<>(), ys = new ArrayList<>();   // {line, offset of my edge that meets it}
            xs.add(new int[]{width / 2, w / 2});
            ys.add(new int[]{height / 2, h / 2});
            for (Map.Entry<String, int[]> e : bounds.entrySet()) {
                if (e.getKey().equals(dragging)) continue;
                int[] o = e.getValue();
                for (int line : new int[]{o[0], o[0] + o[2], o[0] + o[2] / 2}) for (int mine : new int[]{0, w, w / 2}) xs.add(new int[]{line, mine});
                for (int line : new int[]{o[1], o[1] + o[3], o[1] + o[3] / 2}) for (int mine : new int[]{0, h, h / 2}) ys.add(new int[]{line, mine});
                xs.add(new int[]{o[0] + o[2] + GAP, 0});   // flush to its right
                xs.add(new int[]{o[0] - GAP, w});          // flush to its left
                ys.add(new int[]{o[1] + o[3] + GAP, 0});   // flush under it
                ys.add(new int[]{o[1] - GAP, h});          // flush above it
            }
            int bestX = SNAP + 1, bestY = SNAP + 1;
            for (int[] c : xs) {
                int d = Math.abs(px + c[1] - c[0]);
                if (d < bestX) { bestX = d; px = c[0] - c[1]; snapX = c[0]; }
            }
            for (int[] c : ys) {
                int d = Math.abs(py + c[1] - c[0]);
                if (d < bestY) { bestY = d; py = c[0] - c[1]; snapY = c[0]; }
            }
        }
        HudLayout.setPixels(dragging, px, py, w, h, width, height);
        return true;
    }

    /** Snap distance in GUI pixels, and the gap left between two readouts placed flush. */
    private static final int SNAP = 5, GAP = 4;
    /** The guide lines to draw while a drag is snapped, or MIN_VALUE for none. */
    private int snapX = Integer.MIN_VALUE, snapY = Integer.MIN_VALUE;

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = null;
        snapX = snapY = Integer.MIN_VALUE;
        return super.mouseReleased(event);
    }

    /** The wheel over an element: a tenth bigger or smaller a notch, half to double. */
    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        for (Map.Entry<String, int[]> e : bounds.entrySet()) {
            int[] b = e.getValue();
            if (mx < b[0] || mx >= b[0] + b[2] || my < b[1] || my >= b[1] + b[3]) continue;
            HudLayout.setScale(e.getKey(), HudLayout.scale(e.getKey()) + (dy > 0 ? 0.1f : -0.1f));
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {                 // escape goes back, not out
            minecraft.setScreen(parent);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
