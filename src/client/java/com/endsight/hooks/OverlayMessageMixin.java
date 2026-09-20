package com.endsight.hooks;

import com.endsight.storage.PowderTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The action bar as it is set. DragSim sends "+35.29 Mining (80.7%)" through the
 * action bar packet, which never reaches the chat message event - a 25-minute mining
 * session logged zero of them there - so the text is taken here, where the HUD stores it.
 */
@Mixin(Gui.class)
abstract class OverlayMessageMixin {
    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void endsight$overlay(Component message, boolean tinted, CallbackInfo ci) {
        PowderTracker.onActionBar(message.getString());
    }
}
