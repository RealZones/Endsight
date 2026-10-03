package com.endsight.slayers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Last confirmed quest tier for each slayer, including quests started through the menu. */
final class SlayerTiers {
    record Selection(String family, int tier) { }

    private static final String REVENANT = "Revenant", VOIDGLOOM = "Voidgloom";
    private static final Pattern BOSS = Pattern.compile("(?i)\\b(Voidgloom Seraph|Revenant Horror)\\s+(I|II|III|IV|V)\\b");
    private static final long PENDING_MS = 30_000;

    private final Map<String, Integer> tiers = new HashMap<>();
    private Selection pending;
    private long pendingAt;

    static Selection parseCommand(String command, String area) {
        String text = command.trim();
        if (text.startsWith("/")) text = text.substring(1);
        String[] parts = text.split("\\s+");
        if (parts.length < 2 || parts.length > 3 || !parts[0].equalsIgnoreCase("slayer")) return null;
        String family = parts.length == 2 ? area : switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "zombie", "revenant" -> REVENANT;
            case "enderman", "voidgloom" -> VOIDGLOOM;
            default -> "";
        };
        if (!REVENANT.equals(family) && !VOIDGLOOM.equals(family)) return null;
        try {
            int tier = Integer.parseInt(parts[parts.length - 1]);
            return tier >= 1 && tier <= 9 ? new Selection(family, tier) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    void sent(String command, String area, long now) {
        pending = parseCommand(command, area);
        pendingAt = now;
    }

    boolean questStarted(String area, long now) {
        Selection choice = pending;
        pending = null;
        if (choice == null || now - pendingAt > PENDING_MS || now < pendingAt) return false;
        if (!area.isEmpty() && !area.equals(choice.family())) return false;
        return record(choice);
    }

    boolean observeSidebar(String line) {
        if (line == null) return false;
        String plain = line.replaceAll("§.", "").trim();
        if (plain.toLowerCase(Locale.ROOT).contains("atoned horror")) {
            return record(new Selection(REVENANT, 5));
        }
        Matcher match = BOSS.matcher(plain);
        if (!match.find()) return false;
        String family = match.group(1).equalsIgnoreCase("Voidgloom Seraph") ? VOIDGLOOM : REVENANT;
        int tier = switch (match.group(2).toUpperCase(Locale.ROOT)) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            default -> 0;
        };
        return record(new Selection(family, tier));
    }

    private boolean record(Selection choice) {
        if (choice.tier() < 1 || choice.tier() > 9) return false;
        Integer before = tiers.put(choice.family(), choice.tier());
        return before == null || before != choice.tier();
    }

    String command(String family) {
        if (REVENANT.equals(family)) return "/slayer " + tiers.getOrDefault(REVENANT, 5);
        if (VOIDGLOOM.equals(family)) return "/slayer enderman " + tiers.getOrDefault(VOIDGLOOM, 4);
        return "/slayer";
    }

    void load(Path path) {
        if (!Files.exists(path)) return;
        Properties saved = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            saved.load(in);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[Endsight] could not read slayer tiers: " + e);
            return;
        }
        for (String family : new String[]{REVENANT, VOIDGLOOM}) {
            try {
                int tier = Integer.parseInt(saved.getProperty(family, ""));
                record(new Selection(family, tier));
            } catch (NumberFormatException ignored) {
                // A bad entry should not hide the other slayer's tier.
            }
        }
    }

    void save(Path path) {
        Properties saved = new Properties();
        tiers.forEach((family, tier) -> saved.setProperty(family, Integer.toString(tier)));
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(temp)) {
                saved.store(out, "Endsight last confirmed slayer tiers");
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[Endsight] could not save slayer tiers: " + e);
        }
    }
}
