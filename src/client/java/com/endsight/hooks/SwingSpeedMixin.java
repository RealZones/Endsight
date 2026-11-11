package com.endsight.hooks;

import com.endsight.visual.Animations;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Animations' swing speed: how many ticks your own arm takes to swing. Only you; everyone
 * else's arm swings at its own pace. The swing packet is sent on every click whatever this
 * says, so it changes how a swing looks and nothing else.
 */
@Mixin(LivingEntity.class)
abstract class SwingSpeedMixin {
    @ModifyReturnValue(method = "getCurrentSwingDuration", at = @At("RETURN"))
    private int endsight$speed(int ticks) {
        return (Object) this == Minecraft.getInstance().player ? Animations.duration(ticks) : ticks;
    }
}
