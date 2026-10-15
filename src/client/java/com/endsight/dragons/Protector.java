package com.endsight.dragons;

import com.endsight.hud.Alert;
import com.endsight.hud.Alerts;
import com.endsight.hud.Area;
import com.endsight.hud.TrackerHud;
import com.endsight.hud.HudCard;
import com.endsight.hud.HudLayout;
import com.endsight.ui.Module;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Endstone Protector's server-wide climb, as its own module.
 *
 * Split out of Dragon Timer because it is a separate event. The dragon cycle and the
 * Protector run independently - the Protector is rising while you are placing eyes, and
 * dies while a dragon is up - so folding it into the dragon readout meant a row that
 * appeared and vanished for reasons that had nothing to do with the module it was in.
 * Two events, two modules, each switchable on its own.
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
    private static final Pattern STATUS_RISING = Pattern.compile(
            "(?i)^Protector:\\s*Rising\\s*\\(Tier\\s*(\\d+)\\s*/\\s*(\\d+)(?:\\s*,\\s*(\\d+))?\\)\\s*$");
    private static final Pattern STATUS_RESTING = Pattern.compile(
            "(?i)^Protector:\\s*Resting(?:\\s*\\((\\d+)\\))?\\s*$");
    private static final Pattern STATUS_SPAWNED = Pattern.compile(
            "(?i)^(Protector|Warden):\\s*(?:Spawned|Alive)\\s*$");
    // Tab's counter falls from 200 to zero across the whole rise. Tiers are
    // milestones in that one climb, not separate 200-kill counters.
    private static final int KILL_GOAL = 200;
    private static final String SPAWNED = "Endstone Protector has spawned";
    private static final String DEAD = "The Endstone Protector has been defeated";

    /**
     * The golem becomes the Warden when someone uses a catalyst, and the server never
     * announces the change - there is no "the Warden has spawned" line, only the Warden
     * suddenly doing things. So the transformation is inferred from its actions, and the
     * end of the fight from its own death message.
     *
     * Without the death line the readout never stopped: the Protector was gone, but
     * nothing matching "The Endstone Protector has been defeated" ever arrived, so the
     * readout stayed active forever.
     */
    private static final String WARDEN_DEAD = "The Warden has been defeated";
    private static final Pattern WARDEN_ACTIVE = Pattern.compile(
            "The Warden (?:unleashes|summons)|The Warden's aura");

    /** The server's own colour for the Protector, so the popup matches its chat line. */
    private static final int PURPLE = 0xFFAA00AA;

    private static boolean enabled = true;

    private static int tier;
    private static int tierMax = 5;
    private static boolean up;
    private static boolean warden;
    private static boolean resting;
    private static int remaining = -1;
    private static int killGoal = -1;
    private static long statusSeen;
    private static long lastTabRead;
    private static Object world;

    public static Module module() {
        return new Module("dragon.protector", "Golem Status",
                "Server-wide Protector stage and kills toward its spawn. Shows Warden transformations too.", "Trackers",
                () -> enabled, v -> enabled = v,
                List.of());
    }

    public static boolean wardenUp() {
        return enabled && up && warden;
    }

    public static boolean protectorActive() {
        return enabled && tier > 0 && !warden;
    }

    public static void init() {
        // A backend switch is a join; a golem left behind in the End is not rising here.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            onLine(plain(message));
        });
        ClientTickEvents.END_CLIENT_TICK.register(Protector::readTab);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "protector"),
                (g, delta) -> draw(g));

        // A sample makes this End-only readout possible to place from the hub too.
        HudLayout.register("dragon.protector", "Golem Status", 0.429f, 0.047f,
                (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    private static void onLine(String line) {
        if (DragonTimer.isPlayerChat(line)) return;

        Matcher m = TIER.matcher(line);
        if (m.find()) {
            tier = parse(m.group(1));
            tierMax = Math.max(1, parse(m.group(2)));
            up = false;
            warden = false;
            resting = false;
            remaining = -1;
            if (tierMax != 5) killGoal = -1;
            return;
        }
        if (line.contains(SPAWNED)) {
            tier = tierMax;
            up = true;
            warden = false;
            resting = false;
            remaining = 0;
            killGoal = tierMax == 5 ? KILL_GOAL : -1;
            statusSeen = System.currentTimeMillis();
            if (Alerts.protector()) {
                Alert.show("ENDSTONE PROTECTOR", "has spawned", PURPLE, 1.15f, Alerts.seconds());
            }
            return;
        }
        // Either death ends the fight. Checked before the action pattern so a defeat
        // cannot be mistaken for the Warden still doing something.
        if (line.contains(DEAD) || line.contains(WARDEN_DEAD)) {
            clearStatus();
            return;
        }
        if (up && WARDEN_ACTIVE.matcher(line).find()) {
            warden = true;
        }
    }

    private static void readTab(Minecraft mc) {
        if (mc.level != world) {
            reset();
            world = mc.level;
        }
        if (!enabled || mc.player == null || mc.level == null || mc.getConnection() == null) return;
        long now = System.currentTimeMillis();
        if (now - lastTabRead < 500) return;
        lastTabRead = now;

        // Read the server's sidebar and tab data even while neither overlay is open.
        // This is the lobby's progress, so personal kill attribution must never add to it.
        String sidebar = Area.sidebarLine(mc, "Protector:");
        if (sidebar != null && readTabLine(Zealots.strip(sidebar).trim(), now)) return;
        String wardenSidebar = Area.sidebarLine(mc, "Warden:");
        if (wardenSidebar != null && readTabLine(Zealots.strip(wardenSidebar).trim(), now)) return;
        List<String> lines = mc.getConnection().getListedOnlinePlayers().stream()
                .map(Protector::tabName)
                .filter(s -> !s.isBlank())
                .toList();
        for (int i = 0; i < lines.size(); i++) {
            if (readTabLine(lines.get(i), now)) return;
            if (i + 1 < lines.size() && readTabLine(lines.get(i) + " " + lines.get(i + 1), now)) return;
            if (i + 2 < lines.size() && readTabLine(lines.get(i) + " " + lines.get(i + 1) + " " + lines.get(i + 2), now)) return;
        }
    }

    private static boolean readTabLine(String line, long now) {
        Matcher m = STATUS_RISING.matcher(line);
        if (m.matches()) {
            int nextTier = parse(m.group(1)), nextMax = parse(m.group(2));
            if (nextTier < 1 || nextMax < 1 || nextTier > nextMax) return false;
            tier = nextTier;
            tierMax = nextMax;
            remaining = m.group(3) == null ? -1 : parse(m.group(3));
            killGoal = completedKills(tier, tierMax, remaining) < 0 ? -1 : KILL_GOAL;
            up = false;
            warden = false;
            resting = false;
            statusSeen = now;
            return true;
        }
        m = STATUS_RESTING.matcher(line);
        if (m.matches()) {
            tier = 0;
            remaining = m.group(1) == null ? -1 : parse(m.group(1));
            // A resting countdown has no stage to attach to. Wait for the rising row
            // rather than presenting last cycle's completed kills as this cycle's.
            killGoal = -1;
            up = false;
            warden = false;
            resting = true;
            statusSeen = now;
            return true;
        }
        m = STATUS_SPAWNED.matcher(line);
        if (m.matches()) {
            tier = tierMax;
            remaining = 0;
            killGoal = tierMax == 5 ? KILL_GOAL : -1;
            up = true;
            // Generic "Protector: Spawned" remains in tab after its transformation.
            // Do not undo the Warden's own observed action messages every half second.
            warden = warden || m.group(1).equalsIgnoreCase("Warden");
            resting = false;
            statusSeen = now;
            return true;
        }
        return false;
    }

    private static String tabName(PlayerInfo info) {
        Component c = info.getTabListDisplayName();
        if (c == null) c = Component.literal(info.getProfile().name());
        return Zealots.strip(c.getString()).trim();
    }

    private static int completedKills(int stage, int max, int left) {
        // The server's number is kills LEFT to spawn across the entire rise.
        // Tier can change while that number continues to count down. Personal
        // kills never add to this server-wide counter.
        if (max != 5 || stage < 1 || stage > max) return -1;
        if (left < 0 || left > KILL_GOAL) return stage == max && left < 0 ? KILL_GOAL : -1;
        return KILL_GOAL - left;
    }

    private static boolean counterFresh(long now) {
        return statusSeen > 0 && now - statusSeen < 5_000;
    }

    private static void draw(GuiGraphicsExtractor g) {
        if (!enabled || (tier <= 0 && !resting)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        if (!Area.end()) return;

        HudLayout.draw("dragon.protector", g, mc.font, false);
    }

    /**
     * The readout at a position, or - with a null target - just its size.
     *
     * Size has to be known before the position can be worked out, and the size depends
     * on the text. Passing null asks for the measurement without drawing anything, which
     * is the only version of this that cannot accidentally leave a copy on screen.
     */
    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        String label = !sample && warden ? "Warden Status" : "Golem Status";
        boolean alive = !sample && up;
        long now = System.currentTimeMillis();
        boolean fresh = sample || counterFresh(now);
        int completed = alive ? KILL_GOAL : completedKills(tier, tierMax, remaining);
        String counter = sample ? "169 / 200"
                : fresh && completed >= 0 && killGoal > 0 ? String.format(Locale.ROOT, "%,d / 200", completed) : "— / 200";
        List<HudCard.Row> rows = List.of(new HudCard.Row("Kills", counter, Theme.obs()));
        String status = alive ? "Alive" : !sample && resting ? "Resting"
                : (sample || tier > 0) ? "Rising " + (sample ? 4 : tier) + "/5" : "Rising";
        return TrackerHud.draw(g, font, x, y, label, status,
                alive ? Theme.pos() : Theme.obs(), rows, -1f);
    }

    private static String plain(Component c) {
        return c == null ? "" : c.getString().replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "").trim();
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void reset() {
        clearStatus();
        tierMax = 5;
        lastTabRead = 0;
        world = null;
    }

    private static void clearStatus() {
        tier = 0;
        up = false;
        warden = false;
        resting = false;
        remaining = -1;
        killGoal = -1;
        statusSeen = 0;
    }
}
