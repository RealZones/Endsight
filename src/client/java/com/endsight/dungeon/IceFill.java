package com.endsight.dungeon;

import com.endsight.dungeon.IceFillLogic.Cell;
import com.endsight.ui.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * "Ice Fill": cross every ice block of each stage once. The search is in
 * {@link IceFillLogic}; this half reads the stage nearest you and draws the walk on it.
 *
 * Nothing announces the room any more (the new dungeons dropped "PUZZLE! ..." lines), so
 * the room is found from its blocks: unvisited ice with room to stand on it. A stage is
 * one flat patch of ice and packed ice; a cell with a block standing on it is left out,
 * which is how the polished andesite obstacles drop out of the walk.
 */
public final class IceFill {
    private static final String ID = "dungeon.iceFill";
    /** A 7x7 stage and its exit is 50 cells; anything far larger is not a stage. */
    private static final int MAX_CELLS = 80;

    private static boolean enabled = true;
    private static ClientLevel world;
    private static int ticks;
    private static List<Vec3> points = List.of();
    // What the current points were solved from, so standing still costs a scan and no search.
    private static int solvedY;
    private static Set<Cell> solvedFresh;
    private static Cell solvedStanding;

    private IceFill() { }

    public static Module module() {
        return new Module(ID, "Ice Fill", "Draws a path that crosses every ice block once.", "Dungeons",
                () -> enabled, value -> { enabled = value; if (!value) reset(); }, List.of());
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(IceFill::tick);
        // Drawn from the client tick, like Key Alert: lines added in the frame's gizmo
        // event never showed in the test client, and ones added in a tick are kept and
        // drawn every frame of it. The lines only change per tick anyway.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> draw());
    }

    private static void reset() {
        world = null; ticks = 0; points = List.of();
        solvedFresh = null; solvedStanding = null;
    }

    private static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null || !DungeonContext.active()) {
            if (world != null) reset();
            return;
        }
        if (world != mc.level) { reset(); world = mc.level; }
        // Five times a second at a stage: steps in a run come 0.3-0.5 s apart, and the
        // server turns a cell to packed ice as you land on it, so this keeps up with a walk.
        // Twice a second elsewhere: the search reads 2,205 blocks, and every other room in
        // the dungeon has no ice (performance audit, 2026-11-09).
        if (++ticks % (points.isEmpty() ? 10 : 4) != 0) return;
        Stage stage = nearestStage(mc);
        if (stage == null) { points = List.of(); solvedFresh = null; return; }

        Cell standing = null;
        Cell under = new Cell(mc.player.getBlockX(), mc.player.getBlockZ());
        if (stage.cells().contains(under) && Math.abs(mc.player.getY() - (stage.y() + 1)) < .6) standing = under;
        // Off the stage after stepping on it means a failed attempt about to reset: a walk
        // from the nearest cell would ask for packed ice. Wait for the fresh board.
        if (standing == null && stage.fresh().size() != stage.cells().size()) { points = List.of(); solvedFresh = null; return; }
        if (stage.y() == solvedY && stage.fresh().equals(solvedFresh) && Objects.equals(standing, solvedStanding)) return;

        solvedY = stage.y(); solvedFresh = stage.fresh(); solvedStanding = standing;
        List<Cell> walk = IceFillLogic.plan(stage.fresh(), standing, mc.player.getX(), mc.player.getZ(), IceFillLogic.BUDGET);
        List<Vec3> planned = new ArrayList<>(walk.size());
        for (Cell cell : walk) planned.add(new Vec3(cell.x() + .5, stage.y() + 1.06, cell.z() + .5));
        points = List.copyOf(planned);
    }

    /** One stage: every cell you may stand on, and the ones still unvisited. */
    private record Stage(int y, Set<Cell> cells, Set<Cell> fresh) { }

    /** The stage you are on, else the closest one that still has ice on it. */
    private static Stage nearestStage(Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        List<BlockPos> seeds = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-10, -3, -10), feet.offset(10, 1, 10))) {
            if (mc.level.getBlockState(pos).is(Blocks.ICE) && open(mc, pos.above())) seeds.add(pos.immutable());
        }
        if (seeds.isEmpty()) return null;
        Set<BlockPos> grouped = new HashSet<>();
        Stage best = null;
        double nearest = Double.MAX_VALUE;
        for (BlockPos seed : seeds) {
            if (grouped.contains(seed)) continue;
            Stage stage = flood(mc, seed, grouped);
            if (stage == null) continue;
            double distance = Double.MAX_VALUE;
            for (Cell cell : stage.cells())
                distance = Math.min(distance, Math.pow(cell.x() + .5 - mc.player.getX(), 2)
                        + Math.pow(stage.y() + 1 - mc.player.getY(), 2) + Math.pow(cell.z() + .5 - mc.player.getZ(), 2));
            if (distance < nearest) { nearest = distance; best = stage; }
        }
        return best;
    }

    /** The flat patch of ice and packed ice joined to {@code seed}, or null if too big to be a stage. */
    private static Stage flood(Minecraft mc, BlockPos seed, Set<BlockPos> grouped) {
        Set<Cell> cells = new HashSet<>(), fresh = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        grouped.add(seed);
        queue.add(seed);
        boolean tooBig = false;
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            BlockState state = mc.level.getBlockState(pos);
            cells.add(new Cell(pos.getX(), pos.getZ()));
            if (state.is(Blocks.ICE)) fresh.add(new Cell(pos.getX(), pos.getZ()));
            if (cells.size() > MAX_CELLS) { tooBig = true; continue; }   // keep marking it grouped, never solve it
            for (BlockPos next : new BlockPos[]{pos.north(), pos.south(), pos.east(), pos.west()}) {
                if (grouped.contains(next) || !mc.level.hasChunkAt(next)) continue;
                BlockState there = mc.level.getBlockState(next);
                if (!there.is(Blocks.ICE) && !there.is(Blocks.PACKED_ICE)) continue;
                if (!open(mc, next.above())) continue;
                grouped.add(next);
                queue.add(next);
            }
        }
        return tooBig || fresh.isEmpty() ? null : new Stage(seed.getY(), Set.copyOf(cells), Set.copyOf(fresh));
    }

    private static boolean open(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    private static void draw() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.level == null || mc.level != world || points.size() < 2 || !DungeonContext.active()) return;
        for (int i = 1; i < points.size(); i++) {
            Vec3 from = points.get(i - 1), to = points.get(i);
            int colour = i == 1 ? 0xFF55FF55 : 0xFF55FFFF;            // the next step stands out
            Gizmos.line(from, to, (colour & 0x00FFFFFF) | 0x40000000, 6f);
            Gizmos.line(from, to, colour, 2f);
        }
    }
}
