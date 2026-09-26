package com.endsight.hooks;

import com.endsight.slayers.VoidgloomHelper;
import net.minecraft.client.renderer.entity.GuardianRenderer;
import net.minecraft.client.renderer.entity.state.GuardianRenderState;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuardianRenderer.class)
abstract class GuardianRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/Guardian;Lnet/minecraft/client/renderer/entity/state/GuardianRenderState;F)V",
            at = @At("TAIL"))
    private void endsight$radiationBeam(Guardian guardian, GuardianRenderState state, float partial, CallbackInfo ci) {
        // The original textured beam would depth-occlude the replacement centerline.
        // Clear only its render endpoint, not the entity's target or any other guardian.
        if (VoidgloomHelper.replacesBeam(guardian, partial)) state.attackTargetPosition = null;
    }
}
