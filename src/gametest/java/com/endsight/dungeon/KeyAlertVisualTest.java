package com.endsight.dungeon;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Dragsim's Wither Key - an invisible armour stand wearing a head under a "Wither Key" name -
 * in front of a wall, a dropped Blood Key item beside it, and a second Wither Key hidden
 * behind the wall: the alert fires, the two in view get their black and red boxes, and the
 * hidden one shows nothing through the wall.
 */
public final class KeyAlertVisualTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1440, 960);
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(mc -> { mc.options.guiScale().set(3); mc.resizeGui(); });
            // Spawned by the server, as a real drop is: an item added on the client alone is
            // never drawn, so it could not show whether the outline works.
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var level = player.level();
                player.setYRot(0); player.setXRot(10);
                player.connection.teleport(player.getX(), player.getY(), player.getZ(), 0, 10);
                BlockPos ahead = player.blockPosition().south(5);
                for (int x = -2; x <= 2; x++) for (int y = 0; y <= 2; y++)
                    level.setBlockAndUpdate(ahead.offset(x, y, 0), Blocks.STONE_BRICKS.defaultBlockState());
                level.addFreshEntity(stand(level, player.getX() - 0.6, player.getY() - 1, player.getZ() + 3));
                level.addFreshEntity(stand(level, player.getX(), player.getY() - 1, player.getZ() + 7));
                level.addFreshEntity(key(level, "§cBlood Key", player.getX() + 1, player.getY() + 1, player.getZ() + 2));
            });
            context.runOnClient(mc -> {
                // Real chat evidence puts the client in a dungeon, as in a run.
                mc.getChatListener().handleSystemMessage(Component.literal("§d§lPUZZLE! §dQuiz §7- Answer three questions."), false);
                mc.gui.getChat().clearMessages(false);
            });
            context.waitTicks(15);
            context.takeScreenshot("key-alert-dropped");
            context.runOnClient(mc -> {
                var items = mc.level.getEntitiesOfClass(ItemEntity.class, mc.player.getBoundingBox().inflate(10));
                var stands = mc.level.getEntitiesOfClass(ArmorStand.class, mc.player.getBoundingBox().inflate(10));
                if (items.size() != 1 || !KeyAlert.marked(items.getFirst()))
                    throw new AssertionError("dropped Blood Key item is not boxed");
                if (stands.size() != 2 || stands.stream().anyMatch(stand -> !KeyAlert.marked(stand)))
                    throw new AssertionError("Wither Key stand is not highlighted");
            });
        }
    }

    private static ArmorStand stand(net.minecraft.world.level.Level level, double x, double y, double z) {
        var stand = new ArmorStand(level, x, y, z);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setCustomName(Component.literal("§7Wither Key"));
        stand.setCustomNameVisible(true);
        stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.WITHER_SKELETON_SKULL));
        return stand;
    }

    private static ItemEntity key(net.minecraft.world.level.Level level, String name, double x, double y, double z) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        ItemEntity item = new ItemEntity(level, x, y, z, stack, 0, 0, 0);
        item.setNoGravity(true);
        item.setNeverPickUp();
        item.setUnlimitedLifetime();
        return item;
    }
}
