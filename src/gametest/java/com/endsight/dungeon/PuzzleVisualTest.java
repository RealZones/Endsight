package com.endsight.dungeon;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The new-dungeon puzzles as the game draws them, in rooms rebuilt from the run and
 * clips of 2026-11-09: the 5x5 and 7x7 Ice Fill stages with their real obstacles, the
 * Creeper Beams lanterns mirrored through the point the real pairs share (one pair
 * linked, one half lit), a Tic Tac Toe wall with X and O maps in item frames, and the
 * Quiz's three labelled answer blocks fed Oruo's chat lines. No "PUZZLE!" line is sent
 * for any room, as on the server now; one Quiz line only stands in for the sidebar that
 * tells the mod it is in a dungeon.
 */
public final class PuzzleVisualTest implements FabricClientGameTest {
    // The 5x5 walk from the run, x+124 / z+155, ending on the exit cell (5,2).
    private static final int[][] FIVE = {{0, 2}, {0, 1}, {0, 0}, {1, 0}, {2, 0}, {3, 0}, {3, 1}, {2, 1}, {2, 2}, {1, 2},
            {1, 3}, {0, 3}, {0, 4}, {1, 4}, {2, 4}, {3, 4}, {4, 4}, {4, 3}, {3, 3}, {3, 2}, {4, 2}, {5, 2}};
    // The 7x7 walk, x+124 / z+155 as well, so it sits past the 5x5's exit one block up.
    private static final int[][] SEVEN = {{7, 2}, {7, 1}, {7, 0}, {7, -1}, {8, -1}, {8, 0}, {8, 1}, {9, 1}, {9, 0}, {9, -1},
            {10, -1}, {11, -1}, {12, -1}, {13, -1}, {13, 0}, {12, 0}, {11, 0}, {11, 1}, {12, 1}, {12, 2}, {12, 3}, {11, 3},
            {11, 4}, {10, 4}, {9, 4}, {9, 3}, {8, 3}, {7, 3}, {7, 4}, {7, 5}, {8, 5}, {9, 5}, {10, 5}, {11, 5}, {12, 5},
            {12, 4}, {13, 4}, {13, 3}, {13, 2}, {14, 2}};

    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 800);
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeGui(); });
            BlockPos[] origin = new BlockPos[1];
            world.getServer().runOnServer(server -> {
                                command(server, "time set noon");
                command(server, "difficulty normal");
                var player = server.getPlayerList().getPlayers().getFirst();
                origin[0] = player.blockPosition().below();
                buildIceFill(player.level(), origin[0]);
                command(server, "gamemode creative @a");
                // On the step before the 5x5's entry cell, looking along the stage.
                player.connection.teleport(origin[0].getX() - .5, origin[0].getY() + 1, origin[0].getZ() + 2.5, -90, 50);
            });
            dungeon(context);
            context.waitTicks(20);
            context.takeScreenshot("ice-fill-entry");

            // Halfway along the real walk: the first eleven cells packed, standing on the 11th.
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                for (int i = 0; i < 11; i++)
                    player.level().setBlockAndUpdate(origin[0].offset(FIVE[i][0], 0, FIVE[i][1]), Blocks.PACKED_ICE.defaultBlockState());
                player.connection.teleport(origin[0].getX() + FIVE[10][0] + .5, origin[0].getY() + 1, origin[0].getZ() + FIVE[10][1] + .5, -90, 55);
            });
            context.waitTicks(20);
            context.takeScreenshot("ice-fill-mid");

            BlockPos[] key = new BlockPos[1];
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                key[0] = origin[0].offset(0, 9, 40);
                buildBeams(server, player.level(), key[0], true);
                player.connection.teleport(key[0].getX() - 13.5, origin[0].getY() + 1, key[0].getZ() + .5, -90, -15);
            });
            dungeon(context);
            context.waitTicks(40);
            context.takeScreenshot("creeper-beams");
            context.runOnClient(mc -> {
                long creepers = mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class,
                        mc.player.getBoundingBox().inflate(30)).size();
                if (creepers != 1) throw new AssertionError("expected the puzzle creeper, found " + creepers);
            });

            // The same room with no creeper entity must draw nothing: ordinary rooms have
            // lanterns mirrored through their middle too, and drew beams when this didn't.
            world.getServer().runOnServer(server -> command(server, "kill @e[type=creeper]"));
            context.waitTicks(40);
            context.takeScreenshot("creeper-beams-no-creeper");

            // Tic Tac Toe as the 2026-11-09 clip shows it: X (the machine) middle left and
            // bottom left, your O in the middle. The only move that does not lose is top left.
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                buildTicTacToe(server.overworld(), origin[0].offset(1, 3, -20));
                player.connection.teleport(origin[0].getX() + .5, origin[0].getY() + 1, origin[0].getZ() - 25.5, 0, -12);
            });
            dungeon(context);
            context.waitTicks(60);
            context.takeScreenshot("tic-tac-toe");

            // The Quiz: three answers on their blocks, and Oruo's lines as the log has them.
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                buildQuiz(server.overworld(), origin[0].offset(-20, 1, 0));
                player.connection.teleport(origin[0].getX() - 11.5, origin[0].getY() + 1, origin[0].getZ() + .5, 90, 12);
            });
            dungeon(context);
            context.waitTicks(10);
            context.runOnClient(mc -> {
                for (String line : List.of("§f                                §r§6§lQuestion #1",
                        "§f                    §r§6What is the name of Rick's brother?",
                        "    §6 ⓐ §aWendell", "    §6 ⓑ §aJamie", "    §6 ⓒ §aPat"))
                    mc.getChatListener().handleSystemMessage(Component.literal(line), false);
            });
            context.waitTicks(20);
            context.takeScreenshot("quiz");
            System.out.println("PUZZLE VISUAL CHECKS DONE");
        }
    }

    private static void buildIceFill(net.minecraft.world.level.Level level, BlockPos o) {
        for (BlockPos p : BlockPos.betweenClosed(o.offset(-4, 0, -4), o.offset(18, 0, 8)))
            level.setBlockAndUpdate(p, Blocks.STONE_BRICKS.defaultBlockState());
        for (BlockPos p : BlockPos.betweenClosed(o.offset(-4, 1, -4), o.offset(18, 6, 8)))
            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        // 5x5 at floor height; the cells the walk skips carry polished andesite.
        Set<Long> walked = new HashSet<>();
        for (int[] c : FIVE) { walked.add(key(c[0], c[1])); level.setBlockAndUpdate(o.offset(c[0], 0, c[1]), Blocks.ICE.defaultBlockState()); }
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) if (!walked.contains(key(x, z))) {
            level.setBlockAndUpdate(o.offset(x, 0, z), Blocks.ICE.defaultBlockState());
            level.setBlockAndUpdate(o.offset(x, 1, z), Blocks.POLISHED_ANDESITE.defaultBlockState());
        }
        // A step up, then the 7x7 one block higher.
        for (BlockPos p : BlockPos.betweenClosed(o.offset(6, 0, -1), o.offset(14, 0, 5)))
            level.setBlockAndUpdate(p, Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(o.offset(6, 1, 2), Blocks.STONE_BRICKS.defaultBlockState());
        walked.clear();
        for (int[] c : SEVEN) { walked.add(key(c[0], c[1])); level.setBlockAndUpdate(o.offset(c[0], 1, c[1]), Blocks.ICE.defaultBlockState()); }
        for (int x = 7; x <= 13; x++) for (int z = -1; z <= 5; z++) if (!walked.contains(key(x, z))) {
            level.setBlockAndUpdate(o.offset(x, 1, z), Blocks.ICE.defaultBlockState());
            level.setBlockAndUpdate(o.offset(x, 2, z), Blocks.POLISHED_ANDESITE.defaultBlockState());
        }
    }

    /**
     * Lanterns at the real offsets from the point the real pairs mirror through,
     * which is the centre of block {@code k}: (+1,-5,0)/(-1,+5,0) lit together in the
     * run; (+7,+2,-3), (+7,0,-10) and (+3,+4,+6) lit alone and get their mirrors.
     */
    private static void buildBeams(MinecraftServer server, net.minecraft.world.level.Level level, BlockPos k, boolean creeper) {
        int[][] ends = {{1, -5, 0}, {7, 2, -3}, {7, 0, -10}, {3, 4, 6}};
        for (int i = 0; i < ends.length; i++) {
            int[] e = ends[i];
            // Pair 0 linked, pair 1 half lit, the rest waiting.
            level.setBlockAndUpdate(k.offset(e[0], e[1], e[2]), (i <= 1 ? Blocks.PRISMARINE : Blocks.SEA_LANTERN).defaultBlockState());
            level.setBlockAndUpdate(k.offset(-e[0], -e[1], -e[2]), (i == 0 ? Blocks.PRISMARINE : Blocks.SEA_LANTERN).defaultBlockState());
        }
        // Wall lanterns that pair with nothing.
        level.setBlockAndUpdate(k.offset(8, -6, 3), Blocks.SEA_LANTERN.defaultBlockState());
        level.setBlockAndUpdate(k.offset(-7, -6, 3), Blocks.SEA_LANTERN.defaultBlockState());
        if (creeper) command(server, String.format("summon creeper %.2f %.2f %.2f {NoAI:1b,NoGravity:1b,Silent:1b,PersistenceRequired:1b}",
                k.getX() + .5, k.getY() + .5 - .85, k.getZ() + .5));
    }

    /**
     * A wall of squares facing north, top-left square at {@code topLeft}; X and O on maps
     * in frames, flush on the wall with the button gone, as a side-on view in the 17:20
     * clip shows them.
     */
    private static void buildTicTacToe(ServerLevel level, BlockPos topLeft) {
        int[] marks = {0, 0, 0, 1, 2, 0, 1, 0, 0};                      // 1 X, 2 O
        for (int i = 0; i < 9; i++) {
            BlockPos square = topLeft.offset(-(i % 3), -(i / 3), 0), front = square.north();
            level.setBlockAndUpdate(square, Blocks.POLISHED_ANDESITE.defaultBlockState());
            if (marks[i] == 0) {
                level.setBlockAndUpdate(front, Blocks.STONE_BUTTON.defaultBlockState()
                        .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
                continue;
            }
            ItemStack map = MapItem.create(level, 0, 0, (byte) 0, false, false);
            MapItemSavedData data = level.getMapData(map.get(DataComponents.MAP_ID));
            for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
                boolean ink = marks[i] == 1
                        ? x > 14 && x < 113 && (Math.abs(x - z) < 7 || Math.abs(x + z - 127) < 7)
                        : Math.abs(Math.hypot(x - 63.5, z - 63.5) - 40) < 6;
                data.setColor(x, z, (byte) (ink ? 114 : 34));           // red ink on white
            }
            ItemFrame frame = new ItemFrame(level, front, Direction.NORTH);
            frame.setItem(map, false);
            level.addFreshEntity(frame);
        }
    }

    /** Three answer blocks along z with their labels standing on them, as in the clip. */
    private static void buildQuiz(ServerLevel level, BlockPos middle) {
        String[][] labels = {{"ⓐ", "Wendell."}, {"ⓑ", "Jamie."}, {"ⓒ", "Pat."}};
        for (int i = 0; i < 3; i++) {
            BlockPos block = middle.offset(0, 0, 6 * (i - 1));
            level.setBlockAndUpdate(block, Blocks.SMOOTH_STONE.defaultBlockState());
            ArmorStand stand = new ArmorStand(level, block.getX() + .5, block.getY() + 1, block.getZ() + .5);
            stand.setCustomName(Component.literal("§6" + labels[i][0] + " §a" + labels[i][1]));
            stand.setCustomNameVisible(true);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            level.addFreshEntity(stand);
        }
    }

    private static long key(int x, int z) { return (long) x << 32 | (z & 0xFFFFFFFFL); }

    private static void command(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    /** Dungeon evidence, as in a run; it names a different puzzle, so neither room is announced. */
    private static void dungeon(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            mc.getChatListener().handleSystemMessage(Component.literal("§d§lPUZZLE! §dQuiz §7- Answer three questions."), false);
            mc.gui.getChat().clearMessages(false);
        });
    }
}
