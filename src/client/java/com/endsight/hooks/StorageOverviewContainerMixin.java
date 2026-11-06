package com.endsight.hooks;

import com.endsight.storage.LiveStorageView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Removes the original chest underneath the replacement view, including at zero opacity. */
@Mixin(AbstractContainerScreen.class)
abstract class StorageOverviewContainerMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void endsight$overview(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float delta, CallbackInfo ci) {
        var screen = (AbstractContainerScreen<?>) (Object) this;
        if (LiveStorageView.active(screen)) {
            LiveStorageView.render(screen, graphics, mouseX, mouseY, delta);
            ci.cancel();
            return;
        }
        LiveStorageView.restore(screen);
    }

    @Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true)
    private void endsight$liveLabels(GuiGraphicsExtractor g, int mx, int my, CallbackInfo ci) {
        if (LiveStorageView.active((AbstractContainerScreen<?>) (Object) this)) ci.cancel();
    }

    @Inject(method = {"mouseClicked", "mouseReleased", "mouseDragged", "keyPressed"}, at = @At("HEAD"))
    private void endsight$liveInput(CallbackInfoReturnable<Boolean> cir) {
        LiveStorageView.prepare((AbstractContainerScreen<?>) (Object) this);
    }

    @Inject(method = "isHovering(Lnet/minecraft/world/inventory/Slot;DD)Z", at = @At("HEAD"), cancellable = true)
    private void endsight$liveHover(Slot slot, double x, double y, CallbackInfoReturnable<Boolean> cir) {
        if (LiveStorageView.owns((AbstractContainerScreen<?>) (Object) this))
            cir.setReturnValue(LiveStorageView.slotHovered(slot, x, y));
    }
}
