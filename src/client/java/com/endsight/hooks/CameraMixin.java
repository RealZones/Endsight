package com.endsight.hooks;

import com.endsight.visual.CameraTweaks;
import com.endsight.visual.Perspective;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Where the camera points, how high it starts and how far back it sits.
 *
 * alignWithEntity reads the angle off the entity it follows and then, in third person, backs
 * the camera out along that angle, so handing it our angle is all it takes to swing the camera
 * round the player without turning them (Free Look). Every read is caught, since the minecart
 * branch has its own pair.
 *
 * Wrapped rather than redirected: Fabulously Optimized ships Zoomify and others that touch
 * this class, and a second @Redirect on the same call stops the game at startup.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float endsight$yaw(Entity e, float partialTick, Operation<Float> original) {
        CameraTweaks.frame();
        return Perspective.active() && e == Minecraft.getInstance().player ? Perspective.yaw() : original.call(e, partialTick);
    }

    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
    private float endsight$pitch(Entity e, float partialTick, Operation<Float> original) {
        float pitch = Perspective.active() && e == Minecraft.getInstance().player ? Perspective.pitch() : original.call(e, partialTick);
        return CameraTweaks.tilt(pitch);
    }

    /**
     * getMaxZoom is the distance, cut short by eight rays at whatever wall is behind you.
     * Through blocks skips the rays rather than overriding their answer afterwards; the
     * answer is all they are for.
     */
    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
    private float endsight$zoom(Camera camera, float wanted, Operation<Float> original) {
        lift(camera);
        float distance = CameraTweaks.distance(wanted);
        return CameraTweaks.zoom(CameraTweaks.through() ? distance : original.call(camera, distance));
    }

    /**
     * Height: the eye point moved up or down before the camera backs out from it, so the
     * walls are then checked from the new point. The lift itself is stopped short of a
     * ceiling or floor unless Through blocks is on, or a raised camera under a low roof
     * would start inside the block and see straight through it.
     */
    private void lift(Camera camera) {
        double h = CameraTweaks.height();
        if (h == 0) return;
        Vec3 from = position();
        Vec3 to = from.add(0, h, 0);
        Entity e = camera.entity();
        if (!CameraTweaks.through() && e != null) {
            BlockHitResult hit = e.level().clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, e));
            if (hit.getType() != HitResult.Type.MISS) to = from.add(0, Math.signum(h) * Math.max(0, Math.abs(hit.getLocation().y - from.y) - 0.1), 0);
        }
        setPosition(to);
    }

    @Shadow public abstract Vec3 position();
    @Shadow protected abstract void setPosition(Vec3 position);
}
