package com.endsight.hooks;

import com.endsight.visual.Animations;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Animations: the held item's place, size and swing in first person.
 *
 * The place goes in right after the hand's pose is pushed, before any branch runs. Hooking
 * applyItemArmTransform instead would move the item twice: a few use animations call it a
 * second time. Empty hands and maps are left alone; the option is about the item.
 */
@Mixin(ItemInHandRenderer.class)
abstract class HeldItemAnimationsMixin {
    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER))
    private void endsight$place(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand,
                                float swingProgress, ItemStack stack, float equippedProgress, PoseStack poseStack,
                                SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (stack.isEmpty() || stack.has(DataComponents.MAP_ID)) return;
        Animations.place(poseStack, hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite());
    }

    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void endsight$size(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand,
                               float swingProgress, ItemStack stack, float equippedProgress, PoseStack poseStack,
                               SubmitNodeCollector collector, int light, CallbackInfo ci) {
        Animations.size(poseStack);
    }

    @Inject(method = "swingArm", at = @At("HEAD"), cancellable = true)
    private void endsight$swing(float swingProgress, PoseStack poseStack, int side, HumanoidArm arm, CallbackInfo ci) {
        if (Animations.swing(poseStack, arm, swingProgress)) ci.cancel();
    }
}
