package com.endsight.hooks;
import com.endsight.dungeon.TerminalSolver;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Passive acknowledgment keeps naturally glowing items from looking unselected forever. */
@Mixin(ClientPacketListener.class)
abstract class TerminalSlotUpdateMixin {
    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void endsight$terminalUpdate(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        TerminalSolver.serverSlotUpdated(packet.getContainerId(), packet.getSlot());
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void endsight$terminalContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        TerminalSolver.serverContentUpdated(packet.containerId());
    }
}
