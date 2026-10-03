package com.endsight.visual;

import com.endsight.ui.Module;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

import java.util.List;

/** Hides vanilla explosion particles without suppressing unrelated effects. */
public final class ExplosionParticles {
    private ExplosionParticles() { }

    private static boolean enabled = true;

    public static Module module() {
        return new Module("visual.explosionParticles", "Explosion Particle Hider",
                "Hide explosion clouds from any source.", "Visual",
                () -> enabled, v -> enabled = v, List.of());
    }

    public static boolean hide(ParticleOptions particle) {
        return enabled && (particle.getType() == ParticleTypes.EXPLOSION
                || particle.getType() == ParticleTypes.EXPLOSION_EMITTER);
    }
}
