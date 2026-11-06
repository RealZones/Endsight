package com.endsight.hooks;

import com.endsight.visual.Perspective;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Where Perspective Mode points the camera.
 *
 * alignWithEntity reads the angle off the entity it follows and then, in third person, backs
 * the camera out along that angle, so handing it our angle is all it takes to swing the camera
 * round the player without turning them. Every read is caught, since the minecart branch has
 * its own pair.
 */
@Mixin(Camera.class)
abstract class PerspectiveCameraMixin {
    @Redirect(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float endsight$yaw(Entity e, float partialTick) {
        return Perspective.active() && e == Minecraft.getInstance().player ? Perspective.yaw() : e.getViewYRot(partialTick);
    }

    @Redirect(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
    private float endsight$pitch(Entity e, float partialTick) {
        return Perspective.active() && e == Minecraft.getInstance().player ? Perspective.pitch() : e.getViewXRot(partialTick);
    }
}
