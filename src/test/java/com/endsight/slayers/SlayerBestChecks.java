package com.endsight.slayers;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SlayerBestChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        SlayerBest best = new SlayerBest();
        yes(best.record("Revenant", 13_750, 2_100));
        no(best.record("Revenant", 13_750, 2_100));
        no(best.record("Revenant", 13_750, 2_500));
        yes(best.record("Revenant", 13_750, 1_900));
        equal(best.get("Revenant", 13_750), 1_900);
        yes(best.record("Revenant", 11_000, 900));
        yes(best.record("Voidgloom", 13_750, 3_400));
        no(best.record("Revenant", 0, 100));
        no(best.record("Revenant", 13_750, 0));

        Path file = Files.createTempFile("endsight-slayer-best-", ".properties");
        try {
            best.save(file);
            SlayerBest loaded = new SlayerBest();
            loaded.load(file);
            equal(loaded.get("Revenant", 13_750), 1_900);
            equal(loaded.get("Revenant", 11_000), 900);
            equal(loaded.get("Voidgloom", 13_750), 3_400);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
        }

        equal(Slayer.requiredXp(null), 0);
        equal(Slayer.requiredXp("§7XP: §a0§7/§a13,750"), 13_750);
        equal(Slayer.requiredXp("XP: 400/11,000"), 11_000);
        System.out.println("Slayer PB checks passed: " + checks);
    }

    private static void yes(boolean value) {
        checks++;
        if (!value) throw new AssertionError("expected a new PB");
    }

    private static void no(boolean value) {
        checks++;
        if (value) throw new AssertionError("unexpected PB");
    }

    private static void equal(long actual, long expected) {
        checks++;
        if (actual != expected) throw new AssertionError("expected " + expected + ", got " + actual);
    }
}
