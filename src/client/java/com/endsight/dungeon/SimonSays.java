package com.endsight.dungeon;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.List;

/** Highlights the next physical Simon Says button; player interactions stay manual. */
public final class SimonSays {
    private static final SimonSaysBoard.Sequence SEQUENCE = new SimonSaysBoard.Sequence();
    private static boolean enabled = true;
    private static ClientLevel world;
    private static SimonSaysBoard.Grid grid;
    private static int ticks, missing, idleTicks;

    private SimonSays() { }

    static boolean enabled() { return enabled; }
    static void setEnabled(boolean value) { enabled = value; if (!value) reset(); }

    static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(SimonSays::tick);
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> render());
    }

    private static void reset() {
        world = null; grid = null; ticks = missing = idleTicks = 0;
        SEQUENCE.reset();
    }

    private static boolean active(Minecraft mc) {
        return enabled && TerminalSolver.enabledForSimon() && mc.level != null && mc.player != null
                && DungeonContext.active();
    }

    private static void tick(Minecraft mc) {
        if (!active(mc)) { if (grid != null) reset(); return; }
        if (world != mc.level) reset();
        world = mc.level;
        if (grid != null) {
            BlockPos middle = block(grid.support(5));
            if (mc.player.distanceToSqr(middle.getCenter()) > 18 * 18) { reset(); return; }
        }
        SimonSaysBoard.Probe probe = (support, facing) -> cell(mc.level, support, facing);
        SimonSaysBoard.Snapshot snapshot;
        if (grid == null) {
            if (++ticks % 5 != 0) return;
            snapshot = SimonSaysBoard.find(buttonsNear(mc), probe);
            if (snapshot == null) return;
            grid = snapshot.grid();
            SEQUENCE.reset();
        } else snapshot = SimonSaysBoard.read(grid, probe);
        if (snapshot == null) {
            // Block and button updates are separate packets; do not interpret a
            // half-updated board as a new sequence or show a stale target.
            if (++missing > 20) reset();
            return;
        }
        missing = 0;
        SEQUENCE.observe(snapshot.lights(), snapshot.pressed());
        idleTicks = snapshot.lights() == 0 && snapshot.pressed() == 0 && SEQUENCE.count() == 0
                ? Math.min(100, idleTicks + 1) : 0;
    }

    /** The start button is one cell left and two below the discovered grid's top-left. */
    public static BlockPos autoStartTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (!active(mc) || mc.level != world || grid == null || missing != 0 || idleTicks < 20) return null;
        SimonSaysBoard.Facing side = grid.facing();
        BlockPos pos = block(grid.button(0)).offset(-side.rightX, -2, -side.rightZ);
        if (mc.player.distanceToSqr(pos.getCenter()) > 6 * 6 || facing(mc.level.getBlockState(pos)) != side) return null;
        return pos;
    }

    private static List<SimonSaysBoard.Seed> buttonsNear(Minecraft mc) {
        List<SimonSaysBoard.Seed> seeds = new ArrayList<>();
        BlockPos center = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-10, -6, -10), center.offset(10, 6, 10))) {
            if (!mc.level.hasChunkAt(pos)) continue;
            SimonSaysBoard.Facing facing = facing(mc.level.getBlockState(pos));
            if (facing != null) seeds.add(new SimonSaysBoard.Seed(point(pos), facing));
        }
        return seeds;
    }

    private static SimonSaysBoard.Facing facing(BlockState state) {
        if (!(state.getBlock() instanceof ButtonBlock)
                || !state.hasProperty(BlockStateProperties.ATTACH_FACE)
                || state.getValue(BlockStateProperties.ATTACH_FACE) != AttachFace.WALL
                || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return null;
        Direction direction = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return switch (direction) {
            case NORTH -> SimonSaysBoard.Facing.NORTH;
            case EAST -> SimonSaysBoard.Facing.EAST;
            case SOUTH -> SimonSaysBoard.Facing.SOUTH;
            case WEST -> SimonSaysBoard.Facing.WEST;
            default -> null;
        };
    }

    private static int cell(ClientLevel level, SimonSaysBoard.Point support, SimonSaysBoard.Facing facing) {
        BlockPos tile = block(support);
        BlockPos front = tile.offset(facing.nx, 0, facing.nz);
        if (!level.hasChunkAt(tile) || !level.hasChunkAt(front)) return -1;
        BlockState backing = level.getBlockState(tile), button = level.getBlockState(front);
        if (facing(button) != facing) return -1;
        if (!backing.is(Blocks.OBSIDIAN) && !backing.is(Blocks.SEA_LANTERN)) return -1;
        return (backing.is(Blocks.SEA_LANTERN) ? 1 : 0)
                | (button.getValue(ButtonBlock.POWERED) ? 2 : 0);
    }

    private static void render() {
        Minecraft mc = Minecraft.getInstance();
        if (!active(mc) || mc.level != world || grid == null || missing != 0) return;
        int index = SEQUENCE.target();
        if (index < 0) return;
        BlockPos buttonPos = block(grid.button(index));
        if (!mc.level.hasChunkAt(buttonPos) || cell(mc.level, grid.support(index), grid.facing()) != 0) return;
        BlockState state = mc.level.getBlockState(buttonPos);
        var shape = state.getShape(mc.level, buttonPos);
        if (shape.isEmpty()) return;
        Gizmos.cuboid(shape.bounds().move(buttonPos).inflate(.065),
                GizmoStyle.strokeAndFill(0xFF55FF55, 3f, 0x6655FF55)).setAlwaysOnTop();
    }

    private static SimonSaysBoard.Point point(BlockPos pos) {
        return new SimonSaysBoard.Point(pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockPos block(SimonSaysBoard.Point point) {
        return new BlockPos(point.x(), point.y(), point.z());
    }
}
