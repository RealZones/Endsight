package com.endsight.hooks;

import com.endsight.visual.ExplosionParticles;
import com.endsight.slayers.SlayerSpawnMarker;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
abstract class ExplosionParticlesMixin {
    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void endsight$hideExplosion(ParticleOptions particle, double x, double y, double z,
                                        double vx, double vy, double vz,
                                        CallbackInfoReturnable<Particle> cir) {
        boolean hideSpawn = SlayerSpawnMarker.particle(particle, x, y, z);
        if (hideSpawn || ExplosionParticles.hide(particle)) cir.setReturnValue(null);
    }
}
