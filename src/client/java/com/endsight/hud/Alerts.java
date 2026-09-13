package com.endsight.hud;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;

import java.util.List;

/**
 * Every on-screen alert, in one place.
 *
 * They started scattered - the miniboss call lived on the slayer module, the Protector
 * one on the dragon module - and that was already wrong at two: to find out what could
 * interrupt you, you had to open every module and read its settings. Alerts are a class
 * of thing in their own right, so they get a page of their own, and a new one is a line
 * in this file rather than a decision about which module it belongs to.
 *
 * The appearance settings live here for the same reason: one popup is drawn at a time,
 * so there is exactly one size and one opacity, and nobody should be able to set them
 * to two different things from two different pages.
 */
public final class Alerts {

    private Alerts() {
    }

    private static boolean enabled = true;
    private static boolean sound = true;
    private static double seconds = 3;

    private static boolean miniboss = true;
    private static boolean slayerBoss = true;
    private static boolean protector = true;

    public static Module module() {
        return new Module("alerts", "Alerts",
                "Mid-screen alerts for minibosses, slayer bosses and the Protector.", "Alerts",
                () -> enabled, v -> {
                    enabled = v;
                    if (!v) Alert.clear();          // an alert already showing goes too
                },
                List.of(
                        new Setting.Section("What to call"),
                        new Setting.Toggle("Miniboss appeared",
                                "Revenant Champion, Deformed Revenant and friends.",
                                () -> miniboss, v -> miniboss = v),
                        new Setting.Toggle("Slayer boss spawning",
                                "Your own slayer boss arriving.",
                                () -> slayerBoss, v -> slayerBoss = v),
                        new Setting.Toggle("Endstone Protector",
                                "When it finishes rising and spawns.",
                                () -> protector, v -> protector = v),

                        new Setting.Section("How they look"),
                        new Setting.Slider("Size",
                                "Share of Minecraft's own title size.",
                                40, 130, 5, Alert::scale, Alert::setScale, "%"),
                        new Setting.Slider("Opacity",
                                "How solid the text is.",
                                20, 100, 4, Alert::opacity, Alert::setOpacity, "%"),
                        new Setting.Slider("Time on screen",
                                "How long an alert stays up.",
                                1, 8, 0.5, () -> seconds, v -> seconds = v, "s"),
                        new Setting.Toggle("Play a sound",
                                "Ping when an alert fires.",
                                () -> sound, v -> sound = v)));
    }

    // ── what the modules ask ──────────────────────────────────────────────────

    public static boolean miniboss() {
        return enabled && miniboss;
    }

    public static boolean slayerBoss() {
        return enabled && slayerBoss;
    }

    public static boolean protector() {
        return enabled && protector;
    }

    public static boolean sound() {
        return sound;
    }

    public static double seconds() {
        return seconds;
    }
}
