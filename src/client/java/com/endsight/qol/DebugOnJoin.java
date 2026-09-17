package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Sends {@code /debug} for you each time you join.
 *
 * Not on the join packet itself: most servers drop a command that arrives while they
 * are still setting the player up, and this one runs behind a proxy that hands you
 * between backends, each of which is a fresh join to the client. Two seconds in, once,
 * per join. If the command turns out to be a toggle the second backend would switch it
 * back off - that is not known from here, and the recordings never caught a reply.
 */
public final class DebugOnJoin {

    private DebugOnJoin() {
    }

    private static final String COMMAND = "debug";
    private static final int DELAY_TICKS = 40;

    private static boolean enabled = false;
    private static int countdown = -1;

    public static Module module() {
        return new Module("qol.debug", "Debug on join",
                "Runs /debug after every join.", "Chat",
                () -> enabled, v -> enabled = v,
                List.of());
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> countdown = DELAY_TICKS);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (countdown < 0) return;
            if (--countdown > 0) return;
            countdown = -1;
            send(mc);
        });
        // It is a toggle - "Loot debug enabled." or "Loot debug disabled." comes back -
        // so a join that lands on a backend where it was already on would switch it
        // off. Read the reply; if it went the wrong way, send once more.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            String line = Zealots.strip(message.getString());
            if (line.contains("Loot debug disabled")) send(Minecraft.getInstance());
        });
    }

    private static void send(Minecraft mc) {
        if (enabled && mc.player != null && mc.getConnection() != null) {
            mc.getConnection().sendCommand(COMMAND);
        }
    }
}
