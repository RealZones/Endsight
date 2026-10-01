package com.endsight.storage;

import java.lang.reflect.Method;

public final class MiningVisibilityChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        equal(visible(10_000, 0, true), false);
        equal(visible(10_000, 10_000, true), true);
        equal(visible(24_999, 10_000, true), true);
        equal(visible(25_000, 10_000, true), false);
        equal(visible(310_000, 10_000, true), false);
        equal(visible(9_999, 10_000, true), false);
        // A known non-mining area wins even while the tool is held or inside its grace.
        equal(visible(10_000, 10_000, false), false);
        equal(visible(10_001, 10_000, false), false);
        equal(tool("Obsidian Drill OD-555"), true);
        equal(tool("Void-Polished Drill"), true);
        equal(tool("Diamond Pickaxe"), true);
        equal(tool("Drill Motor"), false);
        equal(tool("Giant's Sword"), false);
        equal(tool("Suspicious Vorpal Katana"), false);
        equal(tool("Refined Obsidian"), false);
        equal(tool(""), false);
        System.out.println(checks + " Mining HUD visibility checks passed");
    }

    private static boolean visible(long now, long heldAt, boolean miningArea) throws Exception {
        Method method = MiningSession.class.getDeclaredMethod("hudVisibleAt", long.class, long.class, boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, now, heldAt, miningArea);
    }

    private static boolean tool(String name) throws Exception {
        Method method = MiningSession.class.getDeclaredMethod("miningToolName", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, name);
    }

    private static void equal(boolean actual, boolean expected) {
        checks++;
        if (actual != expected) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
