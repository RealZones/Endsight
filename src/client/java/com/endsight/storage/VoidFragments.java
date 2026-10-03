package com.endsight.storage;

import com.endsight.dragons.DragonTimer;
import com.endsight.hud.Area;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Counts announced Void Fragments in the current mining session. */
public final class VoidFragments {
    private static final Pattern DROP = Pattern.compile(
            "^[A-Z][A-Z ]*DROP!\\s*\\(?Void Fragment\\)?(?:\\s+x([\\d,]+))?(?=\\s|\\(|$)");
    private static final Pattern PET = Pattern.compile("Pet:\\s*(?:§.)*\\[Lvl \\d+\\]\\s*(§.)?(.+)");

    public static boolean show = true;
    private static long found;
    private static int tickN;

    private VoidFragments() {}

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String line = Zealots.strip(message.getString()).trim();
            if (!DragonTimer.isPlayerChat(line)) found += announced(line);
        });
    }

    static long announced(String line) {
        Matcher match = DROP.matcher(line);
        if (!match.find()) return 0;
        if (match.group(1) == null) return 1;
        try {
            return Long.parseLong(match.group(1).replace(",", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static void reset() {
        found = 0;
    }

    public static String row(boolean sample, long amethystMs) {
        long count = sample ? 3 : found;
        if (amethystMs < 5_000) return count + "  -/h";
        return count + "  " + String.format(Locale.ROOT, "%.1f/h", count * 3_600_000.0 / amethystMs);
    }

    /** Powder's pet bonus still needs the selected pet even when no meter is being read. */
    static void tick(Minecraft mc) {
        if (++tickN % 20 != 0) return;
        String raw = Area.sidebarLine(mc, "Pet:");
        if (raw == null) return;
        Matcher match = PET.matcher(raw);
        if (!match.find()) return;
        String pet = Zealots.strip(match.group(2)).trim();
        String code = match.group(1) == null ? "" : match.group(1).substring(1).toLowerCase(Locale.ROOT);
        String rarity = switch (code) {
            case "6" -> "LEGENDARY";
            case "5" -> "EPIC";
            case "9" -> "RARE";
            case "a" -> "UNCOMMON";
            case "f" -> "COMMON";
            case "d" -> "MYTHIC";
            default -> "";
        };
        PowderTracker.petChanged((rarity + " " + pet).trim());
    }
}
