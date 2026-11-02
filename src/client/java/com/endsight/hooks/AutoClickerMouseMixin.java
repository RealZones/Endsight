package com.endsight.hooks;

import com.endsight.qol.AutoClicker;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives attack repeats a frame clock without depending on HUD visibility. */
@Mixin(MouseHandler.class)
abstract class AutoClickerMouseMixin {
    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void endsight$autoClickerFrame(CallbackInfo ci) {
        AutoClicker.frame();
    }
}
