package com.endsight.hooks;

import com.endsight.visual.CameraTweaks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.CameraType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * F5 views: the F5 key steps the view with cycle(), so a skipped view is stepped past here.
 * Only one of the two third person views can be skipped, so one more step always lands.
 */
@Mixin(CameraType.class)
abstract class CameraTypeMixin {
    @ModifyReturnValue(method = "cycle", at = @At("RETURN"))
    private CameraType endsight$skip(CameraType next) {
        return CameraTweaks.skips(next) ? next.cycle() : next;
    }
}
