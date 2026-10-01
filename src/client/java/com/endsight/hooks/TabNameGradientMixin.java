package com.endsight.hooks;

import com.endsight.visual.NameGradient;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(PlayerTabOverlay.class)
abstract class TabNameGradientMixin {
    @ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"), index = 1)
    private Component endsight$name(Component original) { return NameGradient.animate(original); }
}
