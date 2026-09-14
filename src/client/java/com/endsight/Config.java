package com.endsight;

import com.endsight.ui.Module;
import com.endsight.ui.ModuleRegistry;
import com.endsight.ui.Keybinds;
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

        for (String id : com.endsight.hud.HudLayout.ids()) {
            String ss = p.getProperty("hud." + id + ".scale");
            if (ss != null) {
                try {
                    com.endsight.hud.HudLayout.setScale(id, Float.parseFloat(ss));
                } catch (NumberFormatException ignored) {
                }
            }
            String sx = p.getProperty("hud." + id + ".x");
            String sy = p.getProperty("hud." + id + ".y");
            if (sx == null || sy == null) continue;
            try {
                com.endsight.hud.HudLayout.restore(id, Float.parseFloat(sx), Float.parseFloat(sy));
            } catch (NumberFormatException ignored) {
                // Hand-edited or from an older build: the default position stands.
            }
        }

        com.endsight.qol.CommandBinds.load(p);

        for (String name : p.stringPropertyNames()) {
            if (!name.startsWith("bind.")) continue;
            try {
                Keybinds.set(name.substring(5), Integer.parseInt(p.getProperty(name)));
            } catch (NumberFormatException ignored) {
                // Hand-edited: the action stays unbound rather than bound to nonsense.
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

        com.endsight.hud.HudLayout.scales().forEach((id, s) -> p.setProperty("hud." + id + ".scale", String.valueOf(s)));
        for (String id : com.endsight.hud.HudLayout.ids()) {
            float[] f = com.endsight.hud.HudLayout.saved(id);
            if (f == null) continue;            // never moved, so nothing to pin down
            p.setProperty("hud." + id + ".x", String.valueOf(f[0]));
            p.setProperty("hud." + id + ".y", String.valueOf(f[1]));
        }

        Keybinds.all().forEach((id, key) -> p.setProperty("bind." + id, String.valueOf(key)));
        com.endsight.qol.CommandBinds.save(p);

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
