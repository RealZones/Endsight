package com.endsight.hooks;

import com.endsight.visual.RarityOutline;
import com.endsight.storage.ForgeRecipes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The moment before a window draws an item into a slot: the pose is already moved to
 * the window's corner, so the slot's own x and y are the place. See RarityOutline for
 * why the colour has to go in here and not after the screen is done.
 */
@Mixin(AbstractContainerScreen.class)
abstract class ContainerSlotMixin {

    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void endsight$behindSlot(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        RarityOutline.behindSlot(g, slot);
    }

    @Inject(method = "extractSlot", at = @At("TAIL"))
    private void endsight$overSlot(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        ForgeRecipes.overSlot((AbstractContainerScreen<?>) (Object) this, g, slot);
    }
}
