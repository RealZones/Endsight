package com.endsight.dragons;

import com.endsight.hud.Alert;
import com.endsight.hud.Alerts;
import com.endsight.hud.Readout;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Endstone Protector's climb from tier 2 to 5, as its own module.
 *
 * Split out of Dragon Timer because it is a different clock. The dragon cycle and the
 * Protector run independently - the Protector is rising while you are placing eyes, and
 * dies while a dragon is up - so folding it into the dragon readout meant a row that
 * appeared and vanished for reasons that had nothing to do with the module it was in.
 * Two clocks, two modules, each switchable on its own.
 */
public final class Protector {

    private Protector() {
    }

    private static final Pattern TIER = Pattern.compile(
            "Endstone Protector (?:is rising from|has emerged from) the ground! \\((\\d)/(\\d)\\)");
    private static final String SPAWNED = "Endstone Protector has spawned";
    private static final String DEAD = "The Endstone Protector has been defeated";

    /** The server's own colour for the Protector, so the popup matches its chat line. */
    private static final int PURPLE = 0xFFAA00AA;

    private static boolean enabled = true;
    private static String anchor = "Top left";

    private static int tier;
    private static int tierMax = 5;
    private static long since;
    private static boolean up;

    public static Module module() {
        return new Module("dragon.protector", "Protector Stage",
                "Tracks the Endstone Protector rising, tier by tier.", "Dragons",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Choice("Anchor",
                                "Where the readout sits.",
                                List.of("Top left", "Top right", "Bottom left", "Bottom right"),
                                () -> anchor, v -> anchor = v)));
    }

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            onLine(plain(message));
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "protector"),
                (g, delta) -> draw(g));
    }

    private static void onLine(String line) {
        if (DragonTimer.isPlayerChat(line)) return;

        Matcher m = TIER.matcher(line);
        if (m.find()) {
            tier = parse(m.group(1));
            tierMax = Math.max(1, parse(m.group(2)));
            since = System.currentTimeMillis();
            up = false;
            return;
        }
        if (line.contains(SPAWNED)) {
            tier = tierMax;
            up = true;
            since = System.currentTimeMillis();
            if (Alerts.protector()) {
                Alert.show("ENDSTONE PROTECTOR", "has spawned", PURPLE, 1.15f, Alerts.seconds());
            }
            return;
        }
        if (line.contains(DEAD)) {
            tier = 0;
            up = false;
        }
    }

    private static void draw(GuiGraphicsExtractor g) {
        if (!enabled || tier <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;

        Font font = mc.font;
        String label = "Protector";
        String value = up ? "up  " + secs(System.currentTimeMillis() - since)
                : tier + "/" + tierMax;

        int w = Readout.width(font, label, value);
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int x = anchor.endsWith("left") ? 6 : sw - w - 6;
        int y = anchor.startsWith("Top") ? 6 : sh - 40;

        Readout.draw(g, font, x, y, w, label, value, up, tier / (float) tierMax);
    }

    private static String secs(long ms) {
        long s = Math.max(0, ms / 1000);
        return s < 60 ? s + "s" : (s / 60) + "m" + String.format("%02d", s % 60) + "s";
    }

    private static String plain(Component c) {
        return c == null ? "" : c.getString().replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "").trim();
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
