package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * "This ability is on cooldown for 4.8s", once instead of forty times.
 *
 * A held right-click on a one-second item is refused five times a second, and every
 * refusal is a red line, so a fight's chat is mostly this. Two ways out: stack them -
 * the newest refusal replaces the last one and carries a count, so the line reads
 * "This ability is on cooldown for 4.45s  x3" and stays one line until something
 * else is said - or hide them outright.
 *
 * Stacking edits chat in place, which vanilla offers no way to do: messages can only
 * be added, or deleted by signature, and a server line has none. So the previous
 * stacked line is pulled out of the chat's own list (widened for the purpose) before
 * the new one goes in. Other modules that read the refusal - the zealot tracker
 * withdraws a click the server refused - still see it, on the canceled-message event.
 */
public final class AbilitySpam {

    private AbilitySpam() {
    }

    private static final String STACK = "Stack";
    private static final String HIDE = "Hide";
    private static final String COOLDOWN = "This ability is on cooldown";

    private static boolean enabled = true;
    private static String mode = STACK;

    /** The line currently standing in for the refusals, and how many it stands for. */
    private static Component stacked;
    private static int count;

    public static Module module() {
        return new Module("qol.abilityspam", "Ability Spam",
                "One cooldown line with a count instead of a wall of them, or none at all.", "Chat",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Choice("Mode",
                                "Stack keeps the newest refusal with a count; Hide drops them all.",
                                List.of(STACK, HIDE), () -> mode, v -> mode = v)));
    }

    public static void init() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!enabled || overlay) return true;
            if (!Zealots.strip(message.getString()).trim().startsWith(COOLDOWN)) return true;
            if (!HIDE.equals(mode)) stack(message);
            return false;
        });
    }

    /**
     * Replace the last stacked line, if it is still the newest thing in chat, with this
     * refusal and a count one higher; otherwise start a fresh line at one. "Still the
     * newest" is what keeps a refusal from being folded into one from before somebody
     * spoke, which would read as chat history being rewritten.
     */
    private static void stack(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        ChatComponent chat = mc.gui.getChat();
        List<GuiMessage> all = chat.allMessages;
        if (stacked != null && !all.isEmpty() && all.get(0).content() == stacked) {
            all.remove(0);
            chat.refreshTrimmedMessages();
            count++;
        } else {
            count = 1;
        }
        MutableComponent line = Component.empty().append(message);
        if (count > 1) line.append(Component.literal("  x" + count).withStyle(ChatFormatting.GRAY));
        stacked = line;
        chat.addServerSystemMessage(line);
    }
}
