package com.endsight.hud;

import com.endsight.dragons.DragonTimer;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.regex.Pattern;

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
    private static boolean fireball = true;
    private static boolean fullInventory = true;

    /**
     * The dragon announces its fireball in chat, in character - "[BOSS] Young Dragon:
     * You are weak young padowan, you will never kill me. FIREBALL!!" - and the same
     * line for every dragon type, so the word is enough. The speaker has a space in
     * its name, which is what keeps the player-chat filter from eating it.
     */
    private static final Pattern FIREBALL = Pattern.compile("^\\[BOSS\\] .*Dragon: .*FIREBALL");
    private static final int ORANGE = 0xFFFFAA00;
    private static final int YELLOW = 0xFFFFEE55;
    /** Whether the inventory was full last tick, so the alert fires once per filling. */
    private static boolean wasFull;

    public static Module module() {
        return new Module("alerts", "Alerts",
                "Mid-screen alerts for bosses, fireballs and a full inventory.", "Alerts",
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
                        new Setting.Toggle("Dragon fireball",
                                "The dragon calling its fireball.",
                                () -> fireball, v -> fireball = v),
                        new Setting.Toggle("Full inventory",
                                "The moment the last slot fills.",
                                () -> fullInventory, v -> fullInventory = v),

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

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !enabled || !fireball) return;
            String line = Zealots.strip(message.getString()).trim();
            if (DragonTimer.isPlayerChat(line) || !FIREBALL.matcher(line).find()) return;
            fire("FIREBALL", "incoming", ORANGE, 1.15f);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null) return;
            boolean full = true;
            for (ItemStack s : mc.player.getInventory().getNonEquipmentItems()) {
                if (s.isEmpty()) {
                    full = false;
                    break;
                }
            }
            if (full && !wasFull && enabled && fullInventory) fire("INVENTORY FULL", "no room for the next drop", YELLOW, 1f);
            wasFull = full;
        });
    }

    private static void fire(String title, String sub, int colour, float weight) {
        Alert.show(title, sub, colour, weight, seconds);
        Minecraft mc = Minecraft.getInstance();
        if (sound && mc.player != null) mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1.6f);
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
