package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
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

    private static long guardUntil;

    public static Module module() {
        return new Module("qol.eyeguard", "Protect Placed Eyes",
                "Ignores right-clicks briefly after you place an eye.", "Quality of Life",
                () -> enabled, v -> {
                    enabled = v;
                    if (!v) guardUntil = 0;          // never leave a guard armed behind you
                },
                List.of(
                        new Setting.Slider("Guard time",
                                "How long clicks are ignored after placing.",
                                0.2, 3, 0.1, () -> guardSeconds, v -> guardSeconds = v, "s")));
    }

    public static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                isGuarding() ? InteractionResult.FAIL : InteractionResult.PASS);
    }

    /** Called when the server confirms an eye placed by YOU, not by anyone else. */
    public static void armed() {
        if (enabled) guardUntil = System.currentTimeMillis() + (long) (guardSeconds * 1000);
    }

    private static boolean isGuarding() {
        return enabled && System.currentTimeMillis() < guardUntil;
    }
}
