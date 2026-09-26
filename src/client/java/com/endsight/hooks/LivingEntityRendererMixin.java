package com.endsight.hooks;

import com.endsight.visual.SoulFade;
import com.endsight.slayers.Summons;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Raised souls drawn the way the game draws an invisible teammate: body not "visible", so
 * it takes the see-through render type, at the helper's summon opacity instead of the
 * teammate's fixed 0x26 (15%). Everything else about the body is drawn as normal.
 */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {

    private static final String SUBMIT = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;"
            + "Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
    private void endsight$markSoul(LivingEntity entity, LivingEntityRenderState state, float partial, CallbackInfo ci) {
        ((SoulFade) state).endsight$setSoulAlpha(Summons.alpha(entity));
    }

    @ModifyExpressionValue(method = SUBMIT, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;isBodyVisible(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z"))
    private boolean endsight$seeThrough(boolean visible, @Local(argsOnly = true) LivingEntityRenderState state) {
        return visible && ((SoulFade) state).endsight$soulAlpha() < 0;
    }

    @ModifyExpressionValue(method = SUBMIT, at = @At(value = "CONSTANT", args = "intValue=654311423"))
    private int endsight$soulTint(int teammate, @Local(argsOnly = true) LivingEntityRenderState state) {
        float alpha = ((SoulFade) state).endsight$soulAlpha();
        return alpha < 0 ? teammate : Math.round(alpha * 255) << 24 | 0xFFFFFF;
    }
}
