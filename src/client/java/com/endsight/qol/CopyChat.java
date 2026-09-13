package com.endsight.qol;

import com.endsight.hud.Toast;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Chat to clipboard: right-click a line to copy it, and drops copy themselves.
 *
 * One module for both because they are the same idea - getting a line of chat into
 * Discord without retyping it - and the drop half used to be a module of its own,
 * which was one more card for a feature that is a single toggle.
 *
 * The click finds the line the way the game draws them: rows stack upward from forty
 * pixels above the bottom, each a line-height tall at the chat scale, plus however
 * far the chat is scrolled. That is vanilla's own layout redone in four lines, and it
 * copies the WHOLE message a row belongs to, not the wrapped fragment under the mouse.
 */
public final class CopyChat {

    private CopyChat() {
    }

    private static boolean enabled = true;
    private static boolean drops = false;
    private static String minTier = "Rare and up";

    public static Module module() {
        return new Module("qol.copychat", "Copy Chat",
                "Right-click a chat line to copy it. Drops can copy themselves.", "Chat",
                () -> enabled, v -> enabled = v,
                concat(List.of(
                        new Setting.Toggle("Drops automatically",
                                "Each drop's name goes to the clipboard as it lands.",
                                () -> drops, v -> drops = v),
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
            if (!enabled || !drops || overlay) return;
            Drops.Drop d = Drops.parse(message.getString());
            if (d == null || !Drops.passes(d, minTier)) return;
            Minecraft.getInstance().keyboardHandler.setClipboard(d.item());
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                if (!enabled || click.button() != 1) return true;
                return !copyAt(client, click.x(), click.y());
            });
        });
    }

    /** Copies the message under the mouse; false if there was none there. */
    private static boolean copyAt(Minecraft mc, double x, double y) {
        ChatComponent chat = mc.gui.getChat();
        double scale = mc.options.chatScale().get();
        int lineHeight = (int) (9.0 * (mc.options.chatLineSpacing().get() + 1.0));
        double fromBottom = mc.getWindow().getGuiScaledHeight() - 40.0 - y;
        int row = Mth.floor(fromBottom / (scale * lineHeight));
        if (row < 0 || row >= chat.getLinesPerPage()) return false;
        if (x < 0 || x > ChatComponent.getWidth(mc.options.chatWidth().get()) + 4) return false;
        int index = row + chat.chatScrollbarPos;
        List<GuiMessage.Line> lines = chat.trimmedMessages;
        if (index >= lines.size()) return false;
        String text = Zealots.strip(lines.get(index).parent().content().getString()).trim();
        if (text.isEmpty()) return false;
        mc.keyboardHandler.setClipboard(text);
        Toast.changed("Copied", text.length() > 28 ? text.substring(0, 27) + "…" : text);
        return true;
    }
}
