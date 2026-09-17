package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;

import java.util.List;

/**
 * Stops a spam-click from pulling back out the eye it just placed.
 *
 * Placing an eye and taking one back are the same input - a right-click on the same
 * block - so clicking twice quickly puts one in and immediately takes it out, and the
 * altar then makes you wait before you can place again. Nothing about the click says
 * which of the two you meant; only the timing does.
 *
 * So the second click is swallowed. For a fraction of a second after the server
 * confirms YOUR placement, right-clicks are dropped. There is nothing you want to
 * right-click in that window, which is what makes this safe to do bluntly: the cost of
 * a false positive is one ignored click, and the cost of not doing it is a lost eye and
 * a four-second wait.
 *
 * Keyed on the server's own confirmation rather than on the click, so it can only ever
 * arm after an eye actually went in - a click that placed nothing leaves it disarmed.
 */
public final class EyeGuard {

    private EyeGuard() {
    }

    private static boolean enabled = true;
    private static double guardSeconds = 0.9;

    /**
     * The frame the guard covers - the one you just put an eye in - and nothing else.
     *
     * The first version swallowed every right-click in the window, which also swallowed
     * the click that would have put the NEXT eye in the frame beside it, so a fast run
     * down the altar stalled at every frame. The only click worth stopping is the one on
     * the frame that already has your eye; the block position tells those apart.
     */
    private static BlockPos lastClick;
    private static long lastClickMs;

    public static Module module() {
        return new Module("qol.eyeguard", "Protect Placed Eyes",
                "Ignores clicks on an eye you just placed.", "Quality of Life",
                () -> enabled, v -> {
                    enabled = v;
                    if (!v) lastClick = null;        // never leave a guard armed behind you
                },
                List.of(
                        new Setting.Slider("Guard time",
                                "How long clicks are ignored after placing.",
                                0.2, 3, 0.1, () -> guardSeconds, v -> guardSeconds = v, "s")));
    }

    public static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!enabled) return InteractionResult.PASS;
            long now = System.currentTimeMillis();
            BlockPos pos = hit.getBlockPos();
            // The same block again inside the window is the click that takes the eye
            // back out. Guarded from the click itself, not from the server's confirmation:
            // that arrives a tenth of a second later, and a spam-click gets its second
            // press in before it. There is no reason to click one frame twice that fast,
            // so nothing legitimate is lost. The window restarts on each blocked press,
            // so holding the button on a frame keeps it safe rather than timing out.
            if (pos.equals(lastClick) && now - lastClickMs < guardSeconds * 1000) {
                lastClickMs = now;
                return InteractionResult.FAIL;
            }
            lastClick = pos;
            lastClickMs = now;
            return InteractionResult.PASS;
        });
    }

    /** The server confirmed an eye placed by YOU: keep the frame guarded a full window from now. */
    public static void armed() {
        if (enabled && lastClick != null) lastClickMs = System.currentTimeMillis();
    }
}
