package com.endsight.dungeon;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.List;
import java.util.Optional;

/** Draws the dungeon map HUD over a painted room grid with turned player markers. */
public final class DungeonMapVisualTest implements FabricClientGameTest {
    private static final int ROOM = 16, GAP = 4, EDGE = 6;

    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1440, 960);
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(mc -> { mc.options.guiScale().set(3); mc.resizeGui(); });
            context.runOnClient(mc -> {
                MapId id = new MapId(4242);
                MapItemSavedData map = MapItemSavedData.createForClient((byte) 0, true, mc.level.dimension());
                byte brown = MapColor.COLOR_BROWN.getPackedId(MapColor.Brightness.NORMAL);
                String[] grid = {"PGOBBB", "BBBBBB", "BBBBPB", "BPPBBB", "RBBBBY"};
                for (int row = 0; row < grid.length; row++)
                    for (int col = 0; col < 6; col++) {
                        byte colour = switch (grid[row].charAt(col)) {
                            case 'G' -> MapColor.COLOR_GREEN.getPackedId(MapColor.Brightness.NORMAL);
                            case 'R' -> MapColor.COLOR_RED.getPackedId(MapColor.Brightness.NORMAL);
                            case 'P' -> MapColor.COLOR_PURPLE.getPackedId(MapColor.Brightness.NORMAL);
                            case 'Y' -> MapColor.COLOR_YELLOW.getPackedId(MapColor.Brightness.NORMAL);
                            case 'O' -> MapColor.COLOR_ORANGE.getPackedId(MapColor.Brightness.NORMAL);
                            default -> brown;
                        };
                        int x0 = EDGE + col * (ROOM + GAP), y0 = EDGE + row * (ROOM + GAP);
                        for (int y = y0; y < y0 + ROOM; y++) for (int x = x0; x < x0 + ROOM; x++) map.colors[y * 128 + x] = colour;
                    }
                // You (FRAME, facing north) walking into a puzzle room, a teammate facing
                // east, one turned part way.
                map.addClientSideDecorations(List.of(
                        marker(true, 4, 2, 8), marker(false, 0, 2, 12), marker(false, 3, 3, 3)));
                mc.level.overrideMapData(id, map);
                ItemStack stack = new ItemStack(Items.FILLED_MAP);
                stack.set(DataComponents.MAP_ID, id);
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("Magical Map"));
                mc.player.getInventory().setItem(8, stack);
                // Real chat lines, so the map shows and names rooms by the same route as in
                // a run: each is sent while your marker stands in that room.
                mc.getChatListener().handleSystemMessage(Component.literal(
                        "§d§lPUZZLE! §dTeleport Maze §7- After each teleport, cross to the diagonally opposite pad."), false);
            });
            context.waitTicks(20);
            walk(context, 2, 0, "§c§lTRAP ROOM! §7Survive the traps and reach the final reward chest to complete the room.");
            walk(context, 1, 3, "§d§lPUZZLE! §dHigher Or Lower §7- Kill the blazes in order of health.");
            walk(context, 0, 0, "§d§lPUZZLE! §dThree Weirdos §7- One of the three always lies.");
            context.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
            context.waitTicks(5);
            context.takeScreenshot("dungeon-map-hud");
        }
    }

    /** Moves your marker into a room and has the server name it. */
    private static void walk(ClientGameTestContext context, int col, int row, String line) {
        context.runOnClient(mc -> {
            mc.level.getMapData(new MapId(4242)).addClientSideDecorations(List.of(
                    marker(true, col, row, 8), marker(false, 0, 2, 12), marker(false, 3, 3, 3)));
            mc.getChatListener().handleSystemMessage(Component.literal(line), false);
        });
        context.waitTicks(10);
    }

    private static MapDecoration marker(boolean self, int col, int row, int rot) {
        int x = EDGE + col * (ROOM + GAP) + ROOM / 2, y = EDGE + row * (ROOM + GAP) + ROOM / 2;
        return new MapDecoration(self ? MapDecorationTypes.FRAME : MapDecorationTypes.PLAYER,
                (byte) ((x - 64) * 2), (byte) ((y - 64) * 2), (byte) rot, Optional.empty());
    }
}
