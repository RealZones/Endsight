package com.endsight.dungeon;

import net.minecraft.world.level.material.MapColor;

import java.util.List;

public final class DungeonMapOverlayChecks {
    private static final long S = 1_000_000_000L;

    public static void main(String[] args) {
        var motion = new DungeonMapMotion();
        motion.update(List.of(at(10, 10, 0)), S);
        // A step 100 ms after the last one is spread over the next 100 ms.
        double start = motion.update(List.of(at(20, 10, 0)), S + S / 10).getFirst().x();
        check(start == 10, "a new step starts from where the marker is drawn, not where it lands");
        double half = motion.update(List.of(at(20, 10, 0)), S + S * 15 / 100).getFirst().x();
        check(Math.abs(half - 15) < 1e-6, "halfway through the step the marker is halfway there");
        double done = motion.update(List.of(at(20, 10, 0)), S + S * 3 / 10).getFirst().x();
        check(done == 20, "the marker reaches the server's position and stops");
        double teleported = motion.update(List.of(at(80, 80, 0)), S + S * 4 / 10).getFirst().x();
        check(teleported == 80, "large map teleports should snap immediately");

        var turning = new DungeonMapMotion();
        turning.update(List.of(at(40, 40, 350)), S);
        double angle = turning.update(List.of(at(40, 40, 10)), S + S / 5).getFirst().angle();
        double middle = turning.update(List.of(at(40, 40, 10)), S + S * 3 / 10).getFirst().angle();
        check(angle == 350 && Math.abs(middle - 360) < 1e-6, "350 to 10 degrees turns forward through 360, not back");
        byte[] map = new byte[128 * 128];
        paint(map, 6, 6, MapColor.COLOR_BROWN);
        paint(map, 26, 6, MapColor.COLOR_PURPLE);
        paint(map, 46, 6, MapColor.COLOR_ORANGE);
        for (int i = 0; i < 4; i++) map[(12 + i) * 128 + 32 + i] = MapColor.SNOW.getPackedId(MapColor.Brightness.HIGH);
        check(new DungeonRooms.Room(26, 6, 16, 16).equals(DungeonRooms.find(map, 33.5, 13.5, DungeonRooms.Kind.PUZZLE)),
                "the puzzle room is its whole box, tick drawn inside or not");
        check(new DungeonRooms.Room(26, 6, 16, 16).equals(DungeonRooms.find(map, 22.5, 13.5, DungeonRooms.Kind.PUZZLE)),
                "announced in the doorway, the purple room next to you is the one named");
        check(DungeonRooms.find(map, 10.5, 13.5, DungeonRooms.Kind.PUZZLE) == null, "a puzzle out of reach is not guessed");
        check(new DungeonRooms.Room(46, 6, 16, 16).equals(DungeonRooms.find(map, 50.5, 10.5, DungeonRooms.Kind.TRAP)),
                "the trap is the orange room");
        check(DungeonRooms.find(map, 33.5, 13.5, DungeonRooms.Kind.TRAP) == null, "a purple room is not a trap");
        check(DungeonRooms.lines("Three Weirdos").equals(List.of("Three", "Weirdos"))
                && DungeonRooms.lines("Higher Or Lower").equals(List.of("Higher", "Or Lower"))
                && DungeonRooms.lines("Quiz").equals(List.of("Quiz")), "names split in two like the reference, one word stays whole");
        check(DungeonRooms.rot(180) == 8 && DungeonRooms.rot(0) == 0 && DungeonRooms.rot(-90) == 12 && DungeonRooms.rot(540) == 8,
                "facing matches the map's sixteenths, whatever the yaw has wound up to");
        System.out.println("12 dungeon map overlay checks passed");
    }

    private static DungeonMapMotion.Target at(double x, double y, double angle) {
        return new DungeonMapMotion.Target("Player", false, x, y, angle);
    }

    private static void paint(byte[] map, int x0, int y0, MapColor colour) {
        for (int y = y0; y < y0 + 16; y++) for (int x = x0; x < x0 + 16; x++) map[y * 128 + x] = colour.getPackedId(MapColor.Brightness.NORMAL);
    }

    private static void check(boolean good, String reason) { if (!good) throw new AssertionError(reason); }
}
