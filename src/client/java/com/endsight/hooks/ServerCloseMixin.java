package com.endsight.hooks;

import com.endsight.ui.EndsightScreen;
import com.endsight.ui.SettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps our own pages up through a close for a menu that is already gone.
 *
 * Vanilla answers any close from the server by shutting whatever is on screen, menu or not.
 * A delayed close for a previous container must not dismiss an Endsight page opened since.
 * The inventory-menu check limits this protection to cases where no server menu remains.
 *
 * Injected at the close and not at the head: the head also runs on the network thread,
 * before the packet is handed over to the game.
 */
@Mixin(ClientPacketListener.class)
abstract class ServerCloseMixin {
    @Inject(method = "handleContainerClose", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;clientSideCloseContainer()V"))
    private void endsight$keepOurPage(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if ((mc.screen instanceof EndsightScreen || mc.screen instanceof SettingsScreen)
                && mc.player.containerMenu == mc.player.inventoryMenu) ci.cancel();
    }
}
