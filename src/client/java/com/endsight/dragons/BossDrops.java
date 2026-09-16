package com.endsight.dragons;

import com.endsight.hud.HudLayout;
import com.endsight.hud.Readout;
import com.endsight.qol.Drops;
import com.endsight.qol.Rarity;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kills and drops from every boss - dragons, the Warden, the Protector, and the two
 * slayers - on ONE readout that shows whichever boss you killed last, the drops in
 * rarity order and in their own rarity's colour.
 *
 * One readout and not five, because five was a wall: the thing on screen should be
 * the thing you are doing, and the last boss to die is exactly that. Every boss keeps
 * its own count underneath, all the time; /drops <boss> prints any of them.
 *
 * <h2>Whose kill it was</h2>
 * A boss dying in the lobby is not a boss you killed. The first version counted every
 * "de-spawned" and every "defeated" - kills you were not even near. What makes a
 * kill yours is what makes its loot yours: a dragon pays the players who put an eye
 * in, so a dragon is yours if you placed one ("You placed a Summoning Eye"); the
 * Protector and the Warden pay their top three damagers, so they are yours if the
 * summary box the server prints puts you at #1, #2 or #3 - and the Warden pays whoever
 * placed its catalyst regardless, so a Warden Catalyst leaving your inventory just
 * before "the ground trembles" makes that Warden yours at any place. A slayer boss is
 * always yours; nobody else's quest says "SLAYER BOSS SLAIN". And a boss that gave you
 * a drop is yours whatever else was seen, because the drop is the proof.
 *
 * <h2>Reading the death</h2>
 * The kill line, the summary box with your place in it, and the drops all land inside
 * a second - but in a different order for each boss: the Warden's box and drops come
 * before "defeated", the Protector's after, a slayer's loot line just before "SLAIN",
 * a dragon's "has obtained" announcements seconds later by name. So a death is held
 * open for a moment and settled once everything about it has arrived. What zealots
 * drop is never a boss's, whatever the timing says - a nest is always being farmed
 * beside the fight.
 *
 * A session and an all-time count; the chip on the title flips between them with
 * chat open. Everything is counted; the tier list decides what is worth a row.
 */
public final class BossDrops {

    private BossDrops() {
    }

    static final String DRAGON = "Dragon", WARDEN = "Warden", GOLEM = "Golem", ZOMBIE = "Zombie", ENDERMAN = "Enderman";
    private static final List<String> BOSSES = List.of(DRAGON, WARDEN, GOLEM, ZOMBIE, ENDERMAN);
    private static final Pattern DRAGON_DEAD = Pattern.compile("^☠ The (.+?) Dragon has de-spawned");
    private static final Pattern OBTAINED = Pattern.compile("^(?:\\[[^\\]]+\\] )?(\\S+?)(?: \\S)? has obtained (.+?)!$");
    private static final Pattern TARGET = Pattern.compile("Slay [\\d,]+ Combat XP worth of (\\w+)");
    /** The summary box: one message of many lines, "THE WARDEN DOWN!" and your place near the end. */
    private static final Pattern BOX = Pattern.compile(
            "(THE WARDEN|ENDSTONE PROTECTOR|(?:[A-Z]+ )?DRAGON) DOWN!.*?Your Damage: [\\d,.]+[KMB]? \\(Position #(\\d+)\\)", Pattern.DOTALL);
    private static final String EYE_PLACED = "You placed a Summoning Eye";
    private static final String EGG_SPAWNED = "Egg has Spawned";
    private static final String WARDEN_DEAD = "The Warden has been defeated";
    private static final String GOLEM_DEAD = "The Endstone Protector has been defeated";
    private static final String SLAYER_DEAD = "SLAYER BOSS SLAIN";
    private static final String TREMBLE = "The ground trembles with ancient sculk energy";
    private static final String CATALYST = "Warden Catalyst";
    /** The top three get the Protector's and the Warden's loot. */
    private static final int PLACES = 3;
    /** How long a death stays open for its box and its drops. Everything lands inside a second. */
    private static final long HOLD_MS = 2_500;
    /** A drop line this far ahead of the kill line belongs to it. */
    private static final long BEFORE_MS = 2_000;
    /** A catalyst leaving your hands this long before the tremble was you placing it. */
    private static final long CATALYST_MS = 15_000;
    /** What zealots and the golden ones drop: never a boss's, whatever the timing says. */
    private static final Set<String> NEST = Set.of("summoning eye", "enderman", "null ovoid", "ender pearl",
            "spicy wart", "warped stone", "infinieye", "zealot talisman", "warden catalyst");
    private static final String SESSION = "Session";
    private static final String TOTAL = "Total";

