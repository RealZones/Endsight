package com.endsight.hud;

import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.ChatScreen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drag a HUD readout straight from chat: press on it, move, let go.
 *
 * The Move screen draws every readout at once with sample data, so with a dozen modules on
 * they pile on top of each other and the one you want is under three others. In chat only
 * what is on screen right now is there, where it really is, so you move the one you are
 * looking at. Snapping is the Move screen's, shift skips it the same way.
 *
 * A drag starts only once the mouse has moved a few pixels with the button down, and the
 * press itself is passed on. Several readouts have small controls that act on a plain click
 * in chat (the Session / All Time switch, the dragon view), and those must keep working.
 */
public final class ChatHudDrag {
    private ChatHudDrag() { }

    /** Pixels the mouse must travel with the button down before a press becomes a drag. */
    private static final int START = 3;
    private static String pressed, dragging;
    private static double pressX, pressY;
    private static int grabX, grabY, guideX = Integer.MIN_VALUE, guideY = Integer.MIN_VALUE;

    public static void init() {
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
            // The pinned loot-roll details are a ChatScreen too, but own every click.
            if (!(screen instanceof ChatScreen) || !HudLayout.interactiveScreen(screen)) return;
            reset();
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                pressed = click.button() == 0 && !mc.options.hideGui ? at(click.x(), click.y()) : null;
                if (pressed != null) {
                    int[] b = HudLayout.bounds(pressed);
                    pressX = click.x(); pressY = click.y();
                    grabX = (int) click.x() - b[0]; grabY = (int) click.y() - b[1];
                }
                return true;
            });
            ScreenMouseEvents.allowMouseDrag(screen).register((s, click, dx, dy) -> {
                if (pressed == null) return true;
                if (dragging == null && Math.hypot(click.x() - pressX, click.y() - pressY) < START) return true;
                int[] b = HudLayout.bounds(pressed);
                // The readout went away mid-drag (its fight ended, its timer ran out).
                if (b == null) { reset(); return true; }
                dragging = pressed;
                int px = (int) click.x() - grabX, py = (int) click.y() - grabY;
                int[] placed = click.hasShiftDown() ? new int[]{px, py, Integer.MIN_VALUE, Integer.MIN_VALUE}
                        : HudPlacementScreen.snap(dragging, px, py, b[2], b[3], others(dragging), s.width, s.height);
                guideX = placed[2]; guideY = placed[3];
                HudLayout.setPixels(dragging, placed[0], placed[1], b[2], b[3], s.width, s.height);
                return false;
            });
            ScreenMouseEvents.allowMouseRelease(screen).register((s, click) -> {
                boolean moved = dragging != null;
                reset();
                if (moved) com.endsight.Config.save(com.endsight.EndsightClient.registry());
                return !moved;
            });
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> {
                if (mc.options.hideGui) return;
                String hot = dragging != null ? dragging : at(mx, my);
                int[] b = hot == null ? null : HudLayout.bounds(hot);
                if (b == null) return;
                // A faint outline says "this can be moved"; full strength while it is moving.
                Draw.roundedOutline(g, b[0] - 2, b[1] - 2, b[2] + 4, b[3] + 4, 3,
                        Draw.alpha(Theme.accent(), dragging != null ? 1f : .45f), 0x00000000);
                int guide = Draw.alpha(Theme.accent(), 0.55f);
                if (dragging != null && guideX != Integer.MIN_VALUE) g.fill(guideX, 0, guideX + 1, s.height, guide);
                if (dragging != null && guideY != Integer.MIN_VALUE) g.fill(0, guideY, s.width, guideY + 1, guide);
            });
            ScreenEvents.remove(screen).register(s -> reset());
        });
    }

    private static void reset() {
        pressed = dragging = null;
        guideX = guideY = Integer.MIN_VALUE;
    }

    /** The readout under the cursor, the last drawn (the one on top) first. */
    private static String at(double x, double y) {
        List<String> ids = HudLayout.ids();
        for (int i = ids.size() - 1; i >= 0; i--) {
            int[] b = HudLayout.bounds(ids.get(i));
            if (b != null && x >= b[0] && x < b[0] + b[2] && y >= b[1] && y < b[1] + b[3]) return ids.get(i);
        }
        return null;
    }

    private static Map<String, int[]> others(String self) {
        Map<String, int[]> result = new LinkedHashMap<>();
        for (String id : HudLayout.ids()) {
            int[] b = HudLayout.bounds(id);
            if (b != null && !id.equals(self)) result.put(id, b);
        }
        return result;
    }
}
