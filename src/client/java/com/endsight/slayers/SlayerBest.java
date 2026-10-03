package com.endsight.slayers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Fastest confirmed boss kill for each slayer family and quest XP requirement. */
final class SlayerBest {
    private record Key(String family, int xp) { }

    private final Map<Key, Long> times = new HashMap<>();

    boolean record(String family, int xp, long ms) {
        if (family == null || family.isBlank() || xp <= 0 || ms <= 0) return false;
        Key key = new Key(family, xp);
        Long previous = times.get(key);
        if (previous != null && ms >= previous) return false;
        times.put(key, ms);
        return true;
    }

    long get(String family, int xp) {
        return times.getOrDefault(new Key(family, xp), 0L);
    }

    void load(Path path) {
        if (!Files.exists(path)) return;
        Properties saved = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            saved.load(in);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[Endsight] could not read slayer bests: " + e);
            return;
        }
        for (String name : saved.stringPropertyNames()) {
            if (!name.startsWith("best.")) continue;
            int split = name.lastIndexOf('.');
            if (split <= 5) continue;
            try {
                int xp = Integer.parseInt(name.substring(split + 1));
                long ms = Long.parseLong(saved.getProperty(name));
                record(name.substring(5, split), xp, ms);
            } catch (NumberFormatException ignored) {
                // One damaged entry must not hide the other records.
            }
        }
    }

    void save(Path path) {
        Properties saved = new Properties();
        times.forEach((key, ms) -> saved.setProperty("best." + key.family + "." + key.xp, Long.toString(ms)));
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(temp)) {
                saved.store(out, "Endsight Slayer personal bests (milliseconds)");
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[Endsight] could not save slayer bests: " + e);
        }
    }
}
