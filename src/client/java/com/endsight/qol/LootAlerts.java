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

    public static Module module() {
        return new Module("dragon.loot", "Loot Alerts",
                "Mid-screen alert and a ping for the drops you pick.", "Alerts",
                () -> enabled, v -> enabled = v,
                concat(List.of(
                        new Setting.Choice("Alert from",
                                "Lowest tier worth a call. Set in config/endsight/drops.txt.",
                                Drops.TIERS, () -> minTier, v -> minTier = v)),
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
            // Just the name, in the item's rarity colour. A word under it saying "drop"
            // told you nothing the colour did not.
            boolean top = d.tier() >= 3;
            Alert.show(d.item(), "", Drops.colour(d.tier()), top ? 1.4f : 1f, top ? 4 : 3);
            Minecraft mc = Minecraft.getInstance();
            if (Alerts.sound() && mc.player != null) {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, top ? 2f : 1.6f);
            }
        });
    }
}
