package com.endsight.dungeon;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a dungeon's locked doors can stand, which of them to show, and how one is read out
 * of the blocks there.
 *
 * A door only ever fills the one-block gap between two cells, on the cells' centre line (see
 * {@link RoomGlow} for the grid and the run it was measured from). So rather than searching
 * the dungeon for door blocks, each of the 60 places a door can be is looked at directly:
 * a few hundred block reads, against tens of thousands for a search.
 *
 * A wither door is coal blocks and the blood door red terracotta, as on Hypixel. Lazarus's
 * Door Highlight reads Dragsim's doors by the same two blocks, which is what confirms them
 * here; no door of ours has been read live yet.
 *
 * Only doors on the edge of a room you have stood in this run, or can see into from one
 * through an open doorway, are shown (see seen). The server sends most of the dungeon's
 * chunks from the start, so every door in the world would be the whole route to the blood
 * room before anyone has explored it - far more than a highlight should give away. These
 * are about the doors the dungeon map shows you.
 */
final class DoorHighlightLogic {
    private DoorHighlightLogic() { }

    enum Kind { WITHER, BLOOD }

    /** What stands at a block, as far as doors go: a door's block, or null for anything else. */
    interface Blocks { Kind at(int x, int y, int z); }

    /** One place a door can stand: the gap after cell (i, j), +1 in x if alongX, else +1 in z. */
    record Slot(int x, int z, int i, int j, boolean alongX) {
        /** Whether either room this door joins is one of these cells. */
        boolean touches(long rooms) {
            return RoomGlow.has(rooms, i, j) || RoomGlow.has(rooms, alongX ? i + 1 : i, alongX ? j : j + 1);
        }
    }

    /** One locked door, as the blocks it is built from (inclusive). */
    record Door(Kind kind, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        double centreX() { return (minX + maxX + 1) / 2.0; }
        double centreY() { return (minY + maxY + 1) / 2.0; }
        double centreZ() { return (minZ + maxZ + 1) / 2.0; }

        double distanceSq(double x, double y, double z) {
            double dx = centreX() - x, dy = centreY() - y, dz = centreZ() - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /**
     * A door is exactly these blocks: 3 across and 3 deep round its slot, y 69 to 72. Every
     * door opened in his two 2026-11-09 recordings cleared that block and nothing else, 36
     * blocks at once. The first version looked in a band from 64 to 78 and two blocks either
     * side, and live it boxed a door twice its height: Dragsim builds coal round its doorways
     * too, which that swallowed - and which, staying put once the door opened, would have
     * left the box up for good. Fear: "just make it highlight the coal blocks".
     */
    static final int LOW = 69, HIGH = 72;
    /** One block either side of the slot: 3 across, 3 deep. */
    private static final int REACH = 1;
    /**
     * Fewer door blocks than this is not a door. A full one is 36; one recorded opening
     * cleared 30. This keeps a coal block or two at a doorway's edge from being boxed.
     */
    static final int MIN_BLOCKS = 8;

    /** Every place a door can stand: where a gap column meets a cell's centre line. */
    static List<Slot> slots() {
        int cells = RoomGlow.CELLS, cell = RoomGlow.CELL, origin = RoomGlow.ORIGIN;
        List<Slot> out = new ArrayList<>(2 * (cells - 1) * cells);
        for (int i = 0; i < cells - 1; i++) {
            int gap = origin + cell * (i + 1) - 1;
            for (int j = 0; j < cells; j++) {
                int centre = origin + cell * j + cell / 2 - 1;
                out.add(new Slot(gap, centre, i, j, true));
                out.add(new Slot(centre, gap, j, i, false));
            }
        }
        return out;
    }

    /** The locked door at this slot, or null when there is none (or it has been opened). */
    static Door read(Blocks blocks, int x, int z) {
        // The slot's middle column says which kind of door, if any; then its blocks are counted.
        Kind kind = null;
        for (int y = LOW; y <= HIGH && kind == null; y++) kind = blocks.at(x, y, z);
        if (kind == null) return null;
        int count = 0, minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int y = LOW; y <= HIGH; y++)
            for (int bx = x - REACH; bx <= x + REACH; bx++)
                for (int bz = z - REACH; bz <= z + REACH; bz++) {
                    if (blocks.at(bx, y, bz) != kind) continue;
                    count++;
                    minX = Math.min(minX, bx); minY = Math.min(minY, y); minZ = Math.min(minZ, bz);
                    maxX = Math.max(maxX, bx); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, bz);
                }
        return count < MIN_BLOCKS ? null : new Door(kind, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Whether a block is empty to walk through; a block in a chunk not yet sent is not. */
    interface Space { boolean empty(int x, int y, int z); }

    /** The cells of the room that holds cell (i, j). */
    interface Rooms { long of(int i, int j); }

    /**
     * Whether you can see through this slot from one room into the next: empty all the way
     * through, the gap and both rooms' walls, at door height. The gap alone is empty between
     * any two rooms, door or not; a wall either side is what says there is no door.
     */
    static boolean open(Space space, Slot s) {
        for (int y = LOW; y <= HIGH; y++)
            for (int deep = -1; deep <= 1; deep++)
                if (!(s.alongX() ? space.empty(s.x() + deep, y, s.z()) : space.empty(s.x(), y, s.z() + deep))) return false;
        return true;
    }

    /**
     * The rooms you have stood in, and every room you can see into from one of them through
     * an open doorway. The dungeon map shows a room once a doorway into it is open beside
     * you - the fairy room on 2026-11-11 was on the map, and its wither door unboxed until
     * Fear walked in, when only rooms stood in counted. A locked door hides what is behind
     * it, so the room past one is not seen until it is opened.
     */
    static long seen(long visited, Space space, Rooms rooms) {
        long seen = visited;
        if (visited == 0) return 0;
        for (Slot s : slots()) {
            int oi = s.alongX() ? s.i() + 1 : s.i(), oj = s.alongX() ? s.j() : s.j() + 1;
            boolean here = RoomGlow.has(visited, s.i(), s.j()), there = RoomGlow.has(visited, oi, oj);
            if (here == there || !open(space, s)) continue;
            seen |= here ? rooms.of(oi, oj) : rooms.of(s.i(), s.j());
        }
        return seen;
    }

    /** The locked doors on the edge of these rooms. */
    static List<Door> doors(Blocks blocks, long visited) {
        List<Door> out = new ArrayList<>();
        if (visited == 0) return out;
        for (Slot s : slots()) {
            if (!s.touches(visited)) continue;
            Door d = read(blocks, s.x(), s.z());
            if (d != null) out.add(d);
        }
        return out;
    }

    /** The door a tracer leads to: the nearest, or null when there is none. */
    static Door nearest(List<Door> doors, double x, double y, double z) {
        Door best = null;
        double bestSq = Double.MAX_VALUE;
        for (Door d : doors) {
            double sq = d.distanceSq(x, y, z);
            if (sq < bestSq) { bestSq = sq; best = d; }
        }
        return best;
    }
}
