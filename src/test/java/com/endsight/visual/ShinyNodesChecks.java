package com.endsight.visual;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Packet face coordinates must resolve to the adjacent solid block, including negatives. */
public final class ShinyNodesChecks {
    private static int checks;

    public static void main(String[] args) {
        node(10.5, 65.25, -4.5, 10, 64, -5, Direction.UP);
        node(10.5, 63.75, -4.5, 10, 64, -5, Direction.DOWN);
        node(11.25, 64.5, -4.5, 10, 64, -5, Direction.EAST);
        node(9.75, 64.5, -4.5, 10, 64, -5, Direction.WEST);
        node(10.5, 64.5, -3.75, 10, 64, -5, Direction.SOUTH);
        node(10.5, 64.5, -5.25, 10, 64, -5, Direction.NORTH);
        node(10.37, 65.25, -4.82, 10, 64, -5, Direction.UP);
        if (ShinyNodes.candidate(10.33, 64.41, -4.59) != null) throw new AssertionError("unrelated particle mapped to a node");
        checks++;
        // Captured from the two screenshot locations: the server places the burst
        // above sandstone/black concrete, which the resource pack retextures.
        burst(-544.5, 2.1, -250.5, -545, 1, -251);
        burst(-566.5, 40.1, -209.5, -567, 39, -210);
        if (ShinyNodes.burstCandidate(-544.5, 2.1, -250.5, 1, .35, .25, .35, 0) != null)
            throw new AssertionError("single unrelated portal particle accepted");
        checks++;
        if (ShinyNodes.burstCandidate(-544.5, 2.25, -250.5, 8, .35, .25, .35, 0) != null)
            throw new AssertionError("old face-particle signature confused with burst");
        checks++;
        System.out.println(checks + " shiny node checks passed");
    }

    private static void burst(double x, double y, double z, int bx, int by, int bz) {
        var found = ShinyNodes.burstCandidate(x, y, z, 8, .35, .25, .35, 0);
        if (found == null || !found.pos().equals(new BlockPos(bx, by, bz)))
            throw new AssertionError("wrong burst node for " + x + "," + y + "," + z + ": " + found);
        checks++;
    }

    private static void node(double x, double y, double z, int bx, int by, int bz, Direction face) {
        var found = ShinyNodes.candidate(x, y, z);
        if (found == null || !found.pos().equals(new BlockPos(bx, by, bz)) || found.face() != face)
            throw new AssertionError("wrong node for " + x + "," + y + "," + z + ": " + found);
        checks++;
    }
}
