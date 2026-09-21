package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hypixel's "PRISTINE! You found ✧ Flawed Ruby Gemstone x8!" line, for a server that
 * has the stat but not the message.
 *
 * The line is fired from the inventory, since nothing in chat marks a proc. In 27
 * minutes of recorded amethyst mining Flawed came on nearly every block (3,020 gains
 * over 2,339 breaks) while Fine came 89 times - 3.8% of breaks, which is what a Pristine
 * proc rate looks like. So Fine and above announce by default and Flawed is a toggle.
 * Only while mining with nothing open, so a chest or a trade never says PRISTINE.
 */
public final class PristineAlert {

    private PristineAlert() {
    }

    private static final String[] TIERS = {"Flawed", "Fine", "Flawless", "Perfect"};
    /** Not gems, but the drops worth a line: the Void Core is 1 in 20,000 amethyst blocks. */
    private static final String[] RARE = {"Void Core", "Void Fragment"};
    private static boolean enabled = true;
    private static boolean flawed;
    private static final Map<String, Integer> last = new HashMap<>();

    public static Module module() {
        return new Module("qol.pristine", "Gem Alert",
                "PRISTINE! on Fine or better gems, RARE DROP! on Void Cores and Fragments, while mining.", "Mining",
                () -> enabled, v -> enabled = v,
                List.of(new Setting.Toggle("Flawed too", "Announce Flawed drops as well as Fine and up.",
                        () -> flawed, v -> flawed = v)));
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(PristineAlert::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        Map<String, Integer> now = new HashMap<>();
        for (ItemStack s : mc.player.getInventory().getNonEquipmentItems()) {
            if (s.isEmpty()) continue;
            String name = Zealots.strip(s.getHoverName().getString()).trim();
            for (String t : TIERS) if (name.startsWith(t + " ")) now.merge(name, s.getCount(), Integer::sum);
            for (String r : RARE) if (name.equals(r)) now.merge(name, s.getCount(), Integer::sum);
        }
        boolean mining = enabled && mc.screen == null && mc.options.keyAttack.isDown();
        if (mining && !last.isEmpty()) {
            for (Map.Entry<String, Integer> e : now.entrySet()) {
                int gained = e.getValue() - last.getOrDefault(e.getKey(), 0);
                if (gained <= 0 || gained > 16) continue;   // a stack appearing is a pickup, not a drop
                if (e.getKey().startsWith("Flawed ") && !flawed) continue;
                boolean gem = !List.of(RARE).contains(e.getKey());
                // Gems get Hypixel's line; the void drops get their own so the two never blur.
                mc.player.sendSystemMessage(Component.literal(gem ? "PRISTINE! " : "RARE DROP! ").withStyle(gem ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GOLD, ChatFormatting.BOLD)
                        .append(Component.literal("You found ").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal((gem ? "✧ " : "") + e.getKey() + " x" + gained + "!").withStyle(gem ? ChatFormatting.GREEN : ChatFormatting.LIGHT_PURPLE)));
            }
        }
        last.clear();
        last.putAll(now);
    }
}
