package com.endsight.hooks;

import com.endsight.hud.Alerts;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server timer updates restore expiry reminders without scanning tab every frame. */
@Mixin(PlayerTabOverlay.class)
abstract class GoopBaitTabMixin {
    @Inject(method={"setHeader","setFooter"},at=@At("TAIL"))
    private void endsight$goopBait(Component text,CallbackInfo ci) {
        if(text!=null) Alerts.goopBaitTab(text.getString());
    }
}
