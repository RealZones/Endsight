package com.endsight.qol;

import com.endsight.ui.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Sends {@code /gc} for you each time you join, so chat is global rather than the
 * instance you landed in.
 *
 * Every lobby switch puts you back in that instance's own chat, which is the wrong one
 * for anyone trading or watching for a drop announcement. Two seconds in, like
 * {@link DebugOnJoin}: a command that arrives while the backend is still setting the
 * player up is dropped, and behind a proxy every hand-off is a fresh join to the client.
 */
public final class GlobalChat {

    private GlobalChat() {
    }

    private static final String COMMAND = "gc";
    private static final int DELAY_TICKS = 40;

    private static boolean enabled = true;
    private static int countdown = -1;

    public static Module module() {
        return new Module("qol.globalchat", "Global chat on join",
                "Runs /gc after every join, so a lobby switch does not leave you in instance chat.", "Chat",
                () -> enabled, v -> enabled = v,
                List.of());
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> countdown = DELAY_TICKS);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (countdown < 0) return;
            if (--countdown > 0) return;
            countdown = -1;
            if (enabled && mc.player != null && mc.getConnection() != null) {
                mc.getConnection().sendCommand(COMMAND);
            }
        });
    }
}
