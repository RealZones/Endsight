package com.endsight.hooks;

import com.endsight.storage.StorageOverview;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes the original chest underneath the replacement view, including at zero opacity. */
@Mixin(AbstractContainerScreen.class)
abstract class StorageOverviewContainerMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void endsight$overview(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float delta, CallbackInfo ci) {
        // 26.1.2 draws labels, slots, carried/snapback items and tooltips from this
        // method. Fabric's afterExtract wraps the OUTER Screen extraction call in
        // GameRenderer, so cancelling here still lets the overview draw afterward.
        // Leave the outer wrapper and the menu/input state entirely intact.
        if (StorageOverview.active((AbstractContainerScreen<?>) (Object) this)) ci.cancel();
    }
}
