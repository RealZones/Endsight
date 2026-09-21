package com.endsight.hooks;

import com.endsight.visual.Fullbright;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fullbright, done where the lightmap is computed. The lightmap shader starts every
 * texel from max(ambient, nightVisionColor * nightVisionFactor) and adds sky and
 * block light on top, so a full-strength white night vision makes every texel white
 * and the world draws at its daylight colours. Gamma would not do it: the option
 * clamps at 1, and pushed past that the shader's notGamma() divides by zero in a
 * pitch-black cell.
 *
 * RETURN rather than TAIL because extract() leaves early when nothing changed this
 * tick; the state is reused between frames, so touching it there is harmless.
 */
@Mixin(LightmapRenderStateExtractor.class)
abstract class LightmapMixin {

    private static final Vector3fc WHITE = new Vector3f(1f, 1f, 1f);

    @Inject(method = "extract", at = @At("RETURN"))
    private void endsight$fullbright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        if (!Fullbright.on()) return;
        state.nightVisionEffectIntensity = 1f;
        state.nightVisionColor = WHITE;
        // Both are subtracted after that floor, so the Warden's darkness pulses and a
        // boss bar's world darkening would still dim it.
        state.darknessEffectScale = 0f;
        state.bossOverlayWorldDarkening = 0f;
    }
}
