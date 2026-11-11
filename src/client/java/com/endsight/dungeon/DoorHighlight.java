package com.endsight.dungeon;

import com.endsight.dungeon.DoorHighlightLogic.Door;
import com.endsight.dungeon.DoorHighlightLogic.Kind;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The wither and blood doors still locked, boxed through walls, with a tracer to the nearest.
 * Which doors, and how they are found, is on {@link DoorHighlightLogic}.
 *
 * Through walls, unlike Key Alerts' box on a dropped key: Fear asked for doors to show
 * through walls on 2026-11-11, as Lazarus draws them, since a door you can only see once
 * you are at it is no help finding it. It stays limited to the doors the dungeon map would show.
 */
public final class DoorHighlight {
    private DoorHighlight() { }

    private static boolean enabled = true, tracer = true;
    private static List<Door> doors = List.of();
    /** One bit per cell of every room you have stood in this run, as RoomGlow numbers them. */
    private static long visited;
    private static Level world;
    private static int countdown;

    public static Module module() {
        return new Module("dungeon.doors", "Door Highlight", "Boxes locked wither and blood doors, through walls.", "Dungeons",
                () -> enabled, value -> { enabled = value; if (!value) doors = List.of(); }, List.of(
                new Setting.Toggle("Tracer", "A line to the nearest locked door.",
                        () -> tracer, value -> tracer = value)));
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(DoorHighlight::tick);
        // The tracer starts at the camera, so it is redrawn every frame; the boxes are drawn
        // from the tick, as Quiz does, which is also what shows in the test client.
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> drawTracer());
    }

    private static void tick(Minecraft mc) {
        if (mc.level != world) {
            world = mc.level;
            visited = 0;
            doors = List.of();
            countdown = 0;
        }
        if (!enabled || mc.player == null || mc.level == null || !DungeonContext.active()) {
            doors = List.of();
            return;
        }
        // Twice a second: a door opening clears within half a second, and a scan is a few
        // hundred block reads.
        if (--countdown <= 0) {
            countdown = 10;
            Level level = mc.level;
            RoomGlow.Seams seams = (i, j, alongX) -> RoomGlow.seam(level, i, j, alongX);
            visited |= RoomGlow.around(mc.player.getX(), mc.player.getZ(), seams);
            long seen = DoorHighlightLogic.seen(visited, space(level), (i, j) -> RoomGlow.flood(i, j, seams));
            doors = DoorHighlightLogic.doors(blocks(level), seen);
        }
        for (Door d : doors) {
            AABB box = box(d);
            // A dark edge under the coloured line: a line alone in one colour vanished either
            // on grey stone brick (seen through the wall) or on the coal itself, by colour.
            Gizmos.cuboid(box, GizmoStyle.strokeAndFill(EDGE, 5f, 0)).setAlwaysOnTop();
            Gizmos.cuboid(box, GizmoStyle.strokeAndFill(line(d.kind()), 2f, fill(d.kind()))).setAlwaysOnTop();
        }
    }

    private static void drawTracer() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || !tracer || doors.isEmpty() || mc.level != world) return;
        // Same shape as Quiz's tracer: from a little ahead of and below the camera, so the
        // line reads as coming from you rather than out of your eye.
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.position();
        Door d = DoorHighlightLogic.nearest(doors, eye.x, eye.y, eye.z);
        if (d == null) return;
        var forward = camera.forwardVector();
        Vec3 from = eye.add(forward.x() * .8, forward.y() * .8 - .45, forward.z() * .8);
        Vec3 to = new Vec3(d.centreX(), d.centreY(), d.centreZ());
        Gizmos.line(from, to, EDGE & 0x70FFFFFF, 5f).setAlwaysOnTop();
        Gizmos.line(from, to, line(d.kind()), 1.8f).setAlwaysOnTop();
    }

    private static final int EDGE = 0xFF101010;

    /**
     * A wither door's line is a light grey and its fill near black, so through a wall it reads
     * as a black door with an edge you can see; mid grey, the first try, was the colour of the
     * stone brick it was drawn over. The blood door is red.
     */
    private static int line(Kind kind) {
        return kind == Kind.BLOOD ? 0xFFFF3030 : 0xFFC8C8D0;
    }

    private static int fill(Kind kind) {
        return kind == Kind.BLOOD ? 0x40FF3030 : 0x80101010;
    }

    private static AABB box(Door d) {
        return new AABB(d.minX(), d.minY(), d.minZ(), d.maxX() + 1, d.maxY() + 1, d.maxZ() + 1).inflate(.02);
    }

    private static DoorHighlightLogic.Space space(Level level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return (x, y, z) -> DungeonGrid.loaded(level, x, z) && level.getBlockState(pos.set(x, y, z)).isAir();
    }

    /** The world as door blocks, skipping chunks the server has not sent. */
    private static DoorHighlightLogic.Blocks blocks(Level level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            if (!DungeonGrid.loaded(level, x, z)) return null;
            BlockState s = level.getBlockState(pos.set(x, y, z));
            if (s.is(Blocks.COAL_BLOCK)) return Kind.WITHER;
            if (s.is(Blocks.RED_TERRACOTTA)) return Kind.BLOOD;
            return null;
        };
    }
}
