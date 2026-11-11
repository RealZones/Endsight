package com.endsight.hooks;

import com.endsight.visual.Perspective;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the mouse's turn to the camera while Free Look is on.
 *
 * On the turn itself rather than on MouseHandler's call to it: only one redirect can hold a
 * call, and a second one from any other mod stops the game at startup.
 */
@Mixin(Entity.class)
abstract class PerspectiveTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void endsight$camera(double yRot, double xRot, CallbackInfo ci) {
        if (!Perspective.active() || (Object) this != Minecraft.getInstance().player) return;
        Perspective.turn(yRot, xRot);
        ci.cancel();
    }
}
