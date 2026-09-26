package com.endsight.hooks;

import com.endsight.visual.SoulFade;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Room on every living render state for the soul fade. */
@Mixin(LivingEntityRenderState.class)
abstract class LivingEntityRenderStateMixin implements SoulFade {

    @Unique
    private float endsight$soulAlpha = -1f;

    @Override
    public float endsight$soulAlpha() {
        return endsight$soulAlpha;
    }

    @Override
    public void endsight$setSoulAlpha(float alpha) {
        endsight$soulAlpha = alpha;
    }
}
