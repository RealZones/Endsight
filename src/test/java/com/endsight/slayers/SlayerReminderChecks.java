package com.endsight.slayers;

public final class SlayerReminderChecks {
    private static int checks;

    public static void main(String[] args) {
        equal(Slayer.questStatus(null, null, false), Slayer.QuestStatus.UNKNOWN);
        equal(Slayer.questStatus(null, null, true), Slayer.QuestStatus.NOT_STARTED);
        equal(Slayer.questStatus("§cSlayer: Not started", null, true), Slayer.QuestStatus.NOT_STARTED);
        equal(Slayer.questStatus("§cSlayer: Not started", null, false), Slayer.QuestStatus.UNKNOWN);
        equal(Slayer.questStatus("Slayer: Not started", "XP: 0/13,750", true), Slayer.QuestStatus.ACTIVE);
        equal(Slayer.questStatus(null, "XP: 0/13,750", true), Slayer.QuestStatus.ACTIVE);
        equal(Slayer.questStatus("Slayer: Atoned Horror", null, true), Slayer.QuestStatus.ACTIVE);
        equal(Slayer.reminderCommand("Revenant"), "/slayer 5");
        equal(Slayer.reminderCommand("Voidgloom"), "/slayer enderman 4");
        SlayerTiers tiers = new SlayerTiers();
        equal(SlayerTiers.parseCommand("slayer enderman 3", ""), new SlayerTiers.Selection("Voidgloom", 3));
        equal(SlayerTiers.parseCommand("/slayer 4", "Revenant"), new SlayerTiers.Selection("Revenant", 4));
        equal(SlayerTiers.parseCommand("slayer 4", ""), null);
        equal(SlayerTiers.parseCommand("slayer enderman nope", ""), null);
        tiers.sent("slayer enderman 3", "Voidgloom", 1_000);
        equal(tiers.questStarted("Voidgloom", 2_000), true);
        equal(tiers.command("Voidgloom"), "/slayer enderman 3");
        tiers.sent("slayer 4", "Revenant", 3_000);
        equal(tiers.questStarted("Revenant", 40_000), false);
        equal(tiers.command("Revenant"), "/slayer 5");
        equal(tiers.observeSidebar("§cVoidgloom Seraph IV"), true);
        equal(tiers.command("Voidgloom"), "/slayer enderman 4");
        equal(tiers.observeSidebar("Atoned Horror"), true);
        equal(tiers.command("Revenant"), "/slayer 5");
        try {
            java.nio.file.Path file = java.nio.file.Files.createTempFile("endsight-slayer-tiers", ".properties");
            try {
                tiers.save(file);
                SlayerTiers loaded = new SlayerTiers();
                loaded.load(file);
                equal(loaded.command("Voidgloom"), "/slayer enderman 4");
                equal(loaded.command("Revenant"), "/slayer 5");
            } finally {
                java.nio.file.Files.deleteIfExists(file);
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        System.out.println("Slayer reminder checks passed: " + checks);
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError("expected " + expected + ", got " + actual);
    }
}
