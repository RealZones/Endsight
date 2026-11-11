package com.endsight.dungeon;

import com.endsight.dungeon.DoorHighlightLogic.Door;
import com.endsight.dungeon.DoorHighlightLogic.Kind;
import com.endsight.dungeon.DoorHighlightLogic.Slot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Door Highlight on the dungeon grid: doors built where the measured run had them, a wither
 * door between cells (0,0) and (1,0), the blood door between (1,0) and (1,1), a wither door
 * far off between (4,4) and (5,4), and coal in a wall by a doorway that is not a door.
 */
public final class DoorHighlightChecks {
    private static int checks;
    private static final Map<Long, Kind> world = new HashMap<>();
    private static final DoorHighlightLogic.Blocks BLOCKS = (x, y, z) -> world.get(key(x, y, z));

    public static void main(String[] args) {
        List<Slot> slots = DoorHighlightLogic.slots();
        check(slots.size() == 60, "a 6x6 grid has 60 places for a door, not " + slots.size());
        Slot first = slots.getFirst();
        check(first.x() == -169 && first.z() == -185 && first.alongX(), "the first sits on the gap after cell 0, on its centre line");
        check(slots.stream().anyMatch(s -> s.x() == -185 && s.z() == -169 && !s.alongX()), "and its twin across the other axis");

        // A wither door between (0,0) and (1,0): 3 wide, 4 high, 3 deep, on the gap column -
        // with coal round its doorway as well, two rows over it and a column either side, the
        // way Dragsim builds them (the first, looser read boxed all of it: twice the door).
        door(-169, -185, true, Kind.WITHER);
        for (int y = 73; y <= 74; y++) for (int deep = -1; deep <= 1; deep++) for (int across = -2; across <= 2; across++)
            world.put(key(-169 + deep, y, -185 + across), Kind.WITHER);
        for (int y = 69; y <= 72; y++) for (int deep = -1; deep <= 1; deep++) {
            world.put(key(-169 + deep, y, -187), Kind.WITHER);
            world.put(key(-169 + deep, y, -183), Kind.WITHER);
        }
        // The blood door between (1,0) and (1,1).
        door(-153, -169, false, Kind.BLOOD);
        // A wither door far off, between (4,4) and (5,4).
        door(-41, -57, true, Kind.WITHER);
        // Two coal blocks in the middle of the doorway between (0,1) and (1,1): decoration.
        world.put(key(-169, 70, -153), Kind.WITHER);
        world.put(key(-169, 71, -153), Kind.WITHER);

        Door wither = DoorHighlightLogic.read(BLOCKS, -169, -185);
        check(wither != null && wither.kind() == Kind.WITHER, "a wither door is read as one");
        check(wither.minX() == -170 && wither.maxX() == -168 && wither.minZ() == -186 && wither.maxZ() == -184
                && wither.minY() == 69 && wither.maxY() == 72, "and boxed to its own blocks, not the coal round it: " + wither);
        check(wither.centreX() == -168.5 && wither.centreY() == 71 && wither.centreZ() == -184.5, "its middle is the box's middle");
        Door blood = DoorHighlightLogic.read(BLOCKS, -153, -169);
        check(blood != null && blood.kind() == Kind.BLOOD, "the blood door is read by its terracotta");
        check(DoorHighlightLogic.read(BLOCKS, -169, -153) == null, "two coal blocks are not a door");
        check(DoorHighlightLogic.read(BLOCKS, -137, -185) == null, "an empty doorway is not a door");

        // Only doors on the edge of a room you have been in.
        long none = 0, start = bit(0, 0), both = bit(0, 0) | bit(1, 0);
        check(DoorHighlightLogic.doors(BLOCKS, none).isEmpty(), "no rooms visited, no doors shown");
        List<Door> fromStart = DoorHighlightLogic.doors(BLOCKS, start);
        check(fromStart.size() == 1 && fromStart.getFirst().kind() == Kind.WITHER, "from the start room only its own door: " + fromStart);
        List<Door> later = DoorHighlightLogic.doors(BLOCKS, both);
        check(later.size() == 2 && later.stream().anyMatch(d -> d.kind() == Kind.BLOOD),
                "once in (1,0) its blood door shows too: " + later);
        check(later.stream().noneMatch(d -> d.minX() > -100), "the far door stays hidden until its room is reached");
        long far = both | bit(4, 4);
        check(DoorHighlightLogic.doors(BLOCKS, far).size() == 3, "and shows once it is");
        check(DoorHighlightLogic.doors(BLOCKS, bit(5, 4)).size() == 1, "from either side of it");

        // Seen through an open doorway: the room past it shows its doors before you walk in.
        door(-185, -137, false, Kind.WITHER);                     // between (0,1) and (0,2)
        java.util.Set<Long> air = new java.util.HashSet<>();
        for (int y = 69; y <= 72; y++) for (int deep = -1; deep <= 1; deep++) air.add(key(-185, y, -169 + deep));
        DoorHighlightLogic.Space doorway = (x, y, z) -> air.contains(key(x, y, z));
        DoorHighlightLogic.Rooms single = (i, j) -> bit(i, j);
        long seen = DoorHighlightLogic.seen(start, doorway, single);
        check(seen == (start | bit(0, 1)), "the room past the open doorway (0,0)-(0,1) is seen, and only it");
        check(DoorHighlightLogic.doors(BLOCKS, seen).size() == 2, "so its far wither door shows from the start room");
        // Only the gap is empty where there is no doorway: the walls either side are not.
        java.util.Set<Long> gapOnly = new java.util.HashSet<>();
        for (int y = 69; y <= 72; y++) gapOnly.add(key(-185, y, -169));
        check(DoorHighlightLogic.seen(start, (x, y, z) -> gapOnly.contains(key(x, y, z)), single) == start,
                "an empty gap between two walls is not a doorway");
        check(DoorHighlightLogic.seen(start, (x, y, z) -> world.get(key(x, y, z)) == null, single) == (start | bit(0, 1)),
                "a locked door is not seen through, an open doorway is");
        check(DoorHighlightLogic.seen(0, doorway, single) == 0, "nothing visited, nothing seen");

        // The tracer leads to the nearest.
        Door near = DoorHighlightLogic.nearest(later, -160, 70, -160);
        check(near != null && near.kind() == Kind.BLOOD, "the nearest door from (1,0)'s corner is the blood door");
        check(DoorHighlightLogic.nearest(List.of(), 0, 0, 0) == null, "no doors, no tracer");

        // Opened: the blocks go, and so does the box.
        door(-169, -185, true, null);
        check(DoorHighlightLogic.read(BLOCKS, -169, -185) == null, "an opened door is no longer boxed");
        check(DoorHighlightLogic.doors(BLOCKS, both).size() == 1, "leaving only the blood door, though its doorway's coal stays");

        System.out.println(checks + " Door Highlight checks passed");
    }

    /** 3 wide across the doorway, 4 high from 69, 3 deep through the gap; null clears it. */
    private static void door(int x, int z, boolean alongX, Kind kind) {
        for (int y = 69; y <= 72; y++)
            for (int across = -1; across <= 1; across++)
                for (int deep = -1; deep <= 1; deep++) {
                    long k = alongX ? key(x + deep, y, z + across) : key(x + across, y, z + deep);
                    if (kind == null) world.remove(k); else world.put(k, kind);
                }
    }

    private static long bit(int i, int j) {
        return 1L << (i * RoomGlow.CELLS + j);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 1024) << 32) | ((long) (y + 1024) << 16) | (z + 1024);
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }
}
