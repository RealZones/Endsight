package com.endsight.dungeon;

import com.endsight.dungeon.CreeperBeamsLogic.Pair;
import com.endsight.dungeon.CreeperBeamsLogic.Point;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CreeperBeamsChecks {
    private static int checks;

    public static void main(String[] args) {
        // Seen 2026-11-09: these two lit in the same tick, so they are a linked pair.
        Point a = block(-57, 74, -153), b = block(-59, 84, -153);
        Point middle = a.mid(b);
        // Three more lit alone, and their partners were not seen;
        // they are put back as the mirror image through the first pair's middle, which is
        // the claim being tested: one point every pair shares.
        List<Point> lanterns = new ArrayList<>(List.of(a, b));
        for (Point lone : List.of(block(-51, 81, -156), block(-51, 79, -163), block(-55, 83, -147))) {
            lanterns.add(lone);
            lanterns.add(new Point(2 * middle.x() - lone.x(), 2 * middle.y() - lone.y(), 2 * middle.z() - lone.z()));
        }
        // Wall lanterns that line up with nothing: one pair mirrored across the room at
        // floor height, and a stray.
        lanterns.add(block(-50, 72, -150));
        lanterns.add(block(-65, 72, -150));
        lanterns.add(block(-57, 70, -161));

        Set<Set<Integer>> expected = Set.of(Set.of(0, 1), Set.of(2, 3), Set.of(4, 5), Set.of(6, 7));
        check(sets(CreeperBeamsLogic.pairs(lanterns, middle)).equals(expected), "the four mirrored pairs, and no wall lantern");
        // A creeper standing with its feet on that point has its middle 0.85 higher.
        Point standing = new Point(middle.x(), middle.y() + .85, middle.z());
        check(sets(CreeperBeamsLogic.pairs(lanterns, standing)).equals(expected), "the same pairs off a creeper's box centre");

        // A lantern beside the creeper lines up with one far behind it, but the creeper
        // is at the near end of that line, not inside it.
        List<Point> lopsided = List.of(new Point(middle.x() + 1.5, middle.y(), middle.z()), new Point(middle.x() - 12, middle.y(), middle.z()));
        check(CreeperBeamsLogic.pairs(lopsided, middle).isEmpty(), "a beam that only starts at the creeper is not a pair");

        System.out.println("CreeperBeamsChecks: " + checks + " checks passed");
    }

    private static Point block(int x, int y, int z) { return new Point(x + .5, y + .5, z + .5); }

    private static Set<Set<Integer>> sets(List<Pair> pairs) {
        Set<Set<Integer>> out = new HashSet<>();
        for (Pair p : pairs) out.add(Set.of(p.a(), p.b()));
        return out;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