    private static boolean enabled = true;
    private static String mode = SESSION;
    /** Which drops make the list - everything is counted, this is only what is shown. */
    private static String minTier = "Rare and up";

    /** One boss: kills, and drops by item. */
    private static final class Count {
        int kills;
        final Map<String, Integer> drops = new LinkedHashMap<>();
    }

    /** One boss dying, held open until its box and its drops have arrived. */
    private static final class Death {
        final String boss;
        final long at;
        int place = 99;
        boolean dead;
        final List<Drops.Drop> drops = new ArrayList<>();

        Death(String boss, long at) {
            this.boss = boss;
            this.at = at;
        }
    }

    private static final Map<String, Count> session = new LinkedHashMap<>(), total = new LinkedHashMap<>();
    /**
     * The tier each item was announced at, for anything your list does not name: the
     * server's own word is the only rarity a new item has.
     */
    private static final Map<String, Integer> seen = new LinkedHashMap<>();
    /** The boss on the readout: the last one killed. */
    private static String showing = DRAGON;
    private static String slayer = ZOMBIE;
    private static Death death;
    /** The last drop line, for a kill line that arrives just after it. */
    private static Drops.Drop held;
    private static long heldAt;
    private static int eyes;
    private static boolean catalyst;
    private static long catalystAt;
    private static int catalysts = -1;

    private static Count of(Map<String, Count> m, String boss) {
        return m.computeIfAbsent(boss, k -> new Count());
    }

