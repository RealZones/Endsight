package com.endsight.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Reading Dragsim's dungeon grid out of the world: whether the seam between two cells is
 * part of one room. The grid itself, and the run it was measured from, is described on
 * {@link RoomGlow}.
 *
 * Answers null while its chunk is not loaded, so callers ask again later rather than
 * remember "two rooms" for a seam the server simply has not sent yet.
 */
final class DungeonGrid {
    private DungeonGrid() { }

    /** Seam samples sit this far either side of a cell's centre line: clear of the 3-wide door. */
    private static final int OFF_DOOR = 8;
    /** Rooms stand well inside this; it covers deep floors and tall ceilings alike. */
    private static final int LOW = 0, HIGH = 160;

    /** The middle block of cell i, on either axis. */
    static int centre(int i) {
        return RoomGlow.ORIGIN + RoomGlow.CELL * i + RoomGlow.CELL / 2 - 1;
    }

    /** The gap column after cell i. */
    static int gapLine(int i) {
        return RoomGlow.ORIGIN + RoomGlow.CELL * (i + 1) - 1;
    }

    /**
     * Whether (i, j) and its neighbour (+1 in x if alongX, else in z) are one room. Two
     * columns in the seam, eight blocks either side of the door line; either with a block
     * in it makes the seam part of a room. Leaning that way on purpose: a missed merge cuts
     * a room in two, a false one only joins two rooms that touch.
     */
    static Boolean seam(Level level, int i, int j, boolean alongX) {
        int line = gapLine(alongX ? i : j), c = centre(alongX ? j : i);
        int[][] samples = alongX
                ? new int[][]{{line, c - OFF_DOOR}, {line, c + OFF_DOOR}}
                : new int[][]{{c - OFF_DOOR, line}, {c + OFF_DOOR, line}};
        for (int[] s : samples) if (!loaded(level, s[0], s[1])) return null;
        for (int[] s : samples) if (filled(level, s[0], s[1])) return true;
        return false;
    }

    /**
     * Whether the client holds this block's chunk. Not Level.hasChunkAt: on the client that
     * answers yes for everywhere, and an unloaded chunk then reads as solid air - a seam as
     * "two rooms" - for good. Found when cells far past the loaded area came back empty.
     */
    static boolean loaded(Level level, int x, int z) {
        return level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) != null;
    }

    private static boolean filled(Level level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = LOW; y < HIGH; y++) if (!level.getBlockState(pos.set(x, y, z)).isAir()) return true;
        return false;
    }
}
