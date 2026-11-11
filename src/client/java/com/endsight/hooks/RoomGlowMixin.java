package com.endsight.hooks;

import com.endsight.dungeon.RoomGlow;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Room Glow. Every glow outline the game draws is decided here, from the entity's glowing
 * flag (or the spectator outline key), so dropping it here drops the outline and nothing
 * else: the mob, its name and its health bar are untouched.
 */
@Mixin(Minecraft.class)
abstract class RoomGlowMixin {
    @ModifyReturnValue(method = "shouldEntityAppearGlowing", at = @At("RETURN"))
    private boolean endsight$roomGlow(boolean glows, Entity entity) {
        return glows && !RoomGlow.hides(entity);
    }
}
