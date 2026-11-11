package com.endsight.dungeon;

import com.endsight.EndsightClient;
import com.endsight.ui.Module;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.List;

/**
 * Door Highlight in a built dungeon corner: you in cell (0,0) behind a wall, a wither door
 * out of it to (1,0), the blood door from (1,0) on to (1,1), and a wither door far off in a
 * room you have not been in. The near ones are boxed through the wall, the far one is not;
 * opening the wither door takes its box away.
 */
public final class DoorHighlightVisualTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1920, 1009);
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("time set noon");
            context.runOnClient(mc -> {
                mc.options.guiScale().set(2);
                mc.options.renderDistance().set(8);
                mc.resizeGui();
            });
            // To the grid at ground level first: a fill into unloaded chunks does nothing, and
            // a drop to y 69 from spawn kills the player (see RoomGlowVisualTest).
            server.runCommand("tp @a -185 -60 -185 0 0");
            context.waitTicks(40);
            // A floor per cell, stopping short of the gap columns: a block in a seam would make
            // the two cells one room, and every door between them "visited".
            server.runCommand("fill -199 68 -199 -170 68 -170 minecraft:stone_bricks");
            server.runCommand("fill -168 68 -199 -138 68 -170 minecraft:stone_bricks");
            server.runCommand("fill -168 68 -168 -138 68 -138 minecraft:stone_bricks");
            // A wall between you and the wither door, so the box is seen through it.
            server.runCommand("fill -175 69 -190 -175 75 -180 minecraft:stone_bricks");
            // The wither door between (0,0) and (1,0): 3 across, 4 high, 3 deep on the gap.
            server.runCommand("fill -170 69 -186 -168 72 -184 minecraft:coal_block");
            // Coal round the doorway, as Dragsim builds it: two rows over the door. Never boxed.
            server.runCommand("fill -170 73 -187 -168 74 -183 minecraft:coal_block");
            // The blood door between (1,0) and (1,1).
            server.runCommand("fill -154 69 -170 -152 72 -168 minecraft:red_terracotta");
            // A wither door on the far side of (0,1), between it and (0,2). (0,1) is never
            // stood in, but the doorway into it from (0,0) is open, so this door shows.
            server.runCommand("fill -186 69 -138 -184 72 -136 minecraft:coal_block");
            // A wither door between (2,1) and (3,1): no room of it visited.
            server.runCommand("fill -106 69 -154 -104 72 -152 minecraft:coal_block");
            server.runCommand("tp @a -190 69 -185 -90 5");
            context.waitTicks(5);
            server.runCommand("tellraw @a \"PUZZLE! Test Room - Example\"");
            context.waitTicks(40);
            Module module = EndsightClient.registry().all().stream().filter(m -> m.id().equals("dungeon.doors")).findFirst().orElseThrow();
            context.runOnClient(mc -> {
                require(DungeonContext.active(), "the test is not in a dungeon");
                List<DoorHighlightLogic.Door> doors = doors();
                require(doors.size() == 2 && doors.stream().allMatch(d -> d.kind() == DoorHighlightLogic.Kind.WITHER),
                        "from the start room its own wither door and the one past the open doorway: " + doors);
                require(doors.stream().filter(d -> d.minX() == -170).allMatch(d -> d.maxY() == 72),
                        "the coal over the doorway was boxed with the door: " + doors);
            });
            context.takeScreenshot("doors-start-room");
            // Through the door into (1,0): its blood door joins. Facing back south-west, with
            // the wither door to the right and the blood door ahead.
            server.runCommand("tp @a -150 69 -192 45 10");
            context.waitTicks(30);
            context.runOnClient(mc -> {
                List<DoorHighlightLogic.Door> doors = doors();
                require(doors.size() == 3, "after (1,0) its blood door joins the two, not " + doors);
                require(doors.stream().noneMatch(d -> d.minX() > -130), "the far door showed before its room was reached");
            });
            context.takeScreenshot("doors-second-room");
            // Opened: the coal goes and so does its box.
            server.runCommand("fill -170 69 -186 -168 72 -184 minecraft:air");
            context.waitTicks(25);
            context.runOnClient(mc -> {
                List<DoorHighlightLogic.Door> doors = doors();
                require(doors.size() == 2 && doors.stream().noneMatch(d -> d.minX() == -170)
                        && doors.stream().anyMatch(d -> d.kind() == DoorHighlightLogic.Kind.BLOOD),
                        "after opening the wither door its box is gone: " + doors);
            });
            context.takeScreenshot("doors-after-open");
            context.runOnClient(mc -> module.setEnabled().accept(false));
            context.waitTicks(2);
            context.runOnClient(mc -> require(doors().isEmpty(), "off still boxed doors"));
            context.runOnClient(mc -> module.setEnabled().accept(true));
            System.out.println("DOOR HIGHLIGHT CHECKS DONE");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<DoorHighlightLogic.Door> doors() {
        try {
            var f = DoorHighlight.class.getDeclaredField("doors");
            f.setAccessible(true);
            return (List<DoorHighlightLogic.Door>) f.get(null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
