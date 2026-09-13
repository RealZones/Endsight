package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Puts each drop on your clipboard as it lands, so it can go straight into Discord.
 *
 * The last one only. The running list of a session is the {@link DropTracker}'s job,
 * on screen, where a list belongs; a clipboard holds one thing.
 */
public final class DropClipboard {

    private DropClipboard() {
    }

    private static boolean enabled = false;
    private static String minTier = "Rare and up";

    public static Module module() {
        return new Module("qol.drops", "Copy drops",
                "Each drop goes to your clipboard as it lands.", "Chat",
                () -> enabled, v -> enabled = v,
                concat(List.of(
                        new Setting.Choice("Copy from",
                                "Lowest tier worth copying. Crazy rare and RNGesus count as legendary.",
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
            Minecraft.getInstance().keyboardHandler.setClipboard(d.item());
        });
    }
}
