package com.endsight.hooks;

import com.endsight.visual.NameGradient;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(targets = {"net.minecraft.client.gui.components.ChatComponent$DrawingFocusedGraphicsAccess",
        "net.minecraft.client.gui.components.ChatComponent$DrawingBackgroundGraphicsAccess"})
abstract class ChatNameGradientMixin {
    @ModifyVariable(method = "handleMessage", at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence endsight$name(FormattedCharSequence original) {
        return NameGradient.animate(original);
    }
}
