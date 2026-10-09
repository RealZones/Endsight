package com.endsight.dragons;

import com.endsight.hud.HudLayout;
import com.endsight.hud.Area;
import com.endsight.hud.Readout;
import com.endsight.qol.Drops;
import com.endsight.qol.NpcPrices;
import com.endsight.qol.Rarity;
import com.endsight.slayers.SlayerCosts;
import com.endsight.storage.Recipes;
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
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final List<String> SLAYER_ITEMS = List.of(
            "Zombie Talisman", "Revenant Viscera", "Warden Heart", "Judgement Core",
            "Scythe Blade", "Shard of the Shredded", "Void Upgrade Stone",
            "High Class Archfiend Dice", "Beheaded Horror", "Rose of Lies",
            "Crypt Cache", "Undead Catalyst", "Revenant Catalyst",
            "Transmission Tuner", "Etherwarp Conduit", "Etherwarp Merger", "Null Atom", "Null Ovoid");
    /**
     * Each kind of dragon is its own boss - "Golden Dragon", "Protector Dragon" - because
     * the drops worth counting are the kind's own: a Golden Dragon Chestplate is one in
     * so many Golden dragons, and a kill count over every dragon says nothing about it.
     * The readout follows the last kind killed; /drops dragon adds them all up.
     */
    // Berserk, Chaotic and Arcane (Lv200) came in on 2026-10-08.
    private static final List<String> KINDS = List.of("Young", "Old", "Strong", "Wise", "Unstable", "Superior", "Protector", "Golden",
            "Berserk", "Chaotic", "Arcane");
    private static final Pattern DRAGON_DEAD = Pattern.compile("^☠ The (?:(.+?) )?Dragon has de-spawned");
    private static final Pattern OBTAINED = Pattern.compile("^(?:\\[[^\\]]+\\] )?(\\S+?)(?: \\S)? has obtained (.+?)!$");
    /**
     * "Here is your loot from the last dragon:" and a line per item, ten seconds after the kill.
     *
     * DragSim changed its dragon debug line on 2026-10-07 at about 15:05: "loot number: 38.9 ->
     * Dragon Claw" became "loot number: 38.9 (the rare table, lower = rarer)", with no item.
     * That line was where every ordinary dragon drop came from - "has obtained" is only
     * broadcast for the rare ones - so from then on Dragon Drops counted almost nothing:
     * 119 dragons that afternoon, five drops, which is what two players reported as drops
     * "not being counted". The summary names everything you got and is sent only to you,
     * so it is the source now. Its fragments are the dragon's guaranteed share and never a
     * row, but they name its kind, which matters when the eye lines were missed.
     */
    private static final String SUMMARY = "Here is your loot from the last dragon:";
    private static final Pattern SUMMARY_LINE = Pattern.compile("^-\\s*(\\d+)x\\s+(.+)$");
    private static final Pattern FRAGMENT_KIND = Pattern.compile("^(\\w+) Dragon Fragment$");
    /** Every line of the summary lands in the same tick; this is generous. */
    private static final long SUMMARY_MS = 1_000;
    /** Pets share a name across rarities; the tier belongs in the saved drop key. */
    private static final Pattern PET_NAME = Pattern.compile("^\\[Lvl \\d+\\] ");
    private static final Pattern PET_RARITY = Pattern.compile(" \\((Common/Uncommon|Rare|Epic|Legendary)\\)$");
    private static final Pattern TARGET = Pattern.compile("Slay [\\d,]+ Combat XP worth of (\\w+)");
    /** The summary box: one message of many lines, "THE WARDEN DOWN!" and your place near the end. */
    private static final Pattern BOX = Pattern.compile(
            "(THE WARDEN|ENDSTONE PROTECTOR|(?:[A-Z]+ )?DRAGON) DOWN!.*?Your Damage: [\\d,.]+[KMB]? \\(Position #(\\d+)\\)", Pattern.DOTALL);
    /** Your eye going in - a Summoning Eye or, for a Golden dragon, a Golden Eye. */
    private static final Pattern EYE_PLACED = Pattern.compile("You placed a (?:Summoning|Golden) Eye");
    /**
     * Printed when the dragon rises - to the whole lobby, whoever placed the eyes. 0.6.1
     * took it as proof of an eye of yours, so from then every dragon that died near you
     * counted: a Berserk Dragon he never hit showed one kill, and his logs had 247 of 729
     * counted dragons without an eye of his. "You placed a Golden Eye" is printed after all;
     * Golden dragons had gone uncounted because the pattern only knew Summoning Eyes.
     */
    private static final String AWOKEN = "Your Sleeping Eyes have been awoken";
    private static final String EGG_SPAWNED = "Egg has Spawned";
    private static final String WARDEN_DEAD = "The Warden has been defeated";
    private static final String GOLEM_DEAD = "The Endstone Protector has been defeated";
    private static final String SLAYER_DEAD = "SLAYER BOSS SLAIN";
    private static final String SLAYER_SPAWNING = "SLAYER BOSS SPAWNING";
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
    private static final long DRAGON_LINGER_MS = 60_000;
    /** What zealots and the golden ones drop: never a boss's, whatever the timing says. */
    private static final Set<String> NEST = Set.of("summoning eye", "enderman", "null ovoid", "ender pearl",
            "spicy wart", "warped stone", "infinieye", "zealot talisman", "warden catalyst");
    /**
     * Mined, never a boss's. Mining amethyst beside the golem put a Void Fragment in its
     * window; the window only knows timing.
     */
    private static final Set<String> MINED = Set.of("void fragment", "void core");
    /**
     * Each boss's loot as its bestiary page lists it - pets by name, any level. A boss on
     * this list takes nothing else from its window: timing alone gave the golem Golden Eyes
     * and a Void Fragment. Bosses not on it (dragons, slayers) still go by timing.
     */
    private static final Map<String, Set<String>> LOOT = Map.of(
            GOLEM, Set.of("giant's core", "precursor gear", "golem", "tier boost core", "mysterious handle",
                    "livid dagger", "defender talisman", "endstone rose bush", "hot potato book"),
            WARDEN, Set.of("giant's core", "warden core", "precursor gear", "warden", "lament", "golem upgrader",
                    "golem", "tier boost core", "mysterious handle", "livid dagger", "endstone rose bush", "hot potato book"));
    /** Real loot, but not worth a row: it still proves a kill was yours, it is just not counted. */
    private static final Set<String> FODDER = Set.of("defender talisman", "endstone rose bush", "hot potato book");
    /** Fodder nobody wants a row for, at any setting. */
    // Travel Scroll and Hot Potato Book are not dragon drops; they were golem loot landing
    // in a dragon window.
    private static final Set<String> HIDDEN = Set.of("dragon scale", "pure seeds", "aspect of the dragons", "travel scroll", "hot potato book");

    private static final String SESSION = "Session";
    private static final String TOTAL = "Total";

    private static boolean enabled = true;
    private static String mode = SESSION;
    private static boolean compactLayout = true;
    private static int cheapSlayerFloor = 100_000;
    private static String minTier = "Rare and up";
    /** Which drops make the list - everything is counted, this is only what is shown. */

    /** One boss: kills, and drops by item. */
    private static final class Count {
        int kills;
        int starts, unknownCosts;
        long spawnCost, historicalCost;
        final Map<String, Integer> drops = new LinkedHashMap<>();
    }

    /** One boss dying, held open until its box and its drops have arrived. */
    private static final class Death {
        final String boss;
        final long at;
        int place = 99;
        boolean dead;
        RewardWindow reward;
        final List<Drops.Drop> drops = new ArrayList<>();

        Death(String boss, long at) {
            this.boss = boss;
            this.at = at;
        }
    }

    /** Inventory total just before your boss spawns, so an unannounced stack can be measured. */
    private record RewardWindow(String boss, String item, int baseline, Object level, long at) { }

    /**
     * This session's numbers, and what the file held when it was last read. All-time is
     * the two added together, never kept on its own - so the file can be edited, or put
     * back from a backup, while the game runs, and the next save adds only what happened
     * since the last one to whatever is there, rather than stamping over it.
     */
    private static final Map<String, Count> session = new LinkedHashMap<>(), loaded = new LinkedHashMap<>();
    /** Since the last write: what the next write adds to whatever the file holds by then. */
    private static final Map<String, Count> unsaved = new LinkedHashMap<>();
    /**
     * Other players' dragons the audit already took off each kind's kills. The logs keep
     * showing them, so without this a second /drops audit apply would take them off again.
     */
    private static final Map<String, Integer> lobbyTaken = new LinkedHashMap<>();
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
    private static RewardWindow rewardWindow;
    /** The last drop line, for a kill line that arrives just after it. */
    private static Drops.Drop held;
    private static long heldAt;
    private static int eyes;
    private static boolean ownDragonUp;
    private static long dragonActivityAt;
    private static long summaryAt, dragonKillAt;
    /** The summary being read: raw line, item, count. */
    private static final List<String[]> summary = new ArrayList<>();
    private static boolean catalyst;
    private static long catalystAt;
    private static int catalysts = -1;
    private static final SlayerCosts spawnCosts = new SlayerCosts();
    private static int menuTicks;
    private static final AtomicBoolean auditRunning = new AtomicBoolean();

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
                sum.starts += c.starts;
                sum.unknownCosts += c.unknownCosts;
                sum.spawnCost += c.spawnCost;
                sum.historicalCost += c.historicalCost;
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
                "Kills, drops, Slayer costs and values.", "Trackers",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Toggle("Compact layout", "Counts first, with kills and spawn costs below.",
                                () -> compactLayout, v -> compactLayout = v),
                        new Setting.Choice("Show from",
                                "Lowest tier that gets a row, for dragons.",
                                Drops.TIERS, () -> minTier, v -> minTier = v),
                        new Setting.Slider("Hide cheap Slayer drops",
                                "Hide priced Slayer rows below this total; they still count toward profit.",
                                0, 5_000_000, 100_000, () -> cheapSlayerFloor, v -> cheapSlayerFloor = (int) v, " coins"),
                        new Setting.Toggle("Learn Slayer sale prices", "Use a Slayer drop's value after its first sale; off uses custom or built-in prices.",
                                NpcPrices::learning, NpcPrices::learning),
                        new Setting.Action("Slayer drop prices", "Edit prices and reload a shared Slayer price file.",
                                "Edit", DropPricesScreen::open)));
    }

    public static void init() {
        load();
        NpcPrices.init();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            String raw = message.getString();
            String line = Zealots.strip(raw).trim();
            if (DragonTimer.isPlayerChat(line)) return;
            long now = System.currentTimeMillis();
            if (line.startsWith(SUMMARY)) {
                settleSummary();
                summaryAt = now;
                return;
            }
            if (summaryAt != 0 && now - summaryAt < SUMMARY_MS) {
                String[] item = summaryLine(line);
                if (item != null) {
                    summary.add(new String[]{raw, item[0], item[1]});
                    return;
                }
            }
            SlayerCosts.Start start = spawnCosts.onLine(line, now);
            if (start != null) {
                long coins = SlayerCosts.afterLoan(start.coins(), questDiscount());
                for (Map<String, Count> counts : List.of(session, unsaved)) {
                    Count c = of(counts, start.boss());
                    c.starts++;
                    if (coins < 0) c.unknownCosts++;
                    else c.spawnCost += coins;
                }
                show(start.boss());
                save();
            }
            if (line.contains(SLAYER_SPAWNING)) {
                Minecraft mc = Minecraft.getInstance();
                String item = rewardItem(slayer);
                rewardWindow = mc.player == null || mc.level == null || item == null ? null
                        : new RewardWindow(slayer, item, inventoryCount(mc, item), mc.level, now);
                return;
            }

            Drops.Drop d = Drops.parse(raw);
            if (d != null) {
                // Dragon loot is announced by name and credited that way; a dragon's
                // "loot number → Golden Dragon Leggings" landing in the second a Warden
                // died is the dragon's, not the Warden's.
                String lower = d.item().toLowerCase(Locale.ROOT);
                if (!NEST.contains(lower) && !MINED.contains(lower)) {
                    if (death != null) {
                        if ((isDragon(death.boss) || !lower.contains("dragon")) && belongs(death.boss, d.item())) death.drops.add(d);
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
                show(DRAGON);
                return;
            }
            if (line.contains(AWOKEN)) {
                if (eyes > 0) ownDragonUp = true;
                return;
            }
            // "has awoken" is broadcast to the whole lobby, so it says nothing about who
            // placed an eye; counting it as one made every dragon yours.
            if (line.contains(EGG_SPAWNED)) {
                eyes = 0;
                ownDragonUp = false;
                return;
            }
            if (line.contains(TREMBLE)) {
                catalyst = now - catalystAt < CATALYST_MS;
                return;
            }
            m = DRAGON_DEAD.matcher(line);
            if (m.find()) {
                ownDragonUp = false;
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
                x.reward = rewardWindow;
                rewardWindow = null;
                return;
            }
            m = OBTAINED.matcher(line);
            if (m.find()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && m.group(1).equalsIgnoreCase(mc.player.getName().getString())) {
                    String item = m.group(2).trim();
                    drop(lastDragon, obtainedDrop(raw, item));
                }
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long now = System.currentTimeMillis();
            if (death != null && now - death.at > HOLD_MS) settle();
            if (summaryAt != 0 && now - summaryAt >= SUMMARY_MS) settleSummary();
            if (mc.player == null) {
                catalysts = -1;
                ownDragonUp = false;
                eyes = 0;
                rewardWindow = null;
                spawnCosts.resetPending();
                return;
            }
            if (++menuTicks % 5 == 0) learnSpawnCosts(mc);
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
                Map<String, Count> m = totals();
                for (String boss : BOSSES) {
                    Count counts = DRAGON.equals(boss) ? dragons(m) : of(m, boss);
                    if (counts.kills > 0 || counts.starts > 0) say(boss);
                }
                return 1;
            });
            root.then(ClientCommands.literal("audit").executes(c -> audit(false))
                    .then(ClientCommands.literal("apply").executes(c -> audit(true))));
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
     * The best quest discount in the Accessory Bag, as it was last open.
     *
     * The best rather than the sum: each loan is crafted from the one below it - Small
     * Loan from the Spare Change Talisman, Big Loan from the Small Loan - so they are one
     * line of upgrades, not three things you wear together. Read off the bag Recipes
     * already keeps, which is on disk, so it holds from the first quest after a relaunch;
     * a loan only counts once the bag has been opened with it inside.
     */
    private static int questDiscount() {
        int best = 0;
        for (ItemStack stack : Recipes.accessoryBag()) {
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            best = Math.max(best, SlayerCosts.loan(
                    lore.lines().stream().map(c -> Zealots.strip(c.getString()).trim()).toList()));
        }
        return best;
    }

    private static void learnSpawnCosts(Minecraft mc) {
        if (!enabled || !(mc.screen instanceof AbstractContainerScreen<?> screen)) return;
        String title = Zealots.strip(screen.getTitle().getString()).trim();
        if (!title.equals("Revenant Horror") && !title.equals("Voidgloom Seraph")) return;
        for (var slot : screen.getMenu().slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            SlayerCosts.Quote quote = SlayerCosts.quote(Zealots.strip(stack.getHoverName().getString()).trim(),
                    lore.lines().stream().map(c -> Zealots.strip(c.getString()).trim()).toList());
            spawnCosts.learn(quote);
        }
    }

    /**
     * The death being read, opened now if there is none. A different boss still open
     * is settled first. {@code takeHeld}: a drop line just before this one was its.
     */
    private static Death open(String boss, long now, boolean takeHeld) {
        if (death != null && !death.boss.equals(boss)) settle();
        if (death == null) {
            death = new Death(boss, now);
            if (takeHeld && held != null && now - heldAt < BEFORE_MS && belongs(boss, held.item())) death.drops.add(held);
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
        String reward = rewardItem(d.boss);
        int announced = 0;
        for (Drops.Drop x : d.drops) {
            if (reward != null && lootName(x.item()).equalsIgnoreCase(reward)) announced++;
            else drop(d.boss, x);
        }
        if (reward != null) {
            Minecraft mc = Minecraft.getInstance();
            int observed = d.reward != null && d.reward.boss().equals(d.boss)
                    && d.reward.level() == mc.level && mc.player != null
                    && System.currentTimeMillis() - d.reward.at() < 120_000
                    ? inventoryCount(mc, reward) - d.reward.baseline() : 0;
            int quantity = rewardQuantity(announced, observed);
            if (quantity > 0) drop(d.boss, new Drops.Drop(reward, Drops.tierOf(reward)), quantity);
        }
    }

    private static String rewardItem(String boss) {
        return switch (boss) {
            case REVENANT -> "Revenant Viscera";
            case VOIDGLOOM -> "Null Ovoid";
            default -> null;
        };
    }

    private static int inventoryCount(Minecraft mc, String item) {
        if (mc.player == null) return 0;
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && Zealots.strip(stack.getHoverName().getString()).trim().equalsIgnoreCase(item))
                total += stack.getCount();
        }
        return total;
    }

    static int rewardQuantity(int announced, int inventoryDelta) {
        return Math.max(Math.max(0, announced), inventoryDelta);
    }

    /** Count what the dragon's loot summary listed, and the kill if the eye lines missed it. */
    private static void settleSummary() {
        if (summaryAt == 0) return;
        summaryAt = 0;
        List<String> items = new ArrayList<>();
        for (String[] s : summary) items.add(s[1]);
        String boss = summaryBoss(items, lastDragon);
        // The summary goes only to players the dragon paid, so it is a kill of yours. The
        // usual kill is counted ~7 s before it arrives; one that was missed is counted here.
        if (System.currentTimeMillis() - dragonKillAt > 15_000) kill(boss);
        for (String[] s : summary) {
            if (s[1].endsWith(" Fragment")) continue;
            drop(boss, obtainedDrop(s[0], s[1]), Integer.parseInt(s[2]));
        }
        summary.clear();
    }

    /** The dragon a summary was for: the kind its fragments name, or the last one seen. */
    static String summaryBoss(List<String> items, String fallback) {
        for (String item : items) {
            Matcher kind = FRAGMENT_KIND.matcher(item);
            if (kind.find() && KINDS.contains(kind.group(1))) return kind.group(1) + " Dragon";
        }
        return fallback;
    }

    /** One item line of the summary as {item, count}, or null. */
    static String[] summaryLine(String line) {
        Matcher m = SUMMARY_LINE.matcher(line);
        return m.find() ? new String[]{m.group(2).trim(), m.group(1)} : null;
    }

    private static void kill(String boss) {
        if (isDragon(boss)) dragonKillAt = System.currentTimeMillis();
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

    /** The last few non-Slayer drops counted, by item and when. */
    private static final Map<String, Long> counted = new HashMap<>();
    private static final long SAME_DROP_MS = 15_000;

    /**
     * One drop, however many ways the server says it.
     *
     * A drop is announced twice when the loot debug is on: once as its own "RARE DROP!"
     * line and again as "<you> has obtained <item>!" a moment later. They arrive by
     * different paths - one parsed as a drop, one as the broadcast - so neither's repeat
     * check saw the other, and every drop counted as two. Slayer kills can be faster
     * than fifteen seconds, so they use per-kill settlement instead of this time gate.
     */
    /** A drop's name as the lists hold it: lower case, a pet's "[Lvl 1] " taken off. */
    private static String lootName(String item) {
        return petBase(item).toLowerCase(Locale.ROOT)
                .replaceFirst("^\\[lvl \\d+\\]\\s*", "").trim();
    }

    private static String petBase(String item) {
        return PET_NAME.matcher(item).find() ? PET_RARITY.matcher(item).replaceFirst("") : item;
    }

    private static Drops.Drop obtainedDrop(String raw, String item) {
        // The obtained line still has the pet name's colour. The list calls every
        // Ender Dragon legendary, including the epic one in the observed game log.
        int petTier = Drops.petTier(raw);
        return new Drops.Drop(item, petTier >= 0 ? petTier : Drops.tierOf(item));
    }

    private static String petKey(String item, int tier) {
        if (!PET_NAME.matcher(item).find() || PET_RARITY.matcher(item).find()) return item;
        String rarity = switch (tier) {
            case 3 -> "Legendary";
            case 2 -> "Epic";
            case 1 -> "Rare";
            default -> "Common/Uncommon";
        };
        return item + " (" + rarity + ")";
    }

    /** Whether a drop can be this boss's at all: on its bestiary list where it has one, and never a mined drop. */
    private static boolean belongs(String boss, String item) {
        String l = lootName(item);
        if (MINED.contains(l)) return false;
        Set<String> loot = LOOT.get(boss);
        return loot == null || loot.contains(l);
    }

    /** Whether a drop of this boss's gets a row: its loot, less the fodder. */
    private static boolean counted(String boss, String item) {
        return belongs(boss, item) && !(LOOT.containsKey(boss) && FODDER.contains(lootName(item)));
    }

    /** The log audit uses the same exclusions as the live tracker without mutating it. */
    static boolean auditCounted(String boss, String item) {
        String lower = item.toLowerCase(Locale.ROOT);
        return !NEST.contains(lower) && !MINED.contains(lower) && !hidden(item) && counted(boss, item);
    }

    private static void drop(String boss, Drops.Drop d) {
        drop(boss, d, 1);
    }

    private static void drop(String boss, Drops.Drop d, int quantity) {
        if (quantity <= 0) return;
        String item = petKey(d.item(), d.tier());
        if (hidden(item) || !counted(boss, item)) return;
        if (repeatedDrop(boss, item, System.currentTimeMillis())) return;
        of(session, boss).drops.merge(item, quantity, Integer::sum);
        of(unsaved, boss).drops.merge(item, quantity, Integer::sum);
        seen.merge(item, d.tier(), Math::max);
        show(boss);
        save();
    }

    private static boolean repeatedDrop(String boss, String item, long now) {
        if (isSlayer(boss)) return false;
        counted.values().removeIf(t -> now - t > SAME_DROP_MS);
        Long last = counted.put(item.toLowerCase(Locale.ROOT), now);
        return last != null && now - last < SAME_DROP_MS;
    }

    /** The readout turns to the boss that just did something; a dragon of any kind is the dragon readout. */
    private static void show(String boss) {
        if (isDragon(boss)) {
            lastDragon = boss;
            showing = DRAGON;
            dragonActivityAt = System.currentTimeMillis();
        } else {
            showing = boss;
        }
    }

    /** A name to sort by: a pet's "[Lvl 1] " is not part of what it is, and "[" sorts before every letter. */
    private static String plain(String item) {
        return petBase(item).replaceFirst("^\\[[^\\]]*\\]\\s*", "");
    }

    public static List<String> knownSlayerDropNames() {
        Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(SLAYER_ITEMS);
        for (Map<String, Count> counts : List.of(session, loaded, unsaved))
            for (String boss : List.of(REVENANT, VOIDGLOOM))
                names.addAll(of(counts, boss).drops.keySet());
        names.addAll(NpcPrices.knownNames());
        return List.copyOf(names);
    }

    public static boolean isSlayerDropName(String item) {
        if (item == null) return false;
        String name = plain(Zealots.strip(item)).trim();
        if (SLAYER_ITEMS.stream().anyMatch(n -> n.equalsIgnoreCase(name))) return true;
        for (Map<String, Count> counts : List.of(session, loaded, unsaved))
            for (String boss : List.of(REVENANT, VOIDGLOOM))
                if (of(counts, boss).drops.keySet().stream().anyMatch(n -> plain(n).equalsIgnoreCase(name))) return true;
        return false;
    }

    /** Your list's tier, or the one the server announced it at. */

    private static int tier(String item) {
        if (PET_NAME.matcher(item).find()) {
            Matcher pet = PET_RARITY.matcher(item);
            if (pet.find()) return switch (pet.group(1)) {
                case "Legendary" -> 3;
                case "Epic" -> 2;
                case "Rare" -> 1;
                default -> 0;
            };
        }
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

    /**
     * One boss's numbers into chat, the same order and colours as the readout. Always all
     * time: it followed the readout's chip, so with the chip on session, /drops answered
     * "how many have I ever got" with this session's handful.
     */
    private static void say(String boss) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Map<String, Count> m = totals();
        Count c = DRAGON.equals(boss) ? dragons(m) : of(m, boss);
        mc.player.sendSystemMessage(Component.literal("§d" + boss + " §7- §f" + c.kills + (c.kills == 1 ? " kill" : " kills")
                + " §8(all time)"));
        if (isSlayer(boss)) mc.player.sendSystemMessage(Component.literal("§7Spawn costs: §c" + costText(c)));
        if (DRAGON.equals(boss)) {
            StringBuilder kinds = new StringBuilder("§8  ");
            for (String k : KINDS) if (of(m, k + " Dragon").kills > 0) kinds.append(k).append(' ').append(of(m, k + " Dragon").kills).append("  ");
            if (kinds.length() > 4) mc.player.sendSystemMessage(Component.literal(kinds.toString().trim()));
        }
        List<Map.Entry<String, Integer>> list = sorted(boss, c.drops);
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

    private static int audit(boolean apply) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        if (!auditRunning.compareAndSet(false, true)) {
            mc.player.sendSystemMessage(Component.literal("§eDrop audit is already running."));
            return 1;
        }
        String player = mc.player.getName().getString();
        Path config = file();
        Path logs = FabricLoader.getInstance().getGameDir().resolve("logs");
        mc.player.sendSystemMessage(Component.literal("§eAuditing local drop logs in the background..."));
        Thread.ofVirtual().name("endsight-drop-audit").start(() -> {
            try {
                BossDropsAudit.Report report = BossDropsAudit.scan(logs, config, player);
                Path output = config.resolveSibling("drop-audit.txt");
                Files.createDirectories(output.getParent());
                Files.writeString(output, report.text(), StandardCharsets.UTF_8);
                mc.execute(() -> {
                    if (mc.player == null) return;
                    if (!apply) {
                        mc.player.sendSystemMessage(Component.literal("§aDrop audit ready: §f" + output + " §7("
                                + report.events() + " supported boss events). No counts changed. §f/drops audit apply"
                                + " §7makes the " + report.fixes().size() + " corrections it lists."));
                        return;
                    }
                    int done = applyAudit(report.fixes());
                    mc.player.sendSystemMessage(Component.literal("§aDrop audit applied: §f" + done + " §7of "
                            + report.fixes().size() + " corrections. The save from before is §fboss-drops-before-audit.txt§7."));
                });
            } catch (IOException | RuntimeException e) {
                mc.execute(() -> {
                    if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§cDrop audit failed: " + e.getMessage()));
                });
            } finally {
                auditRunning.set(false);
            }
        });
        return 1;
    }

    /**
     * The audit's corrections, made on the counts in memory and then saved. Editing the file
     * instead would be undone by the next save, which rewrites it from these maps. A count
     * that moved since the scan read the file is left alone. The file from before is kept.
     */
    static int applyAudit(List<BossDropsAudit.Fix> fixes) {
        // Kills are a field of their own, not a drop row; the audit names them "#kills".
        try {
            if (!Files.exists(file())) return 0;
            if (Files.getLastModifiedTime(file()).toMillis() != fileStamp) load();
            Files.copy(file(), file().resolveSibling("boss-drops-before-audit.txt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[Endsight] could not back up boss-drops.txt, audit not applied: " + e);
            return 0;
        }
        int done = 0;
        for (BossDropsAudit.Fix f : fixes) {
            Count base = of(loaded, f.boss()), fresh = of(unsaved, f.boss());
            if (f.item().equals(BossDropsAudit.KILLS)) {
                if (base.kills != f.from()) continue;
                base.kills = f.to();
                lobbyTaken.merge(f.boss(), f.from() - f.to(), Integer::sum);
                done++;
                continue;
            }
            if (base.drops.getOrDefault(f.item(), 0) != f.from()) continue;
            int now = f.from() + fresh.drops.getOrDefault(f.item(), 0);
            if (f.to() == 0) {
                base.drops.remove(f.item());
                fresh.drops.remove(f.item());
            } else if (f.to() > now) {
                // Only the latest log can hold a drop not saved yet, and the fix counted it.
                fresh.drops.remove(f.item());
                base.drops.put(f.item(), f.to());
            } else {
                continue;
            }
            done++;
        }
        // A relabelled pet's tier line goes with its last row.
        Map<String, Count> after = totals();
        seen.keySet().removeIf(item -> PET_NAME.matcher(item).find() && !PET_RARITY.matcher(item).find()
                && after.values().stream().noneMatch(c -> c.drops.containsKey(item)));
        save();
        return done;
    }

    private static void save() {
        // Someone else wrote the file since we read it: take theirs as the base.
        try {
            if (Files.exists(file()) && Files.getLastModifiedTime(file()).toMillis() != fileStamp) load();
        } catch (IOException ignored) {
        }
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), savedLines(), StandardCharsets.UTF_8);
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
        lobbyTaken.clear();
        if (!Files.exists(file())) return;
        try {
            readCounts(Files.readAllLines(file(), StandardCharsets.UTF_8));
            fileStamp = Files.getLastModifiedTime(file()).toMillis();
        } catch (IOException | RuntimeException e) {
            System.err.println("[Endsight] could not read boss-drops.txt: " + e);
        }
    }

    private static List<String> savedLines() {
        List<String> lines = new ArrayList<>();
        lines.add("# all-time: kill, drop, tier; cost<TAB>boss<TAB>starts<TAB>coins<TAB>unknown<TAB>historical estimate; dragons by kind");
        totals().forEach((boss, c) -> {
            lines.add("kill\t" + boss + "\t" + c.kills);
            c.drops.forEach((item, n) -> lines.add("drop\t" + boss + "\t" + item + "\t" + n));
            if (isSlayer(boss)) lines.add("cost\t" + boss + "\t" + c.starts + "\t" + c.spawnCost
                    + "\t" + c.unknownCosts + "\t" + c.historicalCost);
        });
        seen.forEach((item, t) -> lines.add("tier\t" + item + "\t" + t));
        lobbyTaken.forEach((boss, n) -> lines.add("lobby\t" + boss + "\t" + n));
        return lines;
    }

    private static void readCounts(List<String> lines) {
        Set<String> costRecords = new java.util.HashSet<>();
        for (String line : lines) {
            String[] p = line.split("\t");
            if (p[0].equals("kill") && p.length == 3) of(loaded, p[1]).kills = Integer.parseInt(p[2]);
            // Retain the existing loot rules; this migration never changes kill/drop counts.
            else if (p[0].equals("drop") && p.length == 4 && counted(p[1], p[2])) of(loaded, p[1]).drops.put(p[2], Integer.parseInt(p[3]));
            else if (p[0].equals("tier") && p.length == 3) seen.put(p[1], Integer.parseInt(p[2]));
            else if (p[0].equals("lobby") && p.length == 3) lobbyTaken.put(p[1], Integer.parseInt(p[2]));
            else if (p[0].equals("cost") && p.length == 6) {
                Count c = of(loaded, p[1]);
                c.starts = Integer.parseInt(p[2]);
                c.spawnCost = Long.parseLong(p[3]);
                c.unknownCosts = Integer.parseInt(p[4]);
                c.historicalCost = Long.parseLong(p[5]);
                costRecords.add(bossKey(p[1]));
            }
        }
        for (String boss : List.of(REVENANT, VOIDGLOOM)) {
            // Only legacy files lack a cost record. Persisting even a zero prevents
            // this requested T5 assumption from being applied again on a later load.
            if (!costRecords.contains(boss)) {
                Count c = of(loaded, boss);
                c.historicalCost = SlayerCosts.historicalCost(boss, c.kills);
            }
        }
    }

    private static boolean isSlayer(String boss) {
        return REVENANT.equals(boss) || VOIDGLOOM.equals(boss);
    }

    private static String costText(Count c) {
        long coins = c.spawnCost + c.historicalCost;
        String value = (c.historicalCost > 0 ? "~" : "") + "-" + compactCoins(coins);
        return c.unknownCosts > 0 ? (coins == 0 ? "Unknown" : value + " + ?") : value;
    }

    private static String compactCoins(long coins) {
        if (coins < 1_000) return String.valueOf(coins);
        long divisor = coins >= 1_000_000_000 ? 1_000_000_000 : coins >= 1_000_000 ? 1_000_000 : 1_000;
        String suffix = divisor == 1_000_000_000 ? "b" : divisor == 1_000_000 ? "m" : "k";
        return String.format(Locale.ROOT, "%.1f", coins / (double) divisor).replaceFirst("\\.0$", "") + suffix;
    }

    private static long addCoins(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    private static long pricedValue(List<Map.Entry<String, Integer>> entries) {
        long result = 0;
        for (Map.Entry<String, Integer> e : entries)
            result = addCoins(result, NpcPrices.value(plain(e.getKey()), e.getValue()));
        return result;
    }

    // ── drawing ───────────────────────────────────────────────────────────────

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.level == null || mc.options.hideGui) return;
        long now = System.currentTimeMillis();
        boolean recentDragon = dragonHudVisible(now, Area.where() == Area.Where.END);
        if (DRAGON.equals(showing) && !recentDragon) return;
        // A paid attempt has something to show even when its boss was never killed.
        Count c = count(session, showing);
        if (c.kills == 0 && c.starts == 0 && !TOTAL.equals(mode) && !recentDragon) return;
        HudLayout.draw("drops.boss", g, mc.font, false);
    }

    private static boolean dragonHudVisible(long now, boolean inEnd) {
        return (ownDragonUp && inEnd) || (dragonActivityAt > 0 && now >= dragonActivityAt
                && now - dragonActivityAt < DRAGON_LINGER_MS);
    }

    /**
     * The drops worth a row, rarest first, then most dropped, then the name.
     *
     * The lowest-tier selector applies to dragons only. A dragon drops piles of tat worth
     * filtering, but every slayer drop has a price - Viscera sells to an NPC, an Ovoid has
     * a sale value - so a floor there only hides a row you came to read: a common Viscera
     * was counted and never shown, which looks like the tracker missed it.
     */
    private static List<Map.Entry<String, Integer>> sorted(String boss, Map<String, Integer> drops) {
        int floor = isSlayer(boss) ? 0 : Drops.TIERS.indexOf(minTier);
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
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
        return compactLayout ? drawCompact(g, font, x, y, sample) : drawClassic(g, font, x, y, sample);
    }

    private static int[] drawClassic(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
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
        List<Map.Entry<String, Integer>> list = sample ? new ArrayList<>(drops.entrySet()) : sorted(boss, drops);
        String costs = costText(c);
        long priced = pricedValue(list);
        String pricedText = priced > 0 ? compactCoins(priced) : null;
        int w = font.width(title) + 20 + font.width(chip) + 12 + (kindChip == null ? 0 : font.width(kindChip) + 12);
        for (Map.Entry<String, Integer> e : list) {
            long worth = NpcPrices.value(plain(e.getKey()), e.getValue());
            String tail = String.valueOf(e.getValue()) + (worth > 0 ? "  " + compactCoins(worth) : "");
            w = Math.max(w, 8 + font.width(e.getKey()) + 12 + font.width(tail));
        }
        if (list.isEmpty()) w = Math.max(w, 8 + font.width("Nothing worth a row yet"));
        if (isSlayer(boss)) w = Math.max(w, Readout.width(font, "Spawn costs", costs));
        if (pricedText != null) w = Math.max(w, Readout.width(font, "Known loot value", pricedText));
        int h = Readout.ROW_H + 3 + (Readout.ROW_H + 2) + Math.max(1, list.size()) * (Readout.ROW_H + 2)
                + (isSlayer(boss) ? Readout.ROW_H + 2 : 0)
                + (pricedText != null ? Readout.ROW_H + 2 : 0);
        if (g != null) {
            Readout.tick(g, x, y, w, Theme.accent());
            Draw.text(g, font, title, Readout.left(x), y, Theme.muted());
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
            if (list.isEmpty()) {
                Draw.text(g, font, "Nothing worth a row yet", Readout.left(x), ry, Theme.dim());
                ry += Readout.ROW_H + 2;
            }
            for (Map.Entry<String, Integer> e : list) {
                int colour = Rarity.colour(e.getKey(), Drops.colour(tier(e.getKey())));
                Draw.text(g, font, e.getKey(), Readout.left(x), ry, colour | 0xFF000000);
                int numbersRight = Readout.right(x, w);
                long worth = NpcPrices.value(plain(e.getKey()), e.getValue());
                if (worth > 0) {
                    String money = compactCoins(worth);
                    Draw.textRight(g, font, money, numbersRight, ry, Theme.dim());
                    numbersRight -= font.width(money) + 6;
                }
                Draw.textRight(g, font, String.valueOf(e.getValue()), numbersRight, ry, Theme.text());
                ry += Readout.ROW_H + 2;
            }
            if (isSlayer(boss)) {
                Draw.text(g, font, "Spawn costs", Readout.left(x), ry, Theme.dim());
                Draw.textRight(g, font, costs, Readout.right(x, w), ry, Theme.neg());
                ry += Readout.ROW_H + 2;
            }
            if (pricedText != null) {
                Draw.text(g, font, "Known loot value", Readout.left(x), ry, Theme.dim());
                Draw.textRight(g, font, pricedText, Readout.right(x, w), ry, Theme.pos());
            }
        }
        return new int[]{w, h};
    }

    private record ProfitRow(String name, int count, long value) { }

    /** Value-sorted Slayer view. Unknown items and costs keep the footer honest. */
    private static int[] drawSlayerProfit(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        String boss = sample ? VOIDGLOOM : showing;
        Count c = sample ? new Count() : count(view(), boss);
        Map<String, Integer> drops = c.drops;
        if (sample) {
            c.kills = 12;
            c.starts = 14;
            c.spawnCost = 70_000_000;
            drops = Map.of("Judgement Core", 1, "Transmission Tuner", 12, "Null Atom", 9);
        }
        String name = VOIDGLOOM.equals(boss) ? "Voidgloom Seraph" : "Revenant Horror";
        String title = name + " Profit Tracker";
        String chip = TOTAL.equals(mode) ? "total" : "session";
        List<ProfitRow> rows = new ArrayList<>();
        long loot = 0;
        int unpriced = 0;
        for (Map.Entry<String, Integer> e : drops.entrySet()) {
            long value = sample ? switch (e.getKey()) {
                case "Judgement Core" -> 2_100_000_000L;
                case "Transmission Tuner" -> 2_400_000L;
                default -> 900_000L;
            } : NpcPrices.value(plain(e.getKey()), e.getValue());
            loot = addCoins(loot, value);
            if (value == 0) unpriced++;
            rows.add(new ProfitRow(e.getKey(), e.getValue(), value));
        }
        rows.sort((a, b) -> {
            int byValue = Long.compare(b.value(), a.value());
            return byValue != 0 ? byValue : a.name().compareToIgnoreCase(b.name());
        });
        List<ProfitRow> visible = new ArrayList<>();
        int cheap = 0;
        for (ProfitRow row : rows) {
            if (row.value() > 0 && row.value() < cheapSlayerFloor) cheap++;
            else visible.add(row);
        }
        int overflow = Math.max(0, visible.size() - 18);
        if (overflow > 0) visible = new ArrayList<>(visible.subList(0, 18));
        long cost = addCoins(c.spawnCost, c.historicalCost);
        long net = loot - cost;
        boolean complete = unpriced == 0 && c.unknownCosts == 0 && c.historicalCost == 0;
        String profitLabel = complete ? "Tracked Profit: " : "Tracked Profit (partial): ";
        String netText = String.format(Locale.ROOT, "%,d coins", net);
        int rowH = Readout.ROW_H + 1;
        int w = font.width(title) + 8 + font.width(chip);
        for (ProfitRow row : visible) {
            String left = String.format(Locale.ROOT, "%,dx ", row.count()) + row.name() + ": ";
            String right = row.value() > 0 ? compactCoins(row.value()) : "?";
            w = Math.max(w, font.width(left) + font.width(right));
        }
        w = Math.max(w, font.width(profitLabel + netText));
        w = Math.max(w, font.width("Slayer Spawn Cost: " + costText(c)));
        if (cheap > 0) w = Math.max(w, font.width(cheap + " cheap items are hidden."));
        int extra = (cheap > 0 ? 1 : 0) + (overflow > 0 ? 1 : 0);
        int h = rowH * (1 + Math.max(1, visible.size()) + extra + 3);

        if (g != null) {
            Draw.text(g, font, title, x, y, 0xFFFFFF55);
            modeL = modeR = kindL = kindR = 0;
            if (!sample && Minecraft.getInstance().screen instanceof ChatScreen) {
                int cw = font.width(chip);
                int cx = x + w - cw;
                Draw.text(g, font, chip, cx, y, Theme.dim());
                modeL = cx - x;
                modeR = cx + cw - x;
            }
            int ry = y + rowH;
            if (visible.isEmpty()) {
                Draw.text(g, font, "No Slayer drops yet", x, ry, Theme.dim());
                ry += rowH;
            }
            for (ProfitRow row : visible) {
                String prefix = String.format(Locale.ROOT, "%,dx ", row.count());
                int colour = Rarity.colour(row.name(), Drops.colour(tier(row.name()))) | 0xFF000000;
                Draw.text(g, font, prefix, x, ry, Theme.dim());
                int nx = x + font.width(prefix);
                Draw.text(g, font, row.name(), nx, ry, colour);
                int vx = nx + font.width(row.name());
                Draw.text(g, font, ": " + (row.value() > 0 ? compactCoins(row.value()) : "?"), vx, ry,
                        row.value() > 0 ? 0xFFFFAA00 : Theme.neg());
                ry += rowH;
            }
            if (cheap > 0) {
                Draw.text(g, font, cheap + " cheap items are hidden.", x, ry, Theme.dim());
                ry += rowH;
            }
            if (overflow > 0) {
                Draw.text(g, font, overflow + " more drops not shown.", x, ry, Theme.dim());
                ry += rowH;
            }
            Draw.text(g, font, "Slayer Spawn Cost: ", x, ry, Theme.dim());
            Draw.text(g, font, costText(c), x + font.width("Slayer Spawn Cost: "), ry, Theme.neg());
            ry += rowH;
            Draw.text(g, font, profitLabel, x, ry, Theme.dim());
            Draw.text(g, font, netText, x + font.width(profitLabel), ry, net < 0 ? Theme.neg() : 0xFFFFAA00);
            ry += rowH;
            Draw.text(g, font, "Bosses killed: ", x, ry, Theme.dim());
            Draw.text(g, font, String.format(Locale.ROOT, "%,d", c.kills), x + font.width("Bosses killed: "), ry, 0xFFFFFF55);
        }
        return new int[]{w, h};
    }

    private static int[] drawCompact(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        String boss = showing;
        if (isSlayer(boss) || sample) return drawSlayerProfit(g, font, x, y, sample);
        Count c = count(view(), boss);
        Map<String, Integer> drops = c.drops;
        String kindChip = DRAGON.equals(boss) ? (ALL.equals(kind) ? ALL : kind.toLowerCase(Locale.ROOT)) : null;
        String name = switch (boss) {
            case VOIDGLOOM -> "Voidgloom Seraph";
            case REVENANT -> "Revenant Horror";
            case GOLEM -> "Endstone Protector";
            case DRAGON -> ALL.equals(kind) ? "Dragon" : kind + " Dragon";
            default -> boss;
        };
        String title = "\u00a7l" + name + " Drops";
        String chip = TOTAL.equals(mode) ? "total" : "session";
        String killLabel = "Bosses killed: ";
        String kills = String.format(Locale.ROOT, "%,d", c.kills);
        String costLabel = "Spawn costs: ";
        String costs = costText(c);
        List<Map.Entry<String, Integer>> list = sample ? new ArrayList<>(drops.entrySet()) : sorted(boss, drops);
        int w = font.width(title) + 8 + font.width(chip) + (kindChip == null ? 0 : font.width(kindChip) + 8);
        for (Map.Entry<String, Integer> e : list) w = Math.max(w, font.width(dropPrefix(e.getValue()) + e.getKey()));
        if (list.isEmpty()) w = Math.max(w, font.width("Nothing worth a row yet"));
        w = Math.max(w, font.width(killLabel + kills));
        if (isSlayer(boss)) w = Math.max(w, font.width(costLabel + costs));
        int rowH = Readout.ROW_H + 1;
        int h = rowH * (1 + Math.max(1, list.size()) + 1 + (isSlayer(boss) ? 1 : 0));

        if (g != null) {
            Draw.text(g, font, title, x, y, 0xFFFFFF55);
            // Keep the existing controls, but only reveal them when the mouse is free.
            // Reserve their width so opening chat never shifts the HUD underneath it.
            modeL = modeR = kindL = kindR = 0;
            if (!sample && Minecraft.getInstance().screen instanceof ChatScreen) {
                int cw = font.width(chip);
                int cx = x + w - cw;
                Draw.text(g, font, chip, cx, y, Theme.dim());
                modeL = cx - x;
                modeR = cx + cw - x;
                if (kindChip != null) {
                    int kw = font.width(kindChip);
                    int kx = cx - 8 - kw;
                    Draw.text(g, font, kindChip, kx, y, Theme.dim());
                    kindL = kx - x;
                    kindR = kx + kw - x;
                }
            }
            int ry = y + rowH;
            if (list.isEmpty()) {
                Draw.text(g, font, "Nothing worth a row yet", x, ry, Theme.dim());
                ry += rowH;
            }
            for (Map.Entry<String, Integer> e : list) {
                String prefix = dropPrefix(e.getValue());
                int colour = Rarity.colour(e.getKey(), Drops.colour(tier(e.getKey())));
                Draw.text(g, font, prefix, x, ry, 0xFFAAAAAA);
                Draw.text(g, font, e.getKey(), x + font.width(prefix), ry, colour | 0xFF000000);
                ry += rowH;
            }
            if (isSlayer(boss)) {
                Draw.text(g, font, costLabel, x, ry, 0xFFAAAAAA);
                Draw.text(g, font, costs, x + font.width(costLabel), ry, 0xFFFF5555);
                ry += rowH;
            }
            Draw.text(g, font, killLabel, x, ry, 0xFFAAAAAA);
            Draw.text(g, font, kills, x + font.width(killLabel), ry, 0xFFFFFF55);
        }
        return new int[]{w, h};
    }

    private static String dropPrefix(int count) {
        return String.format(Locale.ROOT, "%,dx ", count);
    }
}
