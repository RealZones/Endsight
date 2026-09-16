package com.endsight.qol;

import com.endsight.hud.Alert;
import com.endsight.hud.Alerts;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;

import java.util.List;

/**
 * The mid-screen call for a drop worth stopping for.
 *
 * The old 1.8.9 alerter's whole point: a big line on screen and a ping when something
 * good lands, so you do not read it off chat ten seconds later. Which drops count is
 * the same list the tracker and the copier use - {@code config/endsight/drops.txt},
 * tiered by rarity and value together - so one edit to that file changes all three.
 * Heavier call for legendary than for epic, the way {@link Alert} weights a dragon
 * over a miniboss.
 */
public final class LootAlerts {

    private LootAlerts() {
    }

    private static boolean enabled = true;
    private static String minTier = "Epic and up";
    /** Drops inside this window of each other share one alert. */
    private static final long BURST_MS = 1_000;
    private static final List<String> burst = new java.util.ArrayList<>();
    private static long burstAt;
    private static int burstTier;
    /** The drop that is always there, so it never headlines over what came with it. */
    private static final String GIVEN = "Golden Eye";

    public static Module module() {
        return new Module("dragon.loot", "Loot Alerts",
                "Mid-screen alert and a ping for the drops you pick.", "Alerts",
                () -> enabled, v -> enabled = v,
                concat(List.of(
                        new Setting.Choice("Alert from",
                                "Lowest tier worth a call. Set in config/endsight/drops.txt.",
                                Drops.TIERS, () -> minTier, v -> minTier = v),
                        new Setting.Action("Reload tiers",
                                "Re-read config/endsight/drops.txt after editing it.",
                                "Reload", Drops::reload)),
                        Drops.tierNotes()));
    }

    private static List<Setting> concat(List<Setting> a, List<Setting> b) {
        List<Setting> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            Drops.Drop d = Drops.parse(message.getString());
            if (d == null || !Drops.passes(d, minTier)) return;
            // Drops that land together - a special zealot's Golden Eye and Warden
            // Catalyst, a boss's pair - are one moment, so they get one alert with both
            // names and one ping. Shown one after another, the second wiped the first
            // before it could be read and the pings doubled up.
            long now = System.currentTimeMillis();
            if (now - burstAt > BURST_MS) {
                burst.clear();
                burstTier = 0;
            }
            burst.add(d.item());
            burstAt = now;
            burstTier = Math.max(burstTier, d.tier());
            // A special zealot always gives a Golden Eye; the news is what came with it.
            // So the eye alerts on its own, and steps aside for a catalyst beside it.
            List<String> show = burst.size() > 1 ? burst.stream().filter(i -> !i.equalsIgnoreCase(GIVEN)).toList() : burst;
            if (show.isEmpty()) show = burst;
            String item = show.get(show.size() - 1);
            int tier = show.size() == 1 ? Math.max(Drops.tierOf(item), d.tier()) : burstTier;
            boolean top = tier >= 3;
            // Just the name, in the item's rarity colour. A word under it saying "drop"
            // told you nothing the colour did not.
            int colour = show.size() == 1 ? Rarity.colour(item, Drops.colour(tier)) : Drops.colour(tier);
            Alert.show(String.join(" + ", show), "", colour, top ? 1.4f : 1f, top ? 4 : 3);
            Minecraft mc = Minecraft.getInstance();
            if (burst.size() == 1 && Alerts.sound() && mc.player != null) {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, top ? 2f : 1.6f);
            }
        });
    }
}
