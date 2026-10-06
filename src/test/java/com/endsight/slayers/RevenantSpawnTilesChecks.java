package com.endsight.slayers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.List;

public final class RevenantSpawnTilesChecks {
    private static int checks;

    public static void main(String[] args) {
        same(null, RevenantSpawnTiles.parse("# local sightings"), "comment");
        same(null, RevenantSpawnTiles.parse(""), "blank");
        same(null, RevenantSpawnTiles.parse("1 2 3"), "missing count");
        same(null, RevenantSpawnTiles.parse("1 2 3 0"), "zero sightings");
        same(null, RevenantSpawnTiles.parse("1 2 3 not-a-number"), "bad count");
        same(null, RevenantSpawnTiles.parse("1 2 3 1000001"), "unbounded count");
        same(new RevenantSpawnTiles.Tile(new BlockPos(70, 37, -19), 36),
                RevenantSpawnTiles.parse("70 37 -19 36"), "recorded tile");
        same(new RevenantSpawnTiles.Tile(new BlockPos(-2, -4, 5), 2),
                RevenantSpawnTiles.parse(" -2   -4  5  2 "), "signed coordinates");
        BlockPos known = new BlockPos(70, 37, -19);
        same(known, RevenantSpawnTiles.burstTile(new Vec3(70.9, 38, -18.8), Set.of(known)),
                "burst snaps to the recorded ground block");
        same(new BlockPos(12, 63, -4),
                RevenantSpawnTiles.burstTile(new Vec3(12.3, 64, -3.9), Set.of(known)),
                "new burst uses the block below the ground position");
        BlockPos other = new BlockPos(75, 37, -19);
        same(known, RevenantSpawnTiles.nearestVisibleTile(List.of(
                new RevenantSpawnTiles.VisibleTile(other, 1, 0),
                new RevenantSpawnTiles.VisibleTile(known, 1, 0)), new Vec3(70, 38, -19), null),
                "nearest highlighted tile");
        same(other, RevenantSpawnTiles.nearestVisibleTile(List.of(
                new RevenantSpawnTiles.VisibleTile(other, 1, 0),
                new RevenantSpawnTiles.VisibleTile(known, 1, 0)), new Vec3(70, 38, -19), known),
                "just-killed boss tile is skipped briefly");
        same(new RevenantSpawnTiles.TraceTarget(known, 1),
                RevenantSpawnTiles.chooseTracer(null, null, 0, false, known),
                "constant tracer between bosses");
        same(new RevenantSpawnTiles.TraceTarget(other, 1),
                RevenantSpawnTiles.chooseTracer(other, null, 0, false, known),
                "burst tile takes over");
        same(new RevenantSpawnTiles.TraceTarget(other, .5),
                RevenantSpawnTiles.chooseTracer(null, other, .5, true, known),
                "boss birth tile fades");
        same(null, RevenantSpawnTiles.chooseTracer(null, other, 0, true, known),
                "tracer stays faded while boss is alive");
        same(new RevenantSpawnTiles.TraceTarget(known, 1),
                RevenantSpawnTiles.chooseTracer(null, null, 0, false, known),
                "nearest tracer returns after boss");

        // The bundled list is what a new player sees: every line must parse, and every
        // tile in it must already count as confirmed, or it ships invisible.
        int bundled = 0;
        try (var in = RevenantSpawnTiles.class.getResourceAsStream("/endsight-revenant-tiles.txt")) {
            same(true, in != null, "bundled tiles are in the jar");
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                RevenantSpawnTiles.Tile tile = RevenantSpawnTiles.parse(line);
                same(true, tile != null && tile.sightings() >= 2, "bundled tile shows: " + line);
                bundled++;
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        same(21, bundled, "all 21 mapped tiles ship");
        System.out.println("Revenant spawn tile checks passed: " + checks);
    }

    private static void same(Object expected, Object actual, String label) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
