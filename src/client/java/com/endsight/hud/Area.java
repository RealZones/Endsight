package com.endsight.hud;

import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Which backend you are on, read from the proxy's own line as it moves you.
 *
 * "Moving you to Crypts #2..." / "Sending you to Dragon's Den #1..." arrive on every
 * switch, and "Warping to crypts…" / "Teleporting to the End…" just before. The End
 * readouts - dragon, protector, zealots - are noise in the Crypts, where a slayer
 * fight is the only thing happening, so they ask here before drawing.
 */
public final class Area {

    private Area() {
    }

    private static boolean crypts;

    public static boolean crypts() {
        return crypts;
    }

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String line = Zealots.strip(message.getString()).trim().toLowerCase();
            if (line.startsWith("moving you to") || line.startsWith("sending you to")
                    || line.startsWith("warping to") || line.startsWith("teleporting to")) {
                crypts = line.contains("crypt");
            }
        });
    }
}
