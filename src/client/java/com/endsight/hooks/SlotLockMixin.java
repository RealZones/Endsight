package com.endsight.hooks;

import com.endsight.qol.SlotLock;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Where a locked slot says no.
 *
 * Every way a window can move an item - pick up, shift-click, a number key, throwing it,
 * double-click collect - arrives at slotClicked, so one cancel at the head of it covers
 * them all, and cancelling before the method runs means no packet leaves the client and
 * the server never hears the click.
 *
 * Linking uses the lock key's press and release while the pointer moves between slots.
 */
@Mixin(AbstractContainerScreen.class)
abstract class SlotLockMixin {

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void endsight$locked(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        if (SlotLock.intercept((AbstractContainerScreen<?>) (Object) this, slot, button, input)) ci.cancel();
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void endsight$keyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> ci) {
        if (SlotLock.keyPressed((AbstractContainerScreen<?>) (Object) this, event.key())) {
            ci.setReturnValue(true);
        }
    }

    @Inject(method = "extractSlots", at = @At("HEAD"))
    private void endsight$links(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
        SlotLock.links(g, (AbstractContainerScreen<?>) (Object) this);
    }
}
