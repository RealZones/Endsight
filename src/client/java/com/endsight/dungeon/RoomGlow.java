package com.endsight.dungeon;

import com.endsight.ui.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Only the mobs in the room you are in glow.
 *
 * Dragsim makes every mob a room needs killed glow, and a glow shows through walls, so a
 * whole floor of them lit up at once. Players asked for the rest to be hidden. Players
 * themselves keep their glow: a teammate is worth seeing through a wall.
 *
 * The room is worked out in world terms. Dragsim's new dungeons sit on Hypixel's grid:
 * 32-block cells from -200 on both axes, each room 31 blocks wide, with the 32nd column a
 * one-block gap that is empty air except where a door fills it. Every door in a recorded
 * run (2026-11-09) sat exactly on that grid: in a gap column, on a cell's centre line. A
 * room bigger than one cell has no gap inside it - its floor and walls run across the
 * seam - so two cells are one room when their seam has blocks in it away from the door.
 * The old generator, before November, built at positive coordinates on a grid from 0. That
 * is off this grid altogether, so there the glow is left as the server sends it.
 */
public final class RoomGlow {
    private RoomGlow() { }

    private static boolean enabled = true;

    public static Module module() {
        return new Module("dungeon.roomGlow", "Room Glow", "Only mobs in the room you are in glow.", "Dungeons",
                () -> enabled, v -> { enabled = v; if (!v) room = 0; }, List.of());
    }

    static final int ORIGIN = -200, CELL = 32, CELLS = 6;

    /** One bit per cell of your room, x cell * 6 + z cell. 0 means glow is left alone. */
    private static long room;
    private static Level world;
    /** Seams already read this run; a seam never changes once its chunks are loaded. */
    private static final Map<Integer, Boolean> seams = new HashMap<>();
    private static int ticks;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(RoomGlow::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.level != world) {
            world = mc.level;
            seams.clear();
            room = 0;
        }
        if (!enabled || mc.player == null || mc.level == null || !DungeonContext.active()) {
            room = 0;
            return;
        }
        // Twice a second: walking into a room shows its mobs within a few steps, and the
        // seams are cached, so a refresh is a few dozen bit operations.
        if (--ticks > 0) return;
        ticks = 10;
        room = around(mc.player.getX(), mc.player.getZ(), (i, j, alongX) -> seam(mc.level, i, j, alongX));
    }

    /**
     * Whether this entity's glow should be dropped. Called for every entity every frame,
     * so it is only arithmetic on the cached room.
     */
    public static boolean hides(Entity e) {
        long r = room;
        return r != 0 && !(e instanceof Player) && !inside(r, e.getX(), e.getZ());
    }

    // ── grid, kept free of the game so the checks can run it ──────────────────────

    interface Seams {
        /** Whether (i, j) and its neighbour, +1 in x if alongX, else +1 in z, are one room. */
        boolean merged(int i, int j, boolean alongX);
    }

    static int cell(double v) {
        return Math.floorDiv((int) Math.floor(v) - ORIGIN, CELL);
    }

    /** The one-block column between two cells, counted as the lower cell's last block. */
    static boolean gap(double v) {
        return Math.floorMod((int) Math.floor(v) - ORIGIN, CELL) == CELL - 1;
    }

    static boolean onGrid(int i, int j) {
        return i >= 0 && i < CELLS && j >= 0 && j < CELLS;
    }

    static boolean has(long room, int i, int j) {
        return onGrid(i, j) && (room >>> (i * CELLS + j) & 1) != 0;
    }

    /**
     * The room around a point. Standing in a doorway you are in neither room and both, so
     * a point in a gap takes the rooms on each side. Off the grid - the boss arena, the
     * village - there is no room, and glow is left alone.
     */
    static long around(double x, double z, Seams seams) {
        int i = cell(x), j = cell(z);
        if (!onGrid(i, j)) return 0;
        long r = flood(i, j, seams);
        if (gap(x) && onGrid(i + 1, j)) r |= flood(i + 1, j, seams);
        if (gap(z) && onGrid(i, j + 1)) r |= flood(i, j + 1, seams);
        return r;
    }

    /** A mob in a doorway belongs to the rooms on both sides of it. */
    static boolean inside(long room, double x, double z) {
        int i = cell(x), j = cell(z);
        return has(room, i, j) || gap(x) && has(room, i + 1, j) || gap(z) && has(room, i, j + 1);
    }

    /** Every cell joined to (i, j) through merged seams. */
    static long flood(int i, int j, Seams seams) {
        long found = 1L << (i * CELLS + j);
        int[] queue = new int[CELLS * CELLS];
        int head = 0, tail = 0;
        queue[tail++] = i * CELLS + j;
        while (head < tail) {
            int c = queue[head++], ci = c / CELLS, cj = c % CELLS;
            int[][] next = {{ci + 1, cj}, {ci - 1, cj}, {ci, cj + 1}, {ci, cj - 1}};
            for (int[] n : next) {
                if (!onGrid(n[0], n[1]) || (found >>> (n[0] * CELLS + n[1]) & 1) != 0) continue;
                boolean alongX = n[1] == cj;
                int si = Math.min(ci, n[0]), sj = Math.min(cj, n[1]);
                if (!seams.merged(si, sj, alongX)) continue;
                found |= 1L << (n[0] * CELLS + n[1]);
                queue[tail++] = n[0] * CELLS + n[1];
            }
        }
        return found;
    }

    // ── reading a seam from the world ─────────────────────────────────────────────

    /** Cached, since a seam never changes once read; one not loaded yet is asked again. */
    private static boolean seam(Level level, int i, int j, boolean alongX) {
        int key = (i * CELLS + j) * 2 + (alongX ? 1 : 0);
        Boolean known = seams.get(key);
        if (known != null) return known;
        Boolean read = DungeonGrid.seam(level, i, j, alongX);
        if (read == null) return false;
        seams.put(key, read);
        return read;
    }
}
