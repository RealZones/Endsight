package com.endsight.hooks;

import com.endsight.profile.ProfileCommands;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.brigadier.tree.RootCommandNode;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPacketListener.class)
public abstract class ProfileCommandTreeMixin {
    @ModifyExpressionValue(method = "handleCommands", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/network/protocol/game/ClientboundCommandsPacket;getRoot(Lnet/minecraft/commands/CommandBuildContext;Lnet/minecraft/network/protocol/game/ClientboundCommandsPacket$NodeBuilder;)Lcom/mojang/brigadier/tree/RootCommandNode;"))
    private RootCommandNode<ClientSuggestionProvider> endsight$profileCommands(RootCommandNode<ClientSuggestionProvider> root) {
        var client = ClientCommands.getActiveDispatcher();
        if (client == null || client.getRoot().getChild("pv") == null) return root;
        // Fabric appends client completions after this packet is read. Keep the
        // two aliases we own out of the server tree so integer/redirect nodes
        // cannot capture their usernames. Every other server branch is retained.
        return ProfileCommands.withoutServerAliases(root);
    }
}
