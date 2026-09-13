package com.endsight.dragons;

import com.endsight.hud.Alert;
import com.endsight.hud.Alerts;
import com.endsight.hud.HudLayout;
import com.endsight.hud.HudPlacementScreen;
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

    /**
     * The rising announcements, e.g. "(Tier 3/5)".
     *
     * The literal "Tier " matters and was dropped when this moved out of DragonTimer,
     * which silently broke every rising line: the tier stayed at zero, the readout never
     * drew, and the module looked dead right up until "has spawned" set the tier
     * directly - so it appeared only at the top of the climb, which is the one point
     * where you no longer need it.
     */
    private static final Pattern TIER = Pattern.compile(
            "Endstone Protector (?:is rising from|has emerged from) the ground! \\(Tier (\\d)/(\\d)\\)");
    private static final String SPAWNED = "Endstone Protector has spawned";
    private static final String DEAD = "The Endstone Protector has been defeated";

    /**
     * The golem becomes the Warden when someone uses a catalyst, and the server never
     * announces the change - there is no "the Warden has spawned" line, only the Warden
     * suddenly doing things. So the transformation is inferred from its actions, and the
     * end of the fight from its own death message.
     *
     * Without the death line the timer simply never stopped: the Protector was gone, but
     * nothing matching "The Endstone Protector has been defeated" ever arrived, so the
     * readout counted upward forever.
     */
    private static final String WARDEN_DEAD = "The Warden has been defeated";
    private static final Pattern WARDEN_ACTIVE = Pattern.compile(
            "The Warden (?:unleashes|summons)|The Warden's aura");

    /** The server's own colour for the Protector, so the popup matches its chat line. */
    private static final int PURPLE = 0xFFAA00AA;

    private static boolean enabled = true;

    private static int tier;
    private static int tierMax = 5;
    private static long since;
    private static boolean up;
    private static boolean warden;

    public static Module module() {
        return new Module("dragon.protector", "Protector Stage",
                "The Protector's tier and how long it has been up.", "Trackers",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Action("Move readout",
                                "Drag it, and every other readout, where you want.",
                                "Move", HudPlacementScreen::open)));
    }

    public static void init() {
        // A backend switch is a join; a golem left behind in the End is not rising here.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    tier = 0;
                    since = 0;
                    up = false;
                    warden = false;
                });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            onLine(plain(message));
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "protector"),
                (g, delta) -> draw(g));

        // Registered with a sample so it can be positioned from the hub. This readout is
        // invisible until a Protector actually starts rising, which makes it impossible
        // to place - and easy to mistake for a module that is not working.
        HudLayout.register("dragon.protector", "Protector Stage", 0.429f, 0.047f,
                (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    private static void onLine(String line) {
        if (DragonTimer.isPlayerChat(line)) return;

        Matcher m = TIER.matcher(line);
        if (m.find()) {
            tier = parse(m.group(1));
            tierMax = Math.max(1, parse(m.group(2)));
            since = System.currentTimeMillis();
            up = false;
            warden = false;
            return;
        }
        if (line.contains(SPAWNED)) {
            tier = tierMax;
            up = true;
            warden = false;
            since = System.currentTimeMillis();
            if (Alerts.protector()) {
                Alert.show("ENDSTONE PROTECTOR", "has spawned", PURPLE, 1.15f, Alerts.seconds());
            }
            return;
        }
        // Either death ends the fight. Checked before the action pattern so a defeat
        // cannot be mistaken for the Warden still doing something.
        if (line.contains(DEAD) || line.contains(WARDEN_DEAD)) {
            tier = 0;
            up = false;
            warden = false;
            return;
        }
        if (up && WARDEN_ACTIVE.matcher(line).find()) {
            warden = true;
        }
    }

    private static void draw(GuiGraphicsExtractor g) {
        if (!enabled || tier <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;

        Font font = mc.font;
        int[] size = drawAt(null, font, 0, 0, false);
        drawAt(g, font,
                HudLayout.x("dragon.protector", size[0], mc.getWindow().getGuiScaledWidth()),
                HudLayout.y("dragon.protector", size[1], mc.getWindow().getGuiScaledHeight()),
                false);
    }

    /**
     * The readout at a position, or - with a null target - just its size.
     *
     * Size has to be known before the position can be worked out, and the size depends
     * on the text. Passing null asks for the measurement without drawing anything, which
     * is the only version of this that cannot accidentally leave a copy on screen.
     */
    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        String label = !sample && warden ? "Warden" : "Protector";
        String value;
        float progress;
        if (sample) {
            value = "3/5";
            progress = 0.6f;
        } else {
            value = up ? "up  " + secs(System.currentTimeMillis() - since)
                    : tier + "/" + tierMax;
            progress = tier / (float) tierMax;
        }

        int w = Readout.width(font, label, value);
        int h = Readout.height(progress >= 0);
        if (g != null) {
            Readout.draw(g, font, x, y, w, label, value, sample || up, progress);
        }
        return new int[]{w, h};
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
