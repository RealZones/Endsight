package com.endsight.hud;

import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
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

        String hint = "Drag to place  ·  Scroll over one to resize  ·  Right-click to reset  ·  Esc when done";
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

        HudLayout.setPixels(dragging,
                (int) event.x() - grabX, (int) event.y() - grabY,
                b[2], b[3], width, height);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = null;
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
