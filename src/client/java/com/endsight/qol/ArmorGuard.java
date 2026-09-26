package com.endsight.qol;

import com.endsight.ui.Module;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.equipment.Equippable;

import java.util.List;

/**
 * Right-clicking with a piece of armour in hand no longer swaps it onto you.
 *
 * Armour is salvaged by right-clicking the altar with it. A click that misses the altar
 * lands on air or the floor beside it, and the game's own right-click-to-wear puts the
 * piece on and hands you the one you were wearing - so the next click on the altar
 * salvages that one instead. People lost armour they meant to keep this way.
 *
 * A right-click on a block and the use of the item in hand go to the server as two
 * separate requests, and only the second one swaps armour. So the second is dropped while
 * armour is in hand: the click on the altar still goes through, a miss does nothing.
 * Sneaking lets it through, because armour abilities - Reaper's Enrage - are a sneak
 * right-click. Wearing armour from the inventory is untouched.
 */
public final class ArmorGuard {

    private ArmorGuard() {
    }

    private static boolean enabled = true;

    public static Module module() {
        return new Module("qol.armorguard", "Armor Swap Guard",
                "Stops right-click armor swaps unless you sneak.", "Quality of Life",
                () -> enabled, v -> enabled = v, List.of());
    }

    public static void init() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            // Client only: in singleplayer the built-in server raises this too.
            if (!enabled || !level.isClientSide() || player.isShiftKeyDown()) return InteractionResult.PASS;
            Equippable wear = player.getItemInHand(hand).get(DataComponents.EQUIPPABLE);
            if (wear == null || !wear.swappable() || !wear.slot().isArmor()) return InteractionResult.PASS;
            // FAIL, not PASS: FAIL is what stops the request being sent at all.
            return InteractionResult.FAIL;
        });
    }
}
