package com.endsight.dungeon;

import com.endsight.dragons.DragonTimer;
import com.endsight.hud.Area;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Measures the server's class-assignment start event to the blood-door chat line. */
final class BloodRush {
    private static final Pattern FLOOR = Pattern.compile("(?i).*Catacombs.*\\((F[1-7]|M[1-7])\\).*");
    private static final Pattern RUN = Pattern.compile("^☠ Defeated .+ in (\\d{1,2})m (\\d{1,2})s$");
    private static final Map<String, Long> BEST = new HashMap<>();
    private static final Map<String, Long> RUN_BEST = new HashMap<>();
    private static long startedAt, countdownAt;
    private static String floor;
    private static boolean rushReported, runReported;

    private BloodRush() { }

    static void init(BooleanSupplier enabled, BooleanSupplier fullRunEnabled) {
        load();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            String text = BloodRoomLogic.plain(message.getString());
            if (DragonTimer.isPlayerChat(text)) return;
            String currentFloor = floor(mc);
            if (currentFloor == null) currentFloor = floor;
            if (currentFloor == null) return;
            long now = System.nanoTime();
            if (text.equals("Dungeon starts in 1 second.")) {
                reset(); floor = currentFloor; countdownAt = now;
                return;
            }
            if (text.startsWith("You are playing as ") && text.endsWith(".")) {
                floor = currentFloor; startedAt = now;
                return;
            }
            Long fullTime = runMillis(text);
            if (fullTime != null) {
                // The server's result is authoritative even if a boss teleport
                // rejoined the client and cleared the local start timestamp.
                if (runReported || floor != null && !currentFloor.equals(floor)) return;
                runReported = true;
                if (!fullRunEnabled.getAsBoolean()) return;
                Long previous = RUN_BEST.get(currentFloor);
                boolean record = previous == null || fullTime < previous;
                if (record) { RUN_BEST.put(currentFloor, fullTime); save(); }
                mc.player.sendSystemMessage(Component.literal("§6Full Run §7- §f" + duration(fullTime)
                        + " §8(" + currentFloor + ") §7| §6PB " + duration(RUN_BEST.get(currentFloor))
                        + (record ? " §aNEW!" : "")));
                return;
            }
            if (!BloodRoomLogic.door(text) || rushReported) return;
            long start = startedAt != 0 ? startedAt : countdownAt == 0 ? 0 : countdownAt + 1_000_000_000L;
            if (start == 0 || !currentFloor.equals(floor)) { reset(); return; }
            long elapsed = (now - start) / 1_000_000L;
            rushReported = true;
            if (elapsed < 5_000 || elapsed > 30 * 60_000 || !enabled.getAsBoolean()) return;
            Long previous = BEST.get(currentFloor);
            boolean record = previous == null || elapsed < previous;
            if (record) { BEST.put(currentFloor, elapsed); save(); }
            long best = BEST.get(currentFloor);
            mc.player.sendSystemMessage(Component.literal("§cBlood Rush §7- §f"
                    + String.format("%.1fs", elapsed / 1000.0) + " §8(" + currentFloor + ") §7| §6PB "
                    + String.format("%.1fs", best / 1000.0) + (record ? " §aNEW!" : "")));
        });
    }

    static Long runMillis(String text) {
        Matcher match = RUN.matcher(text);
        if (!match.matches()) return null;
        long minutes = Long.parseLong(match.group(1)), seconds = Long.parseLong(match.group(2));
        return seconds < 60 && minutes < 30 ? (minutes * 60 + seconds) * 1000 : null;
    }

    private static String duration(long ms) {
        long seconds = ms / 1000;
        return String.format("%02dm %02ds", seconds / 60, seconds % 60);
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight/blood-rush-pb.properties");
    }

    static String floor(Minecraft mc) {
        for (String line : Area.sidebarLines(mc)) {
            Matcher match = FLOOR.matcher(line);
            if (match.matches()) return match.group(1).toUpperCase();
        }
        return null;
    }

    private static void reset() { startedAt = countdownAt = 0; floor = null; rushReported = runReported = false; }

    private static void load() {
        Path file = file();
        if (!Files.exists(file)) return;
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
            for (String key : properties.stringPropertyNames()) {
                boolean run = key.matches("run\\.[FM][1-7]");
                if (!run && !key.matches("[FM][1-7]")) continue;
                long ms = Long.parseLong(properties.getProperty(key));
                if (ms >= 5_000 && ms <= 30 * 60_000)
                    (run ? RUN_BEST : BEST).put(run ? key.substring(4) : key, ms);
            }
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("[Endsight] Could not load blood rush PBs: " + error);
        }
    }

    private static void save() {
        Path file = file();
        Properties properties = new Properties();
        BEST.forEach((key, value) -> properties.setProperty(key, Long.toString(value)));
        RUN_BEST.forEach((key, value) -> properties.setProperty("run." + key, Long.toString(value)));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) { properties.store(out, "Dungeon personal bests in milliseconds"); }
        } catch (IOException error) {
            System.err.println("[Endsight] Could not save blood rush PBs: " + error);
        }
    }
}
