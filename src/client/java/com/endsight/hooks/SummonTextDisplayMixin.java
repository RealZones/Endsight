package com.endsight.hooks;

import com.endsight.slayers.Summons;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A soul's TextDisplay is its visible label, not the entity nameTag field. */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
abstract class SummonTextDisplayMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Display$TextDisplay;"
            + "Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;F)V", at = @At("TAIL"))
    private void endsight$hideSoulText(Display.TextDisplay entity, TextDisplayEntityRenderState state,
                                        float partial, CallbackInfo ci) {
        if (Summons.hideTag(entity)) {
            state.textRenderState = null;
            state.cachedInfo = null;
        }
    }
}