    public static Module module() {
        return new Module("dragon.bossdrops", "Boss Drops",
                "Kills and drops from whichever boss you killed last - dragons, Warden, Protector, slayers - sorted by rarity.", "Trackers",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Choice("Show from",
                                "Lowest tier worth a row, by your list in config/endsight/drops.txt. Everything is still counted.",
                                Drops.TIERS, () -> minTier, v -> minTier = v)));
    }

    public static void init() {
        load();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            String raw = message.getString();
            String line = Zealots.strip(raw).trim();
            if (DragonTimer.isPlayerChat(line)) return;
            long now = System.currentTimeMillis();

            Drops.Drop d = Drops.parse(raw);
            if (d != null) {
                if (!NEST.contains(d.item().toLowerCase(Locale.ROOT))) {
                    if (death != null) death.drops.add(d);
                    else {
                        held = d;
                        heldAt = now;
                    }
                }
                return;
            }
            Matcher m = TARGET.matcher(line);
            if (m.find()) {
                slayer = m.group(1).toLowerCase(Locale.ROOT).startsWith("ender") ? ENDERMAN : ZOMBIE;
                return;
            }
            if (line.contains(EYE_PLACED)) {
                eyes++;
                return;
            }
            if (line.contains(EGG_SPAWNED)) {
                eyes = 0;
                return;
            }
            if (line.contains(TREMBLE)) {
                catalyst = now - catalystAt < CATALYST_MS;
                return;
            }
            if (DRAGON_DEAD.matcher(line).find()) {
                // Yours if you put an eye in. Its drops are announced by name, seconds
                // later, so there is nothing to hold the death open for.
                if (eyes > 0) {
                    open(DRAGON, now, false).place = 1;
                    settle();
                }
                eyes = 0;
                return;
            }
            m = BOX.matcher(line);
            if (m.find()) {
                String who = m.group(1);
                String boss = who.startsWith("THE WARDEN") ? WARDEN : who.startsWith("ENDSTONE") ? GOLEM : DRAGON;
                if (!DRAGON.equals(boss)) open(boss, now, true).place = Integer.parseInt(m.group(2));
                return;
            }
            if (line.contains(WARDEN_DEAD)) {
                open(WARDEN, now, true).dead = true;
                return;
            }
            if (line.contains(GOLEM_DEAD)) {
                open(GOLEM, now, true).dead = true;
                return;
            }
            if (line.contains(SLAYER_DEAD)) {
                Death x = open(slayer, now, true);
                x.dead = true;
                x.place = 1;
                return;
            }
            m = OBTAINED.matcher(line);
            if (m.find()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && m.group(1).equalsIgnoreCase(mc.player.getName().getString())) {
                    String item = m.group(2).trim();
                    drop(DRAGON, new Drops.Drop(item, Drops.tierOf(item)));
                }
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long now = System.currentTimeMillis();
            if (death != null && now - death.at > HOLD_MS) settle();
            if (mc.player == null) {
                catalysts = -1;
                return;
            }
            // Placing the catalyst is not announced, so it is read off your inventory:
            // one Warden Catalyst gone with no window open is you placing it. A stack
            // sold or traded goes through a window; a lobby change empties everything.
            int n = 0;
            for (ItemStack s : mc.player.getInventory().getNonEquipmentItems()) {
                if (!s.isEmpty() && s.getHoverName().getString().contains(CATALYST)) n += s.getCount();
            }
            if (catalysts >= 0 && n == catalysts - 1 && mc.screen == null) catalystAt = now;
            catalysts = n;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> {
            var root = ClientCommands.literal("drops").executes(c -> {
                for (String boss : BOSSES) if (of(TOTAL.equals(mode) ? total : session, boss).kills > 0) say(boss);
                return 1;
            });
            for (String boss : BOSSES) {
                root.then(ClientCommands.literal(boss.toLowerCase(Locale.ROOT)).executes(c -> {
                    say(boss);
                    return 1;
                }));
            }
            dispatcher.register(root);
        });
        // The chip is clickable whenever the mouse is free over the world - chat open.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                int[] b = HudLayout.bounds("drops.boss");
                if (b == null) return true;
                if (click.x() >= b[0] && click.x() < b[0] + b[2] && click.y() >= b[1] && click.y() < b[1] + Readout.ROW_H + 2) {
                    mode = TOTAL.equals(mode) ? SESSION : TOTAL;
                    return false;
                }
                return true;
            });
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "bossdrops"), (g, delta) -> draw(g));
        HudLayout.register("drops.boss", "Boss Drops", 0.994f, 0.35f, (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    /**
     * The death being read, opened now if there is none. A different boss still open
     * is settled first. {@code takeHeld}: a drop line just before this one was its.
     */
    private static Death open(String boss, long now, boolean takeHeld) {
        if (death != null && !death.boss.equals(boss)) settle();
        if (death == null) {
            death = new Death(boss, now);
            if (takeHeld && held != null && now - heldAt < BEFORE_MS) death.drops.add(held);
        }
        held = null;
        return death;
    }

    /** Decide whose it was, and count it if it was yours. */
    private static void settle() {
        Death d = death;
        death = null;
        if (d == null) return;
        boolean mine = d.place <= PLACES
                || (d.dead && !d.drops.isEmpty())
                || (d.dead && WARDEN.equals(d.boss) && catalyst);
        if (WARDEN.equals(d.boss)) catalyst = false;
        if (!mine) return;
        kill(d.boss);
        for (Drops.Drop x : d.drops) drop(d.boss, x);
    }

    private static void kill(String boss) {
        of(session, boss).kills++;
        of(total, boss).kills++;
        showing = boss;
        save();
    }

    private static void drop(String boss, Drops.Drop d) {
        of(session, boss).drops.merge(d.item(), 1, Integer::sum);
        of(total, boss).drops.merge(d.item(), 1, Integer::sum);
        seen.merge(d.item(), d.tier(), Math::max);
        showing = boss;
        save();
    }

    /** Your list's tier, or the one the server announced it at. */
    private static int tier(String item) {
        return Math.max(Drops.tierOf(item), seen.getOrDefault(item, 0));
    }

    /** One boss's numbers into chat, the same order and colours as the readout. */
    private static void say(String boss) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Count c = of(TOTAL.equals(mode) ? total : session, boss);
        mc.player.sendSystemMessage(Component.literal("§d" + boss + " §7- §f" + c.kills + (c.kills == 1 ? " kill" : " kills")
                + " §8(" + (TOTAL.equals(mode) ? "all time" : "this session") + ")"));
        List<Map.Entry<String, Integer>> list = sorted(c.drops);
        if (list.isEmpty()) {
            mc.player.sendSystemMessage(Component.literal("§8  nothing worth a row yet"));
            return;
        }
        for (Map.Entry<String, Integer> e : list) {
            int colour = Rarity.colour(e.getKey(), Drops.colour(tier(e.getKey())));
            mc.player.sendSystemMessage(Component.literal("  ")
                    .append(Component.literal(e.getKey()).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(colour & 0xFFFFFF))))
                    .append(Component.literal("  §f" + e.getValue())));
        }
    }

    // ── the file ──────────────────────────────────────────────────────────────

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("boss-drops.txt");
    }

    private static void save() {
        List<String> lines = new ArrayList<>();
        lines.add("# all-time: kill<TAB>boss<TAB>count, drop<TAB>boss<TAB>item<TAB>count, tier<TAB>item<TAB>0-3 as announced");
        total.forEach((boss, c) -> {
            lines.add("kill\t" + boss + "\t" + c.kills);
            c.drops.forEach((item, n) -> lines.add("drop\t" + boss + "\t" + item + "\t" + n));
        });
        seen.forEach((item, t) -> lines.add("tier\t" + item + "\t" + t));
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Endsight] could not write boss-drops.txt: " + e);
        }
    }

    private static void load() {
        if (!Files.exists(file())) return;
        try {
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p[0].equals("kill") && p.length == 3) of(total, p[1]).kills = Integer.parseInt(p[2]);
                else if (p[0].equals("drop") && p.length == 4) of(total, p[1]).drops.put(p[2], Integer.parseInt(p[3]));
                else if (p[0].equals("tier") && p.length == 3) seen.put(p[1], Integer.parseInt(p[2]));
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[Endsight] could not read boss-drops.txt: " + e);
        }
    }

    // ── drawing ───────────────────────────────────────────────────────────────

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.level == null || mc.options.hideGui) return;
        // Nothing to say until something has died this session.
        if (of(session, showing).kills == 0 && !TOTAL.equals(mode)) return;
        HudLayout.draw("drops.boss", g, mc.font, false);
    }

    /** The drops worth a row - your tier list decides - rarest first, then most dropped, then the name. */
    private static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> drops) {
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        int floor = Drops.TIERS.indexOf(minTier);
        for (Map.Entry<String, Integer> e : drops.entrySet()) {
            if (tier(e.getKey()) >= floor) out.add(e);
        }
        out.sort((a, b) -> {
            int r = Integer.compare(Rarity.rank(b.getKey()), Rarity.rank(a.getKey()));
            if (r != 0) return r;
            int t = Integer.compare(tier(b.getKey()), tier(a.getKey()));
            if (t != 0) return t;
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareToIgnoreCase(b.getKey());
        });
        return out;
    }

    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        String boss = showing;
        Count c = of(TOTAL.equals(mode) ? total : session, boss);
        int kills = c.kills;
        Map<String, Integer> drops = c.drops;
        if (sample) {
            boss = DRAGON;
            kills = 12;
            drops = new LinkedHashMap<>();
            drops.put("Golden Dragon Chestplate", 1);
            drops.put("Aspect of the Dragons", 3);
        }
        String title = boss.toUpperCase(Locale.ROOT) + " DROPS";
        String chip = TOTAL.equals(mode) ? "total" : "session";
        String killLine = kills + (kills == 1 ? " kill" : " kills");
        List<Map.Entry<String, Integer>> list = sample ? new ArrayList<>(drops.entrySet()) : sorted(drops);

        int w = font.width(title) + 20 + font.width(chip) + 12;
        for (Map.Entry<String, Integer> e : list) w = Math.max(w, 8 + font.width(e.getKey()) + 12 + font.width(String.valueOf(e.getValue())));
        int h = Readout.ROW_H + 3 + (Readout.ROW_H + 2) + Math.max(1, list.size()) * (Readout.ROW_H + 2);

        if (g != null) {
            Readout.tick(g, x, y, w, Theme.accent());
            Draw.text(g, font, title, Readout.left(x), y, Theme.muted());
            // Session or total, as a small chip at the title's end: click it with chat
            // open to flip. On the readout and not the settings page because you decide
            // which you want to look at while looking at it.
            int cw = font.width(chip) + 8;
            int cx = Readout.right(x, w) - cw;
            Draw.roundedRect(g, cx, y - 2, cw, 11, 3, Draw.alpha(Theme.accent(), 0.22f));
            Draw.text(g, font, chip, cx + 4, y, Theme.accent());
            int ry = y + Readout.ROW_H + 3;
            Draw.text(g, font, killLine, Readout.left(x), ry, Theme.dim());
            ry += Readout.ROW_H + 2;
            if (list.isEmpty()) Draw.text(g, font, "Nothing worth a row yet", Readout.left(x), ry, Theme.dim());
            for (Map.Entry<String, Integer> e : list) {
                int colour = Rarity.colour(e.getKey(), Drops.colour(tier(e.getKey())));
                Draw.text(g, font, e.getKey(), Readout.left(x), ry, colour | 0xFF000000);
                Draw.textRight(g, font, String.valueOf(e.getValue()), Readout.right(x, w), ry, Theme.text());
                ry += Readout.ROW_H + 2;
            }
        }
        return new int[]{w, h};
    }
}
