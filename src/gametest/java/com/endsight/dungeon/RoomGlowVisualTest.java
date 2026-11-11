package com.endsight.dungeon;

import com.endsight.EndsightClient;
import com.endsight.ui.Module;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.cow.Cow;

import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * Room Glow on the dungeon grid: you in cell (0,0); a mob beside you, one in (1,0) joined to
 * yours by a block in the seam (a two-cell room), one in (0,1) past an empty gap. Only the
 * first two glow; with the module off, all three do. Cows, not monsters: the test world is
 * peaceful and removes hostile mobs the moment their chunk ticks.
 */
public final class RoomGlowVisualTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1920, 1009);
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("time set noon");
            context.runOnClient(mc -> {
                mc.options.guiScale().set(2);
                mc.options.renderDistance().set(6);
                mc.resizeGui();
            });
            // There first, at ground level: the grid is 280 blocks from spawn and a fill into
            // unloaded chunks does nothing; dropped at 69 instead, the fall killed the player,
            // and a dead player is sent no entities at all.
            server.runCommand("tp @a -185 -60 -185 -45 15");
            context.waitTicks(40);
            // Floors for three cells, each stopping short of the gap column around it.
            server.runCommand("fill -199 68 -199 -170 68 -170 minecraft:stone");
            server.runCommand("fill -168 68 -199 -138 68 -170 minecraft:stone");
            server.runCommand("fill -199 68 -168 -170 68 -138 minecraft:stone");
            // The seam between (0,0) and (1,0) gets a block away from the door line: one room.
            server.runCommand("setblock -169 75 -177 minecraft:stone");
            server.runCommand("tp @a -185 69 -185 -45 15");
            context.waitTicks(5);
            context.runOnClient(mc -> require(!mc.level.getBlockState(new net.minecraft.core.BlockPos(-169, 75, -177)).isAir(),
                    "the seam block was not placed"));
            server.runCommand("summon minecraft:cow -180 69 -182 {NoAI:1b,Glowing:1b,PersistenceRequired:1b,Silent:1b}");
            server.runCommand("summon minecraft:cow -155 69 -182 {NoAI:1b,Glowing:1b,PersistenceRequired:1b,Silent:1b}");
            server.runCommand("summon minecraft:cow -182 69 -155 {NoAI:1b,Glowing:1b,PersistenceRequired:1b,Silent:1b}");
            context.waitTicks(40);
            // A dungeon as the mod knows one: the server's puzzle line.
            server.runCommand("tellraw @a \"PUZZLE! Test Room - Example\"");
            context.waitTicks(25);
            Module module = EndsightClient.registry().all().stream().filter(m -> m.id().equals("dungeon.roomGlow")).findFirst().orElseThrow();
            context.runOnClient(mc -> {
                require(DungeonContext.active(), "the test is not in a dungeon");
                List<Entity> h = cows(mc);
                require(h.size() == 3, "expected three cows, found " + h.size());
                require(mc.shouldEntityAppearGlowing(h.get(0)), "the cow in your cell lost its glow");
                require(mc.shouldEntityAppearGlowing(h.get(1)), "the cow across the joined seam lost its glow");
                require(!mc.shouldEntityAppearGlowing(h.get(2)), "the cow past the empty gap still glows");
            });
            context.takeScreenshot("room-glow-on");
            context.runOnClient(mc -> module.setEnabled().accept(false));
            context.waitTicks(2);
            context.runOnClient(mc -> {
                for (Entity e : cows(mc)) require(mc.shouldEntityAppearGlowing(e), "with Room Glow off a cow did not glow");
            });
            context.takeScreenshot("room-glow-off");
            context.runOnClient(mc -> module.setEnabled().accept(true));
            System.out.println("ROOM GLOW CHECKS DONE");
        }
    }

    /** Ordered: yours, the joined cell's, the far one's. */
    private static List<Entity> cows(Minecraft mc) {
        return StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), false)
                .filter(e -> e instanceof Cow)
                .sorted(Comparator.comparingDouble((Entity e) -> e.getZ()).thenComparingDouble(Entity::getX))
                .toList();
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
