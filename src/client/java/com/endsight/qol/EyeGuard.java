package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import com.endsight.zealots.Zealots;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

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
 * Armed only by a click made holding an eye, on that one block, and held open by the
 * server's confirmation that the eye went in (armed()). Any other right-click - a
 * Hyperion, a bow, a click on the floor - is never touched.
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
    private static final Guard GUARD = new Guard();

    public static Module module() {
        return new Module("qol.eyeguard", "Protect Placed Eyes",
                "Ignores clicks on an eye you just placed.", "Quality of Life",
                () -> enabled, v -> {
                    enabled = v;
                    if (!v) GUARD.clear();           // never leave a guard armed behind you
                },
                List.of(
                        new Setting.Slider("Guard time",
                                "How long clicks are ignored after placing.",
                                0.2, 3, 0.1, () -> guardSeconds, v -> guardSeconds = v, "s")));
    }

    public static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!enabled) return InteractionResult.PASS;
            boolean eye = hand == InteractionHand.MAIN_HAND && eye(player.getItemInHand(hand));
            return GUARD.click(hit.getBlockPos(), eye, hand == InteractionHand.MAIN_HAND, System.currentTimeMillis(),
                    (long) (guardSeconds * 1000)) ? InteractionResult.FAIL : InteractionResult.PASS;
        });
    }

    /** A Summoning Eye, or a Golden Eye for a Golden dragon: the two things an altar frame takes. */
    static boolean eye(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String name = Zealots.strip(stack.getHoverName().getString()).trim();
        return name.equalsIgnoreCase("Summoning Eye") || name.equalsIgnoreCase("Golden Eye");
    }

    /**
     * Which clicks to drop, kept apart from the game so the checks can run it.
     *
     * Only a click made holding an eye arms it, on the block that click was on. It used to
     * arm on every right-click on any block, and a second right-click on the same block
     * inside the window was dropped whatever it was: looking at the floor, a Hyperion or a
     * bow fired once and then not again for as long as you kept clicking, since each dropped
     * click restarted the window. A player found it by taking the mod out. Arming on the eye
     * keeps what this is for - the click that would pull your eye back out of the frame,
     * whatever is in your hand by then (the eye you placed may have been your last) - and
     * leaves every other right-click alone.
     */
    static final class Guard {
        private BlockPos block;
        private long at;

        /**
         * Whether this right-click on {@code pos} is dropped. Guarded from the click itself,
         * not from the server's confirmation: that arrives a tenth of a second later, and a
         * spam-click gets its second press in before it. The window restarts on each dropped
         * press, so holding the button on a frame keeps it safe rather than timing out. The
         * off hand is asked about the same click after the main hand; it never re-arms.
         */
        boolean click(BlockPos pos, boolean holdingEye, boolean mainHand, long now, long windowMs) {
            if (pos.equals(block) && now - at < windowMs) {
                at = now;
                return true;
            }
            if (!mainHand) return false;
            block = holdingEye ? pos : null;
            at = now;
            return false;
        }

        void armed(long now) {
            if (block != null) at = now;
        }

        void clear() {
            block = null;
        }
    }

    /** The server confirmed an eye placed by YOU: keep the frame guarded a full window from now. */
    public static void armed() {
        if (enabled) GUARD.armed(System.currentTimeMillis());
    }
}
