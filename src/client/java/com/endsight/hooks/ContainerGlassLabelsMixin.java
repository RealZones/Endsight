package com.endsight.hooks;

import com.endsight.visual.InventoryGlass;
import com.endsight.ui.Theme;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(AbstractContainerScreen.class)
abstract class ContainerGlassLabelsMixin {
    @ModifyArg(method = "extractLabels", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"), index = 4)
    private int endsight$label(int original) { return InventoryGlass.applies(this) ? Theme.text() : original; }
}
