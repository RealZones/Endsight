package com.endsight.social;

public final class DiscordPresenceChecks {
    public static void main(String[] args) {
        equal(DiscordPresence.purse("§6Purse: §e12,345,678"), "12,345,678");
        equal(DiscordPresence.purse("Purse: 2.5m"), "2.5m");
        equal(DiscordPresence.purse("Bank: 42,000"), null);
        equal(DiscordPresence.purse(null), null);
        equal(DiscordPresence.details("ImFear"), "Playing as ImFear");
        equal(DiscordPresence.details(null), "Playing Dragsim");
        equal(DiscordPresence.context("Dragon's Den", "19,382,146,098"), "Dragon's Den • Purse: 19,382,146,098");
        equal(DiscordPresence.context(null, "19,382,146,098"), "Purse: 19,382,146,098");
        equal(DiscordPresence.context("Hub", null), "In Hub");
        equal(DiscordPresence.context(null, null), "In game");
        System.out.println("Discord Presence parsing and privacy checks passed");
    }

    private static void equal(Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected))
            throw new AssertionError("Expected " + expected + " but got " + actual);
    }
}
