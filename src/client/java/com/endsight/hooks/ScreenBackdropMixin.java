package com.endsight.hooks;

import com.endsight.visual.InventoryGlass;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The backdrop behind a glass window - which is not ours, and was the thing still covering
 * the screen.
 *
 * With the panel's own fill at zero the window was already clear, but the view through it
 * was not: Screen.extractBackground blurs the world and lays a dark gradient over it, so a
 * glass inventory still read as a dim sheet rather than a hole in the screen. The container
 * screens draw their own panel and call up the chain for the backdrop, so cancelling here
 * takes the blur and the dim with it and leaves the frame and the slots.
 *
 * Only while Glass Inventory applies: every other screen - pause, options, anything with
 * text meant to be read against a quiet background - keeps the backdrop it was designed
 * with.
 */
@Mixin(Screen.class)
abstract class ScreenBackdropMixin {
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void endsight$clear(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (InventoryGlass.applies(this)) ci.cancel();
    }
}
