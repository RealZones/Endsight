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
import java.util.HashMap;
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

    static final String DRAGON = "Dragon", WARDEN = "Warden", GOLEM = "Golem", REVENANT = "Revenant", VOIDGLOOM = "Voidgloom";
    private static final List<String> BOSSES = List.of(DRAGON, WARDEN, GOLEM, REVENANT, VOIDGLOOM);
    /**
     * Each kind of dragon is its own boss - "Golden Dragon", "Protector Dragon" - because
     * the drops worth counting are the kind's own: a Golden Dragon Chestplate is one in
     * so many Golden dragons, and a kill count over every dragon says nothing about it.
     * The readout follows the last kind killed; /drops dragon adds them all up.
     */
    private static final List<String> KINDS = List.of("Young", "Old", "Strong", "Wise", "Unstable", "Superior", "Protector", "Golden");
    private static final Pattern DRAGON_DEAD = Pattern.compile("^☠ The (?:(.+?) )?Dragon has de-spawned");
    private static final Pattern OBTAINED = Pattern.compile("^(?:\\[[^\\]]+\\] )?(\\S+?)(?: \\S)? has obtained (.+?)!$");
    private static final Pattern TARGET = Pattern.compile("Slay [\\d,]+ Combat XP worth of (\\w+)");
    /** The summary box: one message of many lines, "THE WARDEN DOWN!" and your place near the end. */
    private static final Pattern BOX = Pattern.compile(
            "(THE WARDEN|ENDSTONE PROTECTOR|(?:[A-Z]+ )?DRAGON) DOWN!.*?Your Damage: [\\d,.]+[KMB]? \\(Position #(\\d+)\\)", Pattern.DOTALL);
    /** Your eye going in - a Summoning Eye or, for a Golden dragon, a Golden Eye. */
    private static final Pattern EYE_PLACED = Pattern.compile("You placed a (?:Summoning|Golden) Eye");
    /**
     * Printed to you when the dragon rises, if any of its eyes were yours. The placing
     * lines are not always shown, so this is the one that settles it.
     */
    private static final String AWOKEN = "Your Sleeping Eyes have been awoken";
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
    /** Fodder nobody wants a row for, at any setting. */
    // Travel Scroll and Hot Potato Book are not dragon drops; they were golem loot landing
    // in a dragon window.
    private static final Set<String> HIDDEN = Set.of("dragon scale", "pure seeds", "aspect of the dragons", "travel scroll", "hot potato book");

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

    /**
     * This session's numbers, and what the file held when it was last read. All-time is
     * the two added together, never kept on its own - so the file can be edited, or put
     * back from a backup, while the game runs, and the next save adds only what happened
     * since the last one to whatever is there, rather than stamping over it.
     */
    private static final Map<String, Count> session = new LinkedHashMap<>(), loaded = new LinkedHashMap<>();
    /** Since the last write: what the next write adds to whatever the file holds by then. */
    private static final Map<String, Count> unsaved = new LinkedHashMap<>();
    private static long fileStamp;
    /**
     * The tier each item was announced at, for anything your list does not name: the
     * server's own word is the only rarity a new item has.
     */
    private static final Map<String, Integer> seen = new LinkedHashMap<>();
    /** The boss on the readout: the last one killed. Any dragon is DRAGON here. */
    private static String showing = DRAGON;
    /** The last dragon killed, by kind, for the "has obtained" lines that follow it. */
    private static String lastDragon = DRAGON;
    /** Which dragons the readout adds up: every kind, or one. Cycled by the chip on the title. */
    private static final String ALL = "all";
    private static String kind = ALL;
    /** The two chips on the title row, as offsets from the readout's left edge, for the click. */
    private static int modeL, modeR, kindL, kindR;
    private static String slayer = REVENANT;
    private static Death death;
    /** The last drop line, for a kill line that arrives just after it. */
    private static Drops.Drop held;
    private static long heldAt;
    private static int eyes;
    private static boolean catalyst;
    private static long catalystAt;
    private static int catalysts = -1;

    private static Count of(Map<String, Count> m, String boss) {
        boss = bossKey(boss);
        return m.computeIfAbsent(boss, k -> new Count());
    }

    private static String bossKey(String boss) {
        if ("Zombie".equals(boss)) return REVENANT;
        if ("Enderman".equals(boss)) return VOIDGLOOM;
        return boss;
    }

    private static boolean isDragon(String boss) {
        return boss.equals(DRAGON) || boss.endsWith(" Dragon");
    }

    /** Every kind of dragon added up. */
    private static Count dragons(Map<String, Count> m) {
        Count sum = new Count();
        m.forEach((k, c) -> {
            if (!isDragon(k)) return;
            sum.kills += c.kills;
            c.drops.forEach((item, n) -> sum.drops.merge(item, n, Integer::sum));
        });
        return sum;
    }

    /** All-time: the file's numbers plus what has not been written yet. */
    private static Map<String, Count> totals() {
        return sum(loaded, unsaved);
    }

    private static Map<String, Count> sum(Map<String, Count> a, Map<String, Count> b) {
        Map<String, Count> out = new LinkedHashMap<>();
        for (Map<String, Count> m : List.of(a, b)) {
            m.forEach((boss, c) -> {
                Count sum = of(out, boss);
                sum.kills += c.kills;
                c.drops.forEach((item, n) -> sum.drops.merge(item, n, Integer::sum));
            });
        }
        return out;
    }

    /** The numbers the readout is set to: this session's, or all-time. */
    private static Map<String, Count> view() {
        return TOTAL.equals(mode) ? totals() : session;
    }

    /** What the readout shows for a boss: for dragons, all kinds or the one the chip picks. */
    private static Count count(Map<String, Count> m, String boss) {
        if (!DRAGON.equals(boss)) return of(m, boss);
        return ALL.equals(kind) ? dragons(m) : of(m, kind + " Dragon");
    }

    /** All, then each kind killed in the mode showing, then all again. */
    private static void nextKind() {
        Map<String, Count> m = view();
        List<String> kinds = new ArrayList<>();
        kinds.add(ALL);
        for (String k : KINDS) if (of(m, k + " Dragon").kills > 0) kinds.add(k);
        int i = kinds.indexOf(kind);
        kind = kinds.get((i + 1) % kinds.size());
    }

    public static Module module() {
        return new Module("dragon.bossdrops", "Boss Drops",
                "Kills and drops per boss, sorted by rarity.", "Trackers",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Choice("Show from",
                                "Lowest tier that gets a row.",
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
                // Dragon loot is announced by name and credited that way; a dragon's
                // "loot number → Golden Dragon Leggings" landing in the second a Warden
                // died is the dragon's, not the Warden's.
                String lower = d.item().toLowerCase(Locale.ROOT);
                if (!NEST.contains(lower)) {
                    if (death != null) {
                        if (isDragon(death.boss) || !lower.contains("dragon")) death.drops.add(d);
                    }
                    else {
                        held = d;
                        heldAt = now;
                    }
                }
                return;
            }
            Matcher m = TARGET.matcher(line);
            if (m.find()) {
                slayer = m.group(1).toLowerCase(Locale.ROOT).startsWith("ender") ? VOIDGLOOM : REVENANT;
                return;
            }
            if (EYE_PLACED.matcher(line).find()) {
                eyes++;
                return;
            }
            // "has awoken" is broadcast to the whole lobby, so it says nothing about who
            // placed an eye; counting it as one made every dragon yours.
            if (line.contains(EGG_SPAWNED)) {
                eyes = 0;
                return;
            }
            if (line.contains(TREMBLE)) {
                catalyst = now - catalystAt < CATALYST_MS;
                return;
            }
            m = DRAGON_DEAD.matcher(line);
            if (m.find()) {
                // Yours if you put an eye in. Its drops are announced by name, seconds
                // later, or as a debug loot line right after the summary box.
                if (eyes > 0) {
                    String boss = m.group(1) == null ? DRAGON : m.group(1) + " Dragon";
                    open(boss, now, false).place = 1;
                    show(boss);
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
                    drop(lastDragon, new Drops.Drop(item, Drops.tierOf(item)));
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
                Map<String, Count> m = view();
                for (String boss : BOSSES) if ((DRAGON.equals(boss) ? dragons(m) : of(m, boss)).kills > 0) say(boss);
                return 1;
            });
            // /drops dragon is every kind together; /drops golden one kind.
            for (String boss : BOSSES) {
                root.then(ClientCommands.literal(boss.toLowerCase(Locale.ROOT)).executes(c -> {
                    say(boss);
                    return 1;
                }));
            }
            root.then(ClientCommands.literal("zombie").executes(c -> {
                say(REVENANT);
                return 1;
            }));
            root.then(ClientCommands.literal("enderman").executes(c -> {
                say(VOIDGLOOM);
                return 1;
            }));
            for (String k : KINDS) {
                root.then(ClientCommands.literal(k.toLowerCase(Locale.ROOT)).executes(c -> {
                    say(k + " Dragon");
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
                float sc = HudLayout.scale("drops.boss");
                if (click.y() < b[1] || click.y() >= b[1] + (Readout.ROW_H + 2) * sc) return true;
                double lx = (click.x() - b[0]) / sc;     // the chips are known as offsets in the readout's own pixels
                if (lx >= modeL && lx < modeR) {
                    mode = TOTAL.equals(mode) ? SESSION : TOTAL;
                    return false;
                }
                if (kindR > kindL && lx >= kindL && lx < kindR) {
                    nextKind();
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
        of(unsaved, boss).kills++;
        show(boss);
        save();
    }

    /** Fodder and every dragon armour piece: no row, whatever the tier says. */
    private static boolean hidden(String item) {
        String s = item.toLowerCase(Locale.ROOT);
        for (String h : HIDDEN) if (s.contains(h)) return true;   // "Travel Scroll to Dragon Nest" too
        return s.contains("dragon")
                && (s.contains("helmet") || s.contains("chestplate") || s.contains("leggings") || s.contains("boots"));
    }

    /** The last few drops counted, by item and when; the same one twice is one drop. */
    private static final Map<String, Long> counted = new HashMap<>();
    private static final long SAME_DROP_MS = 15_000;

    /**
     * One drop, however many ways the server says it.
     *
     * A drop is announced twice when the loot debug is on: once as its own "RARE DROP!"
     * line and again as "<you> has obtained <item>!" a moment later. They arrive by
     * different paths - one parsed as a drop, one as the broadcast - so neither's repeat
     * check saw the other, and every drop counted as two. Fifteen seconds is longer than
     * the gap between the two lines and shorter than any two real drops of one item.
     */
    private static void drop(String boss, Drops.Drop d) {
        if (hidden(d.item())) return;
        long now = System.currentTimeMillis();
        counted.values().removeIf(t -> now - t > SAME_DROP_MS);
        Long last = counted.put(d.item().toLowerCase(Locale.ROOT), now);
        if (last != null && now - last < SAME_DROP_MS) return;
        of(session, boss).drops.merge(d.item(), 1, Integer::sum);
        of(unsaved, boss).drops.merge(d.item(), 1, Integer::sum);
        seen.merge(d.item(), d.tier(), Math::max);
        show(boss);
        save();
    }

    /** The readout turns to the boss that just did something; a dragon of any kind is the dragon readout. */
    private static void show(String boss) {
        if (isDragon(boss)) {
            lastDragon = boss;
            showing = DRAGON;
        } else {
            showing = boss;
        }
    }

    /** A name to sort by: a pet's "[Lvl 1] " is not part of what it is, and "[" sorts before every letter. */
    private static String plain(String item) {
        return item.replaceFirst("^\\[[^\\]]*\\]\\s*", "");
    }

    /** Your list's tier, or the one the server announced it at. */

    private static int tier(String item) {
        return Math.max(Drops.tierOf(item), seen.getOrDefault(item, 0));
    }

    /**
     * Where an item sorts: its own rarity when its lore has been seen, else the tier it
     * was listed or announced at, on the same scale. Without the fallback an item never
     * held - a talisman straight into the bag - ranked below everything, and a Golden
     * Ghoul Talisman sat under a Zombie Talisman while drawn in epic purple.
     */
    private static int rankOf(String item) {
        // The tier list is by rarity AND value and it wins when it names the item: the
        // game's colour put a gold Tiger pet above a purple Dragon Horn.
        int r = Drops.listed(item) ? -1 : Rarity.rank(item);
        if (r > 0) return r;
        return switch (tier(item)) {
            case 3 -> 5;
            case 2 -> 4;
            case 1 -> 3;
            default -> 1;
        };
    }

    /** One boss's numbers into chat, the same order and colours as the readout. */
    private static void say(String boss) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Map<String, Count> m = view();
        Count c = DRAGON.equals(boss) ? dragons(m) : of(m, boss);
        mc.player.sendSystemMessage(Component.literal("§d" + boss + " §7- §f" + c.kills + (c.kills == 1 ? " kill" : " kills")
                + " §8(" + (TOTAL.equals(mode) ? "all time" : "this session") + ")"));
        if (DRAGON.equals(boss)) {
            StringBuilder kinds = new StringBuilder("§8  ");
            for (String k : KINDS) if (of(m, k + " Dragon").kills > 0) kinds.append(k).append(' ').append(of(m, k + " Dragon").kills).append("  ");
            if (kinds.length() > 4) mc.player.sendSystemMessage(Component.literal(kinds.toString().trim()));
        }
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
        // Someone else wrote the file since we read it: take theirs as the base.
        try {
            if (Files.exists(file()) && Files.getLastModifiedTime(file()).toMillis() != fileStamp) load();
        } catch (IOException ignored) {
        }
        List<String> lines = new ArrayList<>();
        lines.add("# all-time: kill<TAB>boss<TAB>count, drop<TAB>boss<TAB>item<TAB>count, tier<TAB>item<TAB>0-3 as announced; dragons by kind");
        totals().forEach((boss, c) -> {
            lines.add("kill\t" + boss + "\t" + c.kills);
            c.drops.forEach((item, n) -> lines.add("drop\t" + boss + "\t" + item + "\t" + n));
        });
        seen.forEach((item, t) -> lines.add("tier\t" + item + "\t" + t));
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), lines, StandardCharsets.UTF_8);
            fileStamp = Files.getLastModifiedTime(file()).toMillis();
            // Written, so from here it is part of the base.
            Map<String, Count> base = sum(loaded, unsaved);
            loaded.clear();
            loaded.putAll(base);
            unsaved.clear();
        } catch (IOException e) {
            System.err.println("[Endsight] could not write boss-drops.txt: " + e);
        }
    }

    private static void load() {
        loaded.clear();
        if (!Files.exists(file())) return;
        try {
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p[0].equals("kill") && p.length == 3) of(loaded, p[1]).kills = Integer.parseInt(p[2]);
                else if (p[0].equals("drop") && p.length == 4) of(loaded, p[1]).drops.put(p[2], Integer.parseInt(p[3]));
                else if (p[0].equals("tier") && p.length == 3) seen.put(p[1], Integer.parseInt(p[2]));
            }
            fileStamp = Files.getLastModifiedTime(file()).toMillis();
        } catch (IOException | RuntimeException e) {
            System.err.println("[Endsight] could not read boss-drops.txt: " + e);
        }
    }

    // ── drawing ───────────────────────────────────────────────────────────────

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.level == null || mc.options.hideGui) return;
        // Nothing to say until something has died this session.
        if (count(session, showing).kills == 0 && !TOTAL.equals(mode)) return;
        HudLayout.draw("drops.boss", g, mc.font, false);
    }

    /** The drops worth a row - your tier list decides - rarest first, then most dropped, then the name. */
    private static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> drops) {
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        int floor = Drops.TIERS.indexOf(minTier);
        for (Map.Entry<String, Integer> e : drops.entrySet()) {
            if (tier(e.getKey()) >= floor && !hidden(e.getKey())) out.add(e);
        }
        out.sort((a, b) -> {
            int r = Integer.compare(rankOf(b.getKey()), rankOf(a.getKey()));
            if (r != 0) return r;
            int t = Integer.compare(tier(b.getKey()), tier(a.getKey()));
            if (t != 0) return t;
            // Tied on the list, so let the item say: a common Warden pet is not a
            // Giant's Core, though the list calls both legendary.
            int q = Integer.compare(Rarity.rank(b.getKey()), Rarity.rank(a.getKey()));
            if (q != 0) return q;
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : plain(a.getKey()).compareToIgnoreCase(plain(b.getKey()));
        });
        return out;
    }

    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        String boss = showing;
        Count c = count(view(), boss);
        int kills = c.kills;
        Map<String, Integer> drops = c.drops;
        String kindChip = DRAGON.equals(boss) ? (ALL.equals(kind) ? ALL : kind.toLowerCase(Locale.ROOT)) : null;
        if (sample) {
            boss = DRAGON;
            kindChip = ALL;
            kills = 12;
            drops = new LinkedHashMap<>();
            drops.put("Golden Dragon Chestplate", 1);
            drops.put("Aspect of the Dragons", 3);
        }
        String title = (DRAGON.equals(boss) && kindChip != null && !ALL.equals(kindChip) ? kind.toUpperCase(Locale.ROOT) + " " : "")
                + boss.toUpperCase(Locale.ROOT) + " DROPS";
        String chip = TOTAL.equals(mode) ? "total" : "session";
        String killLine = kills + (kills == 1 ? " kill" : " kills");
        List<Map.Entry<String, Integer>> list = sample ? new ArrayList<>(drops.entrySet()) : sorted(drops);

        int w = font.width(title) + 20 + font.width(chip) + 12 + (kindChip == null ? 0 : font.width(kindChip) + 12);
        for (Map.Entry<String, Integer> e : list) w = Math.max(w, 8 + font.width(e.getKey()) + 12 + font.width(String.valueOf(e.getValue())));
        int h = Readout.ROW_H + 3 + (Readout.ROW_H + 2) + Math.max(1, list.size()) * (Readout.ROW_H + 2);

        if (g != null) {
            Readout.tick(g, x, y, w, Theme.accent());
            Draw.text(g, font, title, Readout.left(x), y, Theme.muted());
            // Session or total as a small chip at the title's end, and for dragons a
            // second one saying which kinds are added up: click either with chat open.
            // On the readout and not the settings page because you decide which you
            // want to look at while looking at it.
            int cw = font.width(chip) + 8;
            int cx = Readout.right(x, w) - cw;
            Draw.roundedRect(g, cx, y - 2, cw, 11, 3, Draw.alpha(Theme.accent(), 0.22f));
            Draw.text(g, font, chip, cx + 4, y, Theme.accent());
            modeL = cx - x;
            modeR = cx + cw - x;
            if (kindChip != null) {
                int kw = font.width(kindChip) + 8;
                int kx = cx - 4 - kw;
                Draw.roundedRect(g, kx, y - 2, kw, 11, 3, Draw.alpha(Theme.muted(), 0.22f));
                Draw.text(g, font, kindChip, kx + 4, y, Theme.muted());
                kindL = kx - x;
                kindR = kx + kw - x;
            } else {
                kindL = kindR = 0;
            }
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
