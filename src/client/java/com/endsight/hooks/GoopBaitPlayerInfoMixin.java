package com.endsight.hooks;

import com.endsight.hud.Alerts;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DragSim can also put active-buff rows in synthetic player-list entries. */
@Mixin(PlayerInfo.class)
abstract class GoopBaitPlayerInfoMixin {
    @Inject(method="setTabListDisplayName",at=@At("TAIL"))
    private void endsight$goopBait(Component text,CallbackInfo ci) {
        if(text!=null) Alerts.goopBaitTab(text.getString());
    }
}
