package com.endsight.hooks;

import com.endsight.slayers.VoidgloomHelper;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Guardian.class)
abstract class GuardianParticlesMixin {
    @Unique
    private boolean endsight$hideBubbles;

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void endsight$beamParticles(CallbackInfo ci) {
        // Check once per tick, not for every bubble along all twelve beams.
        endsight$hideBubbles = VoidgloomHelper.replacesBeam((Guardian) (Object) this, 1f);
    }

    @WrapWithCondition(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"),
            require = 2)
    private boolean endsight$keepParticle(Level level, ParticleOptions particle,
                                          double x, double y, double z, double vx, double vy, double vz) {
        // Suppress only the replaced beam's bubbles; unrelated particles stay visible.
        return particle.getType() != ParticleTypes.BUBBLE || !endsight$hideBubbles;
    }
}
