package com.endsight.hooks;

import com.endsight.visual.InventoryGlass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ContainerScreen.class)
abstract class ChestGlassMixin {
    @Redirect(method = "extractBackground", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"))
    private void endsight$glass(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier texture,
                                int x, int y, float u, float v, int w, int h, int tw, int th) {
        if (!InventoryGlass.applies(this)) g.blit(pipeline, texture, x, y, u, v, w, h, tw, th);
        // A chest background is two texture slices; draw the complete frame only once.
        else if (v == 0) InventoryGlass.background((ContainerScreen) (Object) this, g);
    }
}
