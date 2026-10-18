package com.endsight.slayers;

public final class SlayerReminderChecks {
    private static int checks;

    public static void main(String[] args) {
        equal(Slayer.trackerVisible(false, true, true, false), true); // Dragon's Den quest
        equal(Slayer.trackerVisible(false, true, false, false), false); // completed quest
        equal(Slayer.trackerVisible(true, false, false, true), true); // join mid-quest
        equal(Slayer.trackerVisible(false, false, true, false), false); // stale quest in hub
        equal(Slayer.questStatus(null, null, false), Slayer.QuestStatus.UNKNOWN);
        equal(Slayer.questStatus(null, null, true), Slayer.QuestStatus.NOT_STARTED);
        equal(Slayer.questStatus("§cSlayer: Not started", null, true), Slayer.QuestStatus.NOT_STARTED);
        equal(Slayer.questStatus("§cSlayer: Not started", null, false), Slayer.QuestStatus.UNKNOWN);
        equal(Slayer.questStatus("Slayer: Not started", "XP: 0/13,750", true), Slayer.QuestStatus.ACTIVE);
        equal(Slayer.questStatus(null, "XP: 0/13,750", true), Slayer.QuestStatus.ACTIVE);
        equal(Slayer.questStatus("Slayer: Atoned Horror", null, true), Slayer.QuestStatus.ACTIVE);
        equal(Slayer.voidgloomQuest("§5Slayer: Voidgloom Seraph I", "XP: 0/400", false, null), true);
        equal(Slayer.voidgloomQuest("Slayer: Voidgloom Seraph IV", null, false, null), true);
        equal(Slayer.voidgloomQuest("Slayer: Riftborn Seraph V", "XP: 1/13,750", false, null), true);
        equal(Slayer.voidgloomQuest("Slayer: Not started", null, false, "Voidgloom"), false);
        equal(Slayer.voidgloomQuest("Slayer: Revenant Horror V", "XP: 0/13,750", false, null), false);
        equal(Slayer.voidgloomQuest(null, null, true, "Voidgloom"), true);
        equal(Slayer.voidgloomQuest(null, null, false, "Voidgloom"), false);
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
