package com.endsight.hooks;

import com.endsight.visual.ShinyNodes;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ShinyNodeParticlesMixin {
    // At TAIL the packet has been marshalled onto the client thread, and its fixed
    // server coordinates are still available before per-particle random offsets.
    @Inject(method = "handleParticleEvent", at = @At("TAIL"))
    private void endsight$shinyNodeParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        ShinyNodes.particle(packet);
    }
}
