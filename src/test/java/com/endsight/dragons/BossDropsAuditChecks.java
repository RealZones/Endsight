package com.endsight.dragons;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

public final class BossDropsAuditChecks {
    private BossDropsAuditChecks() { }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("endsight-drop-audit-");
        Path logs = Files.createDirectory(temp.resolve("logs"));
        Path save = temp.resolve("boss-drops.txt");
        try {
            // A dragon before the feature existed must never appear in the comparison.
            gz(logs.resolve("2026-09-15-1.log.gz"), "0.5.0",
                    chat("12:00:00", "§5☠ §d§lThe §cOld Dragon §dhas de-spawned."));
            gz(logs.resolve("2026-10-04-1.log.gz"), "1.3.0",
                    chat("12:00:00", "§aYou placed a Summoning Eye"),
                    chat("12:00:10", "§5☠ §d§lThe §cYoung Dragon §dhas de-spawned."),
                    chat("12:00:10", "§7loot number: §f0.40035 §8→ §a§7[Lvl 1] §5Ender Dragon"),
                    chat("12:00:11", "§6EPIC DROP! §7[Lvl 1] §5Ender Dragon"),
                    chat("12:00:20", "§eHere is your loot from the last dragon:"),
                    chat("12:00:20", "§7 - §f1x §7[Lvl 1] §5Ender Dragon"),
                    chat("12:00:20", "§7 - §f3x §5Young Dragon Fragment"),
                    chat("12:00:21", "§7loot number: §f7.0 §8→ §aDragon Claw"));
            // Someone else's eyes: the awoken line reaches the whole lobby, and 1.5.0 counted it.
            gz(logs.resolve("2026-10-08-1.log.gz"), "1.5.0",
                    chat("17:38:19", "§5» Ox2u §dplaced a Summoning Eye! §7(§e8§7/§a8§7)"),
                    chat("17:38:19", "§5Your Sleeping Eyes have been awoken by the magic of the dragon."),
                    chat("17:39:46", "§5☠ §d§lThe §cBerserk Dragon §dhas de-spawned."));
            // A rollover has no mod list; it inherits the last supported startup.
            gz(logs.resolve("2026-10-04-2.log.gz"), null,
                    chat("13:00:00", "§6☠ §lNICE! SLAYER BOSS SLAIN!"));
            Files.writeString(save, "kill\tYoung Dragon\t1\nkill\tBerserk Dragon\t1\n"
                    + "drop\tYoung Dragon\t[Lvl 1] Ender Dragon (rarity unknown)\t1\n", StandardCharsets.UTF_8);

            BossDropsAudit.Report report = BossDropsAudit.scan(logs, save, "TestPlayer");
            String text = report.text();
            require(report.events() == 1, text);
            require(report.fixes().contains(new BossDropsAudit.Fix("Berserk Dragon", BossDropsAudit.KILLS, 1, 0)), text);
            require(!BossDropsAudit.awokenRule("0.6.0") && BossDropsAudit.awokenRule("0.6.1")
                    && BossDropsAudit.awokenRule("1.5.0") && !BossDropsAudit.awokenRule("1.6.0"), "awoken rule versions");
            require(text.contains("pre-feature skipped: 1"), text);
            require(text.contains("rollover version inferred: 1"), text);
            require(text.contains("unknown Slayer targets: 1"), text);
            require(text.contains("Young Dragon [Lvl 1] Ender Dragon (Epic): 1 logged; 0 saved"), text);
            require(!text.contains("Old Dragon: logged owned kills 1"), text);
            require(report.fixes().contains(new BossDropsAudit.Fix("Young Dragon", "[Lvl 1] Ender Dragon (rarity unknown)", 1, 0)), text);
            require(report.fixes().contains(new BossDropsAudit.Fix("Young Dragon", "[Lvl 1] Ender Dragon (Epic)", 0, 1)), text);

            // An Epic saved as a plain row is relabelled; a pet from before the logs is not.
            java.util.Map<String, Integer> logged = java.util.Map.of(
                    "Old Dragon\t[Lvl 1] Ender Dragon (Epic)", 1,
                    "Strong Dragon\t[Lvl 1] Tiger (Legendary)", 1,
                    "Revenant\tZombie Talisman", 9,
                    "Old Dragon\tDragon Claw", 2);
            java.util.Map<String, Integer> saved = java.util.Map.of(
                    "Old Dragon\t[Lvl 1] Ender Dragon", 1,
                    "Strong Dragon\t[Lvl 1] Tiger", 3,
                    "Revenant\tZombie Talisman", 4,
                    "Old Dragon\tDragon Claw", 5);
            // A second apply must not take the same lobby dragons off again.
            java.util.Map<String, Integer> lobby = java.util.Map.of("Berserk Dragon", 1);
            require(BossDropsAudit.fixes(java.util.Map.of(), java.util.Map.of("Berserk Dragon\t#kills", 3), lobby)
                    .equals(java.util.List.of(new BossDropsAudit.Fix("Berserk Dragon", BossDropsAudit.KILLS, 3, 2))), "lobby kills");
            require(BossDropsAudit.fixes(java.util.Map.of(), java.util.Map.of("Berserk Dragon\t#kills", 2,
                    "Berserk Dragon\t#lobby", 1), lobby).isEmpty(), "lobby kills taken twice");

            java.util.List<BossDropsAudit.Fix> fixes = BossDropsAudit.fixes(logged, saved, java.util.Map.of());
            require(fixes.equals(java.util.List.of(
                    new BossDropsAudit.Fix("Old Dragon", "[Lvl 1] Ender Dragon", 1, 0),
                    new BossDropsAudit.Fix("Old Dragon", "[Lvl 1] Ender Dragon (Epic)", 0, 1))), fixes.toString());
            System.out.println("BossDropsAuditChecks passed");

            if (args.length == 3) {
                BossDropsAudit.Report real = BossDropsAudit.scan(Path.of(args[0]), Path.of(args[1]), args[2]);
                System.out.println(real.text());
            }
        } finally {
            Files.deleteIfExists(logs.resolve("2026-09-15-1.log.gz"));
            Files.deleteIfExists(logs.resolve("2026-10-04-1.log.gz"));
            Files.deleteIfExists(logs.resolve("2026-10-04-2.log.gz"));
            Files.deleteIfExists(logs.resolve("2026-10-08-1.log.gz"));
            Files.deleteIfExists(save);
            Files.deleteIfExists(logs);
            Files.deleteIfExists(temp);
        }
    }

    private static String chat(String at, String message) {
        return "[" + at + "] [Render thread/INFO]: [System] [CHAT] " + message + "\n";
    }

    private static void gz(Path file, String version, String... lines) throws Exception {
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            if (version != null) out.write(("[00:00:00] [main/INFO]: - endsight " + version + "\n").getBytes(StandardCharsets.UTF_8));
            for (String line : lines) out.write(line.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void require(boolean condition, String report) {
        if (!condition) throw new AssertionError(report);
    }
}
