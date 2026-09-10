package com.endsight;

import com.endsight.ui.Module;
import com.endsight.ui.ModuleRegistry;
import com.endsight.ui.Palette;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Everything you set, written to disk and read back at startup.
 *
 * Walks the registry rather than listing what to save. A module describes what it has -
 * a name, an on/off flag and a list of settings, each with a getter and a setter - and
 * that is already everything persistence needs. So this file knows nothing about
 * storage pages or dragons and never needs touching when a module is added; a new
 * setting is saved the day it is written, without anyone remembering to come here.
 *
 * The key is the module id plus the setting's label, e.g.
 * {@code module.storage.preview.set.Preview delay}. Labels rather than indices, because
 * an index silently reassigns every saved value the moment a setting is inserted in the
 * middle of a list, and the value that comes back is then wrong rather than missing -
 * which is much harder to notice.
 *
 * Deliberately java.util.Properties and not JSON: it is in the JDK, it escapes the
 * spaces and colons that setting labels contain, and this file has no business pulling
 * in a parser to store forty key-value pairs.
 */
public final class Config {

    private Config() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight.properties");
    }

    // ── load ──────────────────────────────────────────────────────────────────

    public static void load(ModuleRegistry registry) {
        Path path = file();
        if (!Files.exists(path)) return;

        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            // A corrupt config must not stop the mod loading. Defaults are always a
            // working state, so falling back to them loses preferences and nothing else.
            System.err.println("[Endsight] could not read config, using defaults: " + e);
            return;
        }

        String palette = p.getProperty("palette");
        if (palette != null) {
            for (Palette candidate : Theme.palettes()) {
                if (candidate.name().equals(palette)) {
                    Theme.set(candidate);
                    break;
                }
            }
        }

        for (Module m : registry.all()) {
            String base = "module." + m.id();

            String on = p.getProperty(base + ".enabled");
            if (on != null && m.setEnabled() != null) {
                m.setEnabled().accept(Boolean.parseBoolean(on));
            }

            for (Setting s : m.settings()) {
                String key = base + ".set." + s.label();
                String raw = p.getProperty(key);
                if (raw == null) continue;
                apply(s, raw);
            }
        }
    }

    /**
     * One saved string back into one setting.
     *
     * Every branch is guarded: a config edited by hand, or left over from a build where
     * a slider had a different range, must not throw on startup. A value that no longer
     * makes sense is dropped and the default stands.
     */
    private static void apply(Setting s, String raw) {
        try {
            if (s instanceof Setting.Toggle t) {
                t.set().accept(Boolean.parseBoolean(raw));
            } else if (s instanceof Setting.Slider sl) {
                sl.set().accept(sl.clamp(Double.parseDouble(raw)));
            } else if (s instanceof Setting.Choice c) {
                // Only accept an option that still exists - a renamed or removed choice
                // would otherwise leave the setting holding a value nothing matches.
                if (c.options().contains(raw)) c.set().accept(raw);
            }
            // Section and Action hold nothing, so there is nothing to restore.
        } catch (RuntimeException e) {
            System.err.println("[Endsight] dropping unreadable setting " + s.label() + ": " + e);
        }
    }

    // ── save ──────────────────────────────────────────────────────────────────

    public static void save(ModuleRegistry registry) {
        Properties p = new Properties();
        p.setProperty("palette", Theme.palette().name());

        for (Module m : registry.all()) {
            String base = "module." + m.id();
            p.setProperty(base + ".enabled", String.valueOf(m.isEnabled()));

            for (Setting s : m.settings()) {
                String key = base + ".set." + s.label();
                if (s instanceof Setting.Toggle t) {
                    p.setProperty(key, String.valueOf(t.get().getAsBoolean()));
                } else if (s instanceof Setting.Slider sl) {
                    p.setProperty(key, String.valueOf(sl.get().getAsDouble()));
                } else if (s instanceof Setting.Choice c) {
                    p.setProperty(key, c.get().get());
                }
            }
        }

        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                p.store(out, "Endsight - written on exit and when the menu closes");
            }
        } catch (IOException e) {
            System.err.println("[Endsight] could not write config: " + e);
        }
    }
}
