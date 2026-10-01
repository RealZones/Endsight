package com.endsight.hooks;

import com.endsight.qol.SlotLock;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Q and Ctrl-Q bypass container screens entirely. */
@Mixin(LocalPlayer.class)
abstract class SlotLockPlayerMixin {
    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void endsight$protectDrop(boolean entireStack, CallbackInfoReturnable<Boolean> ci) {
        if (SlotLock.protectSelectedDrop()) ci.setReturnValue(false);
    }
}
