package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Sends {@code /debug} - or {@code /debug detailed} - for you each time you join.
 *
 * Not on the join packet itself: most servers drop a command that arrives while they
 * are still setting the player up, and this one runs behind a proxy that hands you
 * between backends, each of which is a fresh join to the client. Two seconds in, once,
 * per join. Every backend starts with the debug off (the logs show "Loot debug enabled"
 * after each "Sending you to"), but both commands are toggles, so a join that lands
 * where it was already on would switch it off; the reply says which way it went.
 */
public final class DebugOnJoin {

    private DebugOnJoin() {
    }

    private static final String NORMAL = "Normal", DETAILED = "Detailed";
    private static final int DELAY_TICKS = 40;
    /**
     * A reply this soon after our command is its answer. It used to correct any "disabled"
     * line, so turning the debug off yourself was undone a moment later.
     */
    private static final long REPLY_MS = 5_000;

    private static boolean enabled = false;
    private static String mode = NORMAL;
    private static int countdown = -1;
    private static long sentAt;
    private static boolean retried;

    public static Module module() {
        return new Module("qol.debug", "Debug on join",
                "Runs /debug after every join.", "Chat",
                () -> enabled, v -> enabled = v,
                List.of(new Setting.Choice("Mode",
                        "Normal shows the loot number. Detailed shows every roll.",
                        List.of(NORMAL, DETAILED), () -> mode, v -> mode = v)));
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> countdown = DELAY_TICKS);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (countdown < 0) return;
            if (--countdown > 0) return;
            countdown = -1;
            retried = false;
            send(mc);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay || retried || System.currentTimeMillis() - sentAt > REPLY_MS) return;
            String line = Zealots.strip(message.getString()).trim();
            String off = DETAILED.equals(mode) ? "Detailed loot debug disabled" : "Loot debug disabled";
            if (!line.startsWith(off)) return;
            // Once only: a second "disabled" would mean the toggle is not what it was.
            retried = true;
            send(Minecraft.getInstance());
        });
    }

    private static void send(Minecraft mc) {
        if (enabled && mc.player != null && mc.getConnection() != null) {
            sentAt = System.currentTimeMillis();
            mc.getConnection().sendCommand(DETAILED.equals(mode) ? "debug detailed" : "debug");
        }
    }
}
