package com.endsight.hooks;

import com.endsight.visual.InventoryGlass;
import com.endsight.ui.Theme;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(InventoryScreen.class)
abstract class InventoryGlassMixin {
    @Redirect(method = "extractBackground", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"))
    private void endsight$glass(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier texture,
                                int x, int y, float u, float v, int w, int h, int tw, int th) {
        if (InventoryGlass.applies(this)) InventoryGlass.background((InventoryScreen) (Object) this, g);
        else g.blit(pipeline, texture, x, y, u, v, w, h, tw, th);
    }

    @ModifyArg(method = "extractLabels", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"), index = 4)
    private int endsight$label(int original) { return InventoryGlass.applies(this) ? Theme.text() : original; }
}
