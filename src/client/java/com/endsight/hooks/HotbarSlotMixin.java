package com.endsight.hooks;

import com.endsight.visual.RarityOutline;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The same moment on the HUD: each hotbar item, just before it is drawn at x, y. */
@Mixin(Gui.class)
abstract class HotbarSlotMixin {

    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void endsight$behindHotbar(GuiGraphicsExtractor g, int x, int y, DeltaTracker delta, Player player,
                                       ItemStack stack, int seed, CallbackInfo ci) {
        RarityOutline.behindHotbar(g, x, y, stack);
    }
}
