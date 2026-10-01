package com.endsight.hooks;

import com.endsight.slayers.Summons;
import com.endsight.visual.NameGradient;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A soul's name tag left undrawn. Cleared where every entity's tag is decided, not in a
 * renderer's own "show name" check: the armour stands that carry most mob tags answer
 * that check themselves, so hooking the living renderer's would miss them.
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At("TAIL"))
    private void endsight$hideSoulTag(Entity entity, EntityRenderState state, float partial, CallbackInfo ci) {
        if (Summons.hideTag(entity)) state.nameTag = null;
        else if (entity instanceof net.minecraft.world.entity.player.Player player)
            state.nameTag = NameGradient.nametag(state.nameTag, player);
    }
}
