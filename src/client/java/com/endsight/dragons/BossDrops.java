package com.endsight.dragons;

import com.endsight.hud.Area;
import com.endsight.hud.HudLayout;
import com.endsight.hud.HudCard;
import com.endsight.hud.TrackerHud;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.endsight.qol.Drops;
import com.endsight.qol.NpcPrices;
import com.endsight.qol.Rarity;
import com.endsight.slayers.SlayerCosts;
import com.endsight.storage.Recipes;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
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
 * Counts kills and drops from dragons, the Warden, the Protector, and the two
 * slayers. Detailed results are available through /drops; the small Dragon Totals
 * HUD reads the same counts as the configurable Boss Drops readout.
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
 * /drops prints all-time counts, or this session's with "session" on the end.
 */
public final class BossDrops {

    private BossDrops() {
    }

    static final String DRAGON = "Dragon", WARDEN = "Warden", GOLEM = "Golem", REVENANT = "Revenant", VOIDGLOOM = "Voidgloom", RIFTBORN = "Riftborn";
    private static final String LEGACY_ENDERMAN = "Enderman (legacy)", UNKNOWN_ENDERMAN = "Enderman (unidentified)";
    private static final String FAMILY_SCHEMA = "# slayer-family-v2";
    private static final List<String> BOSSES = List.of(DRAGON, WARDEN, GOLEM, REVENANT, VOIDGLOOM, RIFTBORN,
            LEGACY_ENDERMAN, UNKNOWN_ENDERMAN);
    private static final List<String> SLAYER_ITEMS = List.of(
            "Zombie Talisman", "Revenant Viscera", "Warden Heart", "Judgement Core",
            "Scythe Blade", "Shard of the Shredded", "Void Upgrade Stone",
            "High Class Archfiend Dice", "Beheaded Horror", "Rose of Lies",
            "Crypt Cache", "Undead Catalyst", "Revenant Catalyst",
            "Transmission Tuner", "Etherwarp Conduit", "Etherwarp Merger", "Null Atom", "Null Ovoid",
            "Rift Pass", "Seraph's Halo", "Whetstone", "Black Hole Fragment", "Combat XP Boost", "Seraph Quill",
            "Streak Anchor", "Shadow Warp Scroll", "Golden Eye", "Seraph's Tally", "Seraph Plating", "Seraph's Plating", "Soulflow Pile",
            "Void Talisman", "Void Fang", "Flesh Fang", "Riftstring");
    /**
     * Each kind of dragon is its own boss - "Golden Dragon", "Protector Dragon" - because
     * the drops worth counting are the kind's own: a Golden Dragon Chestplate is one in
     * so many Golden dragons, and a kill count over every dragon says nothing about it.
     * /drops dragon adds all kinds up.
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
    private static final Pattern TARGET = Pattern.compile("Slay ([\\d,]+) Combat XP worth of (\\w+)");
    private static final Pattern QUEST_FAMILY = Pattern.compile("(?:^|Slayer:\\s*)(Riftborn Seraph|Voidgloom Seraph|Revenant Horror|Atoned Horror)(?:\\s+(IV|III|II|I|V))?\\b");
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
    private static final String EGG_SPAWNED = "Egg has Spawned";
    private static final String WARDEN_DEAD = "The Warden has been defeated";
    private static final String GOLEM_DEAD = "The Endstone Protector has been defeated";
    private static final String SLAYER_DEAD = "SLAYER BOSS SLAIN";
    private static final String SLAYER_SPAWNING = "SLAYER BOSS SPAWNING";
    private static final String SLAYER_STARTED = "SLAYER QUEST STARTED";
    private static final String SLAYER_FAILED = "SLAYER QUEST FAILED";
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

    private static boolean enabled = true;
    private static boolean showHud = true;
    private static boolean compactHud = true;
    private static String hudPeriod = "Session";
    private static String displayedBoss = DRAGON;
    private static String minTier = "Rare and up";
    /** Which drops make the command list - everything is counted. */

    /** The two quest families have their own controls and placement, while sharing the loot recorder. */
    private static final class ProfitHud {
        final String id;
        final String boss;          // unused since the merge; always null
        boolean enabled = true, compact = true;
        String period = "Session";

        ProfitHud(String id, String boss) {
            this.id = id;
            this.boss = boss;
        }
    }
    // One card, like Boss Drops. There were two - "Voidgloom Profit" and "Riftborn
    // Profit" - which is two menu entries, two positions and two Period settings for a
    // thing you can only ever be doing one of at a time. They were never on screen
    // together anyway, so the split bought nothing and cost a duplicate of every
    // setting. The title still names the family you are on.
    private static final ProfitHud slayerHud = new ProfitHud("slayer.profit", null);

    /** One boss: kills, and drops by item. */
    private static final class Count {
        int kills;
        int starts, unknownCosts;
        long spawnCost, historicalCost, activeMs;
        boolean incompleteTime;
        final Map<String, Integer> drops = new LinkedHashMap<>();
    }

    /** One boss dying, held open until its box and its drops have arrived. */
    private static final class Death {
        final String boss;
        final long at;
        int place = 99;
        boolean dead;
        Object level;
        RewardWindow reward, atomReward;
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
    /** The last dragon killed, by kind, for the "has obtained" lines that follow it. */
    private static String lastDragon = DRAGON;
    private static String slayer = REVENANT;
    /** Menu names identify a quest, while Endermen alone cannot distinguish T4 from T5. */
    private static final Map<Long, String> endermanFamilies = new HashMap<>(Map.of(
            4_000L, VOIDGLOOM, 11_000L, VOIDGLOOM, 13_750L, RIFTBORN));
    private static boolean profitQuestActive;
    private static boolean profitSawStart;
    private static long profitTickAt, profitLastLine;
    private static Object profitWorld;
    private static Death death;
    private static RewardWindow rewardWindow;
    /**
     * The same measurement for Null Atoms. A T5 Voidgloom pays atoms every kill the way
     * a normal one pays Ovoids - straight into the inventory, with no drop message - so
     * the tracker, which only listened for drop lines, counted none of them. T5 is
     * identified as Riftborn (13,750 quest XP and the observed Riftborn Seraph entity),
     * so measuring Voidgloom alone missed the very family that needed this baseline.
     * A T4's announced atom also lands in the inventory; count the larger observation.
     */
    private static RewardWindow atomWindow;
    /** Every nearby drop line, for a kill line that arrives just after its loot. */
    private record HeldDrop(Drops.Drop drop, long at, Object level, String bonusBoss) { }
    private static final List<HeldDrop> held = new ArrayList<>();
    private static final int HELD_LIMIT = 64;
    private static Object messageWorld;
    private static int eyes;
    private static long summaryAt, dragonKillAt;
    /**
     * Dragons since the last Superior and since the last Ender Dragon pet. A save from
     * before these existed has totals but no order, so each starts unknown and counts up
     * from install - shown as "37+" - until a real Superior or pet pins it.
     */
    private static int sinceSuperior, sincePet;
    private static boolean superiorKnown, petKnown;
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
                sum.activeMs += c.activeMs;
                sum.incompleteTime |= c.incompleteTime;
                c.drops.forEach((item, n) -> sum.drops.merge(item, n, Integer::sum));
            });
        }
        return out;
    }

    /** Dragons killed of one kind, or of every kind for null; this session or all time. */
    static int dragonKills(String kind, boolean allTime) {
        // Dragon Totals asks once per kind in both the measuring and drawing pass.
        // Merging every boss's complete loot history here made a count-only HUD
        // allocate that history dozens of times per frame as the save grew.
        return allTime ? dragonKills(loaded, kind) + dragonKills(unsaved, kind) : dragonKills(session, kind);
    }

    private static int dragonKills(Map<String, Count> counts, String kind) {
        if (kind != null) {
            Count count = counts.get(kind + " Dragon");
            return count == null ? 0 : count.kills;
        }
        int kills = 0;
        for (Map.Entry<String, Count> entry : counts.entrySet())
            if (isDragon(entry.getKey())) kills += entry.getValue().kills;
        return kills;
    }

    static int sinceSuperior() { return sinceSuperior; }

    static boolean superiorKnown() { return superiorKnown; }

    static int sincePet() { return sincePet; }

    static boolean petKnown() { return petKnown; }

    static List<String> dragonKinds() { return KINDS; }

    public static Module module() {
        return new Module("dragon.bossdrops", "Boss Drops",
                "Dragon, Warden and Protector loot; Slayer quests have their own profit tracker.", "Trackers",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Section("Display"),
                        new Setting.Toggle("Show on HUD", "Show the latest boss's kills and drops.",
                                () -> showHud, v -> showHud = v),
                        new Setting.Choice("Period", "Switch the counts shown on the HUD.",
                                List.of("Session", "All Time"), () -> hudPeriod, v -> hudPeriod = v),
                        new Setting.Toggle("Compact list", "Show the five highest-value items; /drops keeps the full list.",
                                () -> compactHud, v -> compactHud = v),
                        new Setting.Choice("Show from",
                                "Lowest dragon drop tier printed by /drops.",
                                Drops.TIERS, () -> minTier, v -> minTier = v),
                        new Setting.Section("Prices"),
                        new Setting.Toggle("Learn Slayer sale prices", "Use a Slayer drop's value after its first sale; off uses custom or built-in prices.",
                                NpcPrices::learning, NpcPrices::learning),
                        new Setting.Action("Slayer drop prices", "Edit prices and reload a shared Slayer price file.",
                                "Edit", DropPricesScreen::open)));
    }

    public static Module slayerProfitModule() { return profitModule(slayerHud); }

    private static Module profitModule(ProfitHud hud) {
        return new Module(hud.id, "Slayer Profit",
                "Loot, spawn costs and profit per hour during your Slayer quest.", "Trackers",
                () -> hud.enabled, v -> hud.enabled = v,
                List.of(
                        new Setting.Choice("Period", "Switch the counts; click the HUD label in chat or inventory.",
                                List.of("Session", "All Time"), () -> hud.period, v -> hud.period = v),
                        new Setting.Toggle("Compact list", "Show the five highest-value items; profit still includes every priced drop.",
                                () -> hud.compact, v -> hud.compact = v),
                        new Setting.Toggle("Learn Slayer sale prices", "Use a Slayer drop's value after its first sale; off uses custom or built-in prices.",
                                NpcPrices::learning, NpcPrices::learning),
                        new Setting.Action("Slayer drop prices", "Edit prices shared by your Slayer profit trackers.",
                                "Edit", DropPricesScreen::open)));
    }

    private static boolean trackingEnabled() { return enabled || slayerHud.enabled; }

    public static void init() {
        load();
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "boss_drops"),
                (g, delta) -> drawHud(g));
        HudLayout.register("dragon.bossdrops", "Boss Drops", 0.006f, 0.62f, BossDrops::drawHudAt);
        HudLayout.register(slayerHud.id, "Slayer Profit", 0.006f, 0.62f,
                (g, font, x, y, sample) -> drawProfitHudAt(slayerHud, g, font, x, y, sample));
        NpcPrices.init();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!trackingEnabled() || overlay) return;
            String raw = message.getString();
            String line = Zealots.strip(raw).trim();
            if (DragonTimer.isPlayerChat(line)) return;
            messageWorld = Minecraft.getInstance().level;
            long now = System.currentTimeMillis();
            rewardQuestLine(line);
            profitQuestLine(line, now);
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
                    Count c = of(counts, resolvedStartBoss(start.boss(), line));
                    c.starts++;
                    if (coins < 0) c.unknownCosts++;
                    else c.spawnCost += coins;
                }
                show(resolvedStartBoss(start.boss(), line));
                save();
            }
            if (line.contains(SLAYER_SPAWNING)) {
                // A fast next kill can begin before the previous 2.5s loot wait
                // expires. Close that death before taking this boss's baseline.
                settlePreviousSlayer();
                Minecraft mc = Minecraft.getInstance();
                String item = rewardItem(slayer);
                rewardSpawned(slayer, item, inventoryCount(mc, item), mc.player == null ? null : mc.level, now);
                String atom = atomItem(slayer);
                atomSpawned(slayer, atom, inventoryCount(mc, atom), mc.player == null ? null : mc.level, now);
                return;
            }

            Drops.Drop d = Drops.parse(raw);
            if (d != null) {
                if (line.startsWith("BONUS! ")) {
                    // This generic wording also pays altar sacrifices. Only an
                    // armed personal Slayer fight can make it boss loot.
                    if (!slayerBonusEligible(d.item(), now, messageWorld)) return;
                    if (slayerDeathWindow(now, messageWorld)) death.drops.add(d);
                    else hold(d, now, true);
                    return;
                }
                String reward = slayerDeathWindow(now, messageWorld) ? rewardItem(death.boss) : rewardItem(slayer);
                if (lootName(d.item()).equalsIgnoreCase(reward) && slayerBonusEligible(d.item(), now, messageWorld)) {
                    // Ovoids also come from ordinary End mobs. Only a confirmed
                    // personal Slayer window lets their announced stack supplement
                    // the inventory snapshot, rather than attributing nearby mobs.
                    if (slayerDeathWindow(now, messageWorld)) death.drops.add(d);
                    else hold(d, now, true);
                    return;
                }
                // Dragon loot is announced by name and credited that way; a dragon's
                // "loot number → Golden Dragon Leggings" landing in the second a Warden
                // died is the dragon's, not the Warden's.
                String lower = d.item().toLowerCase(Locale.ROOT);
                if (!NEST.contains(lower) && !MINED.contains(lower)) {
                    if (death != null) {
                        if ((isDragon(death.boss) || !lower.contains("dragon")) && belongs(death.boss, d.item())) death.drops.add(d);
                        else hold(d, now);
                    }
                    else hold(d, now);
                }
                return;
            }
            Matcher m = TARGET.matcher(line);
            if (m.find()) {
                slayer = questBoss(line);
                return;
            }
            if (EYE_PLACED.matcher(line).find()) {
                eyes++;
                show(DRAGON);
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
                // Missing a spawn line must still leave each SLAIN as its own kill.
                settlePreviousSlayer();
                Death x = open(slayer, now, true);
                x.dead = true;
                x.place = 1;
                x.reward = takeReward();
                x.atomReward = takeAtomReward();
                return;
            }
            m = OBTAINED.matcher(line);
            if (m.find()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && m.group(1).equalsIgnoreCase(mc.player.getName().getString())) {
                    String item = m.group(2).trim();
                    // A dragon's kill is counted 2.5 s after it de-spawns, and in his logs about
                    // one obtained line in six landed inside that wait (201 of 1,310 within 2 s).
                    // Dropped straight away, a pet reset "since pet" and the kill then made it
                    // 1. Held with the kill, it is counted after it.
                    if (death != null && isDragon(death.boss)) death.drops.add(obtainedDrop(raw, item));
                    else drop(lastDragon, obtainedDrop(raw, item));
                }
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long now = System.currentTimeMillis();
            clockProfit(now, mc.player == null ? null : mc.level);
            if (mc.player != null && menuTicks % 5 == 0) observeQuestSidebar(mc, now);
            rewardWorld(mc.player == null ? null : mc.level);
            if (death != null && now - death.at > HOLD_MS) settle();
            if (summaryAt != 0 && now - summaryAt >= SUMMARY_MS) settleSummary();
            if (mc.player == null) {
                catalysts = -1;
                eyes = 0;
                rewardWindow = null;
                atomWindow = null;
                held.clear();
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
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            clockProfit(System.currentTimeMillis(), mc.player == null ? null : mc.level);
            save();
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!HudLayout.interactiveScreen(screen)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                if (event.button() != 0 || client.options.hideGui) return true;
                if (bossHudVisible()) {
                    int[] bounds = HudLayout.bounds("dragon.bossdrops");
                    if (periodHit(bounds, HudLayout.scale("dragon.bossdrops"), event.x(), event.y(), client.font.width(periodText(hudPeriod)))) {
                        hudPeriod = hudPeriod.equals("Session") ? "All Time" : "Session";
                        com.endsight.Config.save(com.endsight.EndsightClient.registry());
                        return false;
                    }
                }
                for (ProfitHud hud : List.of(slayerHud)) {
                    if (!profitHudVisible(hud)) continue;
                    int[] bounds = HudLayout.bounds(hud.id);
                    if (periodHit(bounds, HudLayout.scale(hud.id), event.x(), event.y(), client.font.width(periodText(hud.period)))) {
                        hud.period = hud.period.equals("Session") ? "All Time" : "Session";
                        com.endsight.Config.save(com.endsight.EndsightClient.registry());
                        return false;
                    }
                }
                return true;
            });
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> {
            // /drops is all time; "session" on the end gives this launch instead.
            var root = ClientCommands.literal("drops").executes(c -> sayAll(false));
            root.then(ClientCommands.literal("session").executes(c -> sayAll(true)));
            root.then(ClientCommands.literal("audit").executes(c -> audit(false))
                    .then(ClientCommands.literal("apply").executes(c -> audit(true))));
            // /drops dragon is every kind together; /drops golden one kind.
            for (String boss : BOSSES) {
                if (boss.equals(LEGACY_ENDERMAN) || boss.equals(UNKNOWN_ENDERMAN)) continue;
                root.then(ClientCommands.literal(boss.toLowerCase(Locale.ROOT)).executes(c -> say(boss, false))
                        .then(ClientCommands.literal("session").executes(c -> say(boss, true))));
            }
            root.then(ClientCommands.literal("zombie").executes(c -> say(REVENANT, false))
                    .then(ClientCommands.literal("session").executes(c -> say(REVENANT, true))));
            root.then(ClientCommands.literal("enderman").executes(c -> say(VOIDGLOOM, false))
                    .then(ClientCommands.literal("session").executes(c -> say(VOIDGLOOM, true))));
            root.then(ClientCommands.literal("legacy").executes(c -> say(LEGACY_ENDERMAN, false))
                    .then(ClientCommands.literal("session").executes(c -> say(LEGACY_ENDERMAN, true))));
            root.then(ClientCommands.literal("unidentified").executes(c -> say(UNKNOWN_ENDERMAN, false))
                    .then(ClientCommands.literal("session").executes(c -> say(UNKNOWN_ENDERMAN, true))));
            for (String k : KINDS) {
                root.then(ClientCommands.literal(k.toLowerCase(Locale.ROOT)).executes(c -> say(k + " Dragon", false))
                        .then(ClientCommands.literal("session").executes(c -> say(k + " Dragon", true))));
            }
            dispatcher.register(root);
        });
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
        if (!trackingEnabled() || !(mc.screen instanceof AbstractContainerScreen<?> screen)) return;
        String title = Zealots.strip(screen.getTitle().getString()).trim();
        if (!title.equals("Revenant Horror") && !title.equals("Voidgloom Seraph") && !title.equals("Riftborn Seraph")) return;
        for (var slot : screen.getMenu().slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            String name = Zealots.strip(stack.getHoverName().getString()).trim();
            SlayerCosts.Quote quote = SlayerCosts.quote(name,
                    lore.lines().stream().map(c -> Zealots.strip(c.getString()).trim()).toList());
            spawnCosts.learn(quote);
            if (quote != null && quote.boss().equals(VOIDGLOOM)) {
                String family = namedQuestBoss(name);
                if (family != null) endermanFamilies.put(quote.xp(), family);
            }
        }
    }

    /** The current menu's named boss wins over defaults when its XP requirement changes. */
    private static String questBoss(String line) {
        Matcher target = TARGET.matcher(line);
        if (!target.find()) return null;
        String mob = target.group(2).toLowerCase(Locale.ROOT);
        if (mob.startsWith("zombie")) return REVENANT;
        if (!mob.startsWith("ender")) return null;
        try {
            return endermanFamilies.getOrDefault(Long.parseLong(target.group(1).replace(",", "")), UNKNOWN_ENDERMAN);
        } catch (NumberFormatException invalidXp) {
            return UNKNOWN_ENDERMAN;
        }
    }

    private static String namedQuestBoss(String line) {
        if (line == null) return null;
        Matcher name = QUEST_FAMILY.matcher(Zealots.strip(line).trim());
        if (!name.find()) return null;
        return switch (name.group(1)) {
            case "Riftborn Seraph" -> RIFTBORN;
            case "Voidgloom Seraph" -> "V".equals(name.group(2)) ? RIFTBORN : VOIDGLOOM;
            default -> REVENANT;
        };
    }

    private static String resolvedStartBoss(String mergedBoss, String line) {
        if (!mergedBoss.equals(VOIDGLOOM)) return mergedBoss;
        String fromTarget = questBoss(line);
        return fromTarget == null ? UNKNOWN_ENDERMAN : fromTarget;
    }

    private static void profitQuestLine(String line, long now) {
        clockProfit(now, profitWorld);
        if (line.contains(SLAYER_STARTED)) {
            profitQuestActive = true;
            profitSawStart = true;
            // A fresh quest must not inherit the previous quest's family before its target arrives.
            slayer = UNKNOWN_ENDERMAN;
            profitLastLine = now;
        }
        String target = questBoss(line);
        if (target != null) {
            slayer = target;
            profitQuestActive = true;
            profitLastLine = now;
            if (!profitSawStart) markIncompleteTime(slayer);
        }
        String named = namedQuestBoss(line);
        if (named != null && profitQuestActive) slayer = named;
        if (line.contains(SLAYER_SPAWNING)) {
            profitQuestActive = true;
            profitLastLine = now;
        }
        if (line.contains(SLAYER_DEAD) || line.contains(SLAYER_FAILED) || line.contains("SLAYER QUEST COMPLETE")) {
            profitQuestActive = false;
            profitSawStart = false;
        }
    }

    private static void observeQuestSidebar(Minecraft mc, long now) {
        if (!profitQuestActive) return;
        String family = namedQuestBoss(Area.sidebarLine(mc, "Slayer:"));
        if (family != null) {
            clockProfit(now, mc.level);
            slayer = family;
            if (!profitSawStart) markIncompleteTime(slayer);
        }
    }

    private static void markIncompleteTime(String boss) {
        for (Map<String, Count> counts : List.of(session, unsaved)) of(counts, boss).incompleteTime = true;
    }

    private static void clockProfit(long now, Object world) {
        if (profitWorld != null && profitWorld != world) {
            profitQuestActive = false;
            profitSawStart = false;
        }
        if (trackingEnabled() && profitQuestActive && world != null && world == profitWorld && profitTickAt > 0
                && now >= profitTickAt && now - profitLastLine < 15 * 60_000L) {
            long elapsed = now - profitTickAt;
            for (Map<String, Count> counts : List.of(session, unsaved)) of(counts, slayer).activeMs += elapsed;
        }
        profitTickAt = now;
        profitWorld = world;
    }

    /**
     * The death being read, opened now if there is none. A different boss still open
     * is settled first. {@code takeHeld}: a drop line just before this one was its.
     */
    private static Death open(String boss, long now, boolean takeHeld) {
        if (death != null && !death.boss.equals(boss)) settle();
        if (death == null) {
            death = new Death(boss, now);
            death.level = messageWorld;
        }
        held.removeIf(pending -> now < pending.at() || now - pending.at() >= BEFORE_MS);
        if (takeHeld) {
            var pending = held.iterator();
            while (pending.hasNext()) {
                HeldDrop entry = pending.next();
                if (entry.level() != death.level || entry.bonusBoss() != null && !entry.bonusBoss().equals(boss)
                        || !belongs(boss, entry.drop().item())) continue;
                death.drops.add(entry.drop());
                pending.remove();
            }
        }
        return death;
    }

    private static void hold(Drops.Drop drop, long now) {
        hold(drop, now, false);
    }

    private static void hold(Drops.Drop drop, long now, boolean slayerBonus) {
        held.removeIf(pending -> now < pending.at() || now - pending.at() >= BEFORE_MS);
        if (held.size() == HELD_LIMIT) held.removeFirst();
        // A bonus item can arrive before SLAIN immediately after the rare item.
        // Remembering only the last line replaced that rare drop with the bonus.
        held.add(new HeldDrop(drop, now, messageWorld, slayerBonus ? slayer : null));
    }

    private static boolean slayerBonusEligible(String item, long now, Object level) {
        if (level == null || item == null) return false;
        String name = plain(Zealots.strip(item)).trim().replace('\u2019', '\'');
        // Eyes are shared with altar rewards; saved history alone is not evidence
        // that another generic BONUS line belongs to the current Slayer fight.
        if (name.equalsIgnoreCase("Golden Eye") || name.equalsIgnoreCase("Summoning Eye")
                || SLAYER_ITEMS.stream().noneMatch(n -> n.equalsIgnoreCase(name))) return false;
        if (slayerDeathWindow(now, level)) return true;
        return profitQuestActive && now >= profitLastLine && now - profitLastLine < 15 * 60_000L
                && rewardWindow != null && isSlayer(rewardWindow.boss()) && rewardWindow.boss().equals(slayer)
                && rewardWindow.level() == level;
    }

    private static boolean slayerDeathWindow(long now, Object level) {
        return death != null && death.dead && isSlayer(death.boss) && death.level == level
                && now >= death.at && now - death.at <= HOLD_MS;
    }

    private static void settlePreviousSlayer() {
        Death previous = takeDeadSlayer();
        if (previous != null) settle(previous);
    }

    /** Only an already slain personal Slayer needs the next-fight boundary. */
    private static Death takeDeadSlayer() {
        return death != null && death.dead && isSlayer(death.boss) ? takeDeath() : null;
    }

    private static Death takeDeath() {
        Death previous = death;
        death = null;
        return previous;
    }

    /** Decide whose it was, and count it if it was yours. */
    private static void settle() { settle(takeDeath()); }

    private static void settle(Death d) {
        if (d == null) return;
        String reward = rewardItem(d.boss), atom = atomItem(d.boss);
        int observed = measured(d.reward, d.boss, reward), atoms = measured(d.atomReward, d.boss, atom);
        settle(d, observed, atoms, BossDrops::kill, BossDrops::drop, Drops::tierOf);
    }

    /** Inventory gain since the spawn; only touches the client when there is a baseline to compare. */
    private static int measured(RewardWindow window, String boss, String item) {
        if (window == null || item == null) return 0;
        Minecraft mc = Minecraft.getInstance();
        return observedReward(window, boss, item, mc.player == null ? null : mc.level, inventoryCount(mc, item));
    }

    // Keep the actual kill and payout calculation testable without booting a
    // client. The live path supplies its normal persistence and alert callbacks.
    private static void settle(Death d, int observed, java.util.function.Consumer<String> kill,
                               java.util.function.BiConsumer<String, Drops.Drop> payout,
                               java.util.function.ToIntFunction<String> tier) {
        settle(d, observed, 0, kill, payout, tier);
    }

    private static void settle(Death d, int observed, int observedAtoms, java.util.function.Consumer<String> kill,
                               java.util.function.BiConsumer<String, Drops.Drop> payout,
                               java.util.function.ToIntFunction<String> tier) {
        if (d == null) return;
        boolean mine = d.place <= PLACES
                || (d.dead && !d.drops.isEmpty())
                || (d.dead && WARDEN.equals(d.boss) && catalyst);
        if (WARDEN.equals(d.boss)) catalyst = false;
        if (!mine) return;
        kill.accept(d.boss);
        String reward = rewardItem(d.boss), atom = atomItem(d.boss);
        int announced = announcedQuantity(d.drops, reward), announcedAtoms = announcedQuantity(d.drops, atom);
        for (Drops.Drop x : d.drops) {
            String name = lootName(x.item());
            if ((reward == null || !name.equalsIgnoreCase(reward)) && (atom == null || !name.equalsIgnoreCase(atom)))
                payout.accept(d.boss, x);
        }
        // Each measured item is paid once, as the larger of what chat announced and what
        // the inventory gained: an announced atom also lands in the bag, so adding the
        // two would count it twice, and a silent T5 stack has nothing announced at all.
        if (reward != null) {
            int quantity = rewardQuantity(announced, observed);
            if (quantity > 0) payout.accept(d.boss, new Drops.Drop(reward, tier.applyAsInt(reward), quantity));
        }
        if (atom != null) {
            int quantity = rewardQuantity(announcedAtoms, observedAtoms);
            if (quantity > 0) payout.accept(d.boss, new Drops.Drop(atom, tier.applyAsInt(atom), quantity));
        }
    }

    /** The second item a kill pays straight into the inventory, or null. See {@link #atomWindow}. */
    private static String atomItem(String boss) {
        return VOIDGLOOM.equals(boss) || RIFTBORN.equals(boss) || UNKNOWN_ENDERMAN.equals(boss) ? "Null Atom" : null;
    }

    private static void atomSpawned(String boss, String item, int baseline, Object level, long at) {
        atomWindow = level == null || item == null ? null : new RewardWindow(boss, item, baseline, level, at);
    }

    private static RewardWindow takeAtomReward() {
        RewardWindow atoms = atomWindow;
        atomWindow = null;
        return atoms;
    }

    private static String rewardItem(String boss) {
        return switch (boss) {
            case REVENANT -> "Revenant Viscera";
            case VOIDGLOOM, RIFTBORN, UNKNOWN_ENDERMAN -> "Null Ovoid";
            default -> null;
        };
    }

    private static void rewardQuestLine(String line) {
        if (line.contains(SLAYER_STARTED) || line.contains(SLAYER_FAILED)) { rewardWindow = null; atomWindow = null; }
    }

    private static void rewardSpawned(String boss, String item, int baseline, Object level, long at) {
        rewardWindow = level == null || item == null ? null : new RewardWindow(boss, item, baseline, level, at);
    }

    private static RewardWindow takeReward() {
        RewardWindow reward = rewardWindow;
        rewardWindow = null;
        return reward;
    }

    private static void rewardWorld(Object level) {
        held.removeIf(pending -> pending.level() != level);
        if (rewardWindow != null && rewardWindow.level() != level) rewardWindow = null;
        if (atomWindow != null && atomWindow.level() != level) atomWindow = null;
        if (death != null && death.reward != null && death.reward.level() != level) death.reward = null;
        if (death != null && death.atomReward != null && death.atomReward.level() != level) death.atomReward = null;
    }

    private static int observedReward(RewardWindow reward, String boss, String item, Object level, int current) {
        // A long T5 fight is still the same quest. The former two-minute cutoff could
        // lose its guaranteed stack; quest transitions and world changes invalidate it now.
        return reward != null && reward.boss().equals(boss) && reward.item().equals(item)
                && level != null && reward.level() == level ? current - reward.baseline() : 0;
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

    private static int announcedQuantity(List<Drops.Drop> drops, String item) {
        if (item == null) return 0;
        long quantity = 0;
        for (Drops.Drop drop : drops)
            if (lootName(drop.item()).equalsIgnoreCase(item)) quantity += drop.quantity();
        return (int) Math.min(Integer.MAX_VALUE, quantity);
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
        if (isDragon(boss)) {
            dragonKillAt = System.currentTimeMillis();
            countTowardStreaks(boss);
        }
        of(session, boss).kills++;
        of(unsaved, boss).kills++;
        show(boss);
        save();
    }

    /** One more dragon toward both streaks; a Superior resets its own. */
    static void countTowardStreaks(String boss) {
        // A Golden Dragon comes from Golden Eyes, not the roll that makes a Superior, so
        // it is no chance missed; it still counts toward the pet.
        if ("Superior Dragon".equals(boss)) {
            sinceSuperior = 0;
            superiorKnown = true;
        } else if (!"Golden Dragon".equals(boss)) sinceSuperior++;
        sincePet++;
    }

    /** The Ender Dragon pet at any level or rarity, as a counted drop key. */
    static boolean isDragonPet(String item) {
        return "ender dragon".equals(lootName(item));
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
        String name = lootName(item);
        // A Golden Eye can land during a dragon's payout window, but it comes from
        // zealots or a Slayer roll. This guard is also used when loading old saves,
        // so an eye previously attributed to Strong/Golden Dragon leaves /drops.
        if (isDragon(boss) && "golden eye".equals(name)) return false;
        return belongs(boss, item) && !(LOOT.containsKey(boss) && FODDER.contains(name));
    }

    /** The log audit uses the same exclusions as the live tracker without mutating it. */
    static boolean auditCounted(String boss, String item) {
        String lower = item.toLowerCase(Locale.ROOT);
        return !NEST.contains(lower) && !MINED.contains(lower) && !hidden(item) && counted(boss, item);
    }

    private static void drop(String boss, Drops.Drop d) {
        drop(boss, d, d.quantity());
    }

    private static void drop(String boss, Drops.Drop d, int quantity) {
        if (quantity <= 0) return;
        String item = petKey(d.item(), d.tier());
        if (hidden(item) || !counted(boss, item)) return;
        if (repeatedDrop(boss, item, System.currentTimeMillis())) return;
        recordDrop(boss, item, quantity);
        if (isDragon(boss) && isDragonPet(item)) {
            sincePet = 0;
            petKnown = true;
        }
        seen.merge(item, d.tier(), Math::max);
        show(boss);
        save();
    }

    private static void recordDrop(String boss, String item, int quantity) {
        if (quantity <= 0) return;
        of(session, boss).drops.merge(item, quantity, Integer::sum);
        of(unsaved, boss).drops.merge(item, quantity, Integer::sum);
    }

    private static boolean repeatedDrop(String boss, String item, long now) {
        if (isSlayer(boss)) return false;
        counted.values().removeIf(t -> now - t > SAME_DROP_MS);
        Long last = counted.put(item.toLowerCase(Locale.ROOT), now);
        return last != null && now - last < SAME_DROP_MS;
    }

    /** Keep the last dragon kind for its later reward announcements. */
    private static void show(String boss) {
        displayedBoss = boss;
        if (isDragon(boss)) lastDragon = boss;
    }

    private static void drawHud(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        if (bossHudVisible()) HudLayout.draw("dragon.bossdrops", g, mc.font, false);
        if (profitHudVisible(slayerHud)) HudLayout.draw(slayerHud.id, g, mc.font, false);
    }

    private static boolean bossHudVisible() {
        String boss = hudBoss();
        return enabled && showHud && bossHudMatches(boss) && Area.end();
    }

    private static boolean bossHudMatches(String boss) {
        // The generic card is the dragon/lobby-boss tracker. Excluding dragons
        // removed its original job, while permitting Revenant duplicated Slayer
        // Profit with the same costs and profit rows under a second title.
        return !isSlayer(boss);
    }

    private static boolean profitHudVisible(ProfitHud hud) {
        return hud.enabled && (Area.end() || Area.crypts())
                && profitHudMatches(profitFamily(), slayer, profitQuestActive);
    }

    /**
     * The family the profit card is for: whichever Slayer quest is running.
     *
     * Null when that is not a known family, which keeps the old guarantee - an
     * unidentified or legacy Enderman never borrows another family's history just to
     * fill a card - now that one card serves them all.
     */
    static String profitFamily() {
        return isSlayer(slayer) && !UNKNOWN_ENDERMAN.equals(slayer) && !LEGACY_ENDERMAN.equals(slayer)
                ? slayer : null;
    }

    private static boolean profitHudMatches(String family, String questFamily, boolean questActive) {
        // XP/name identification is shared with the recorder. Unknown and legacy
        // Endermen never borrow another family's history just to fill a HUD.
        return questActive && family != null && family.equals(questFamily);
    }

    /**
     * The running quest's family once it is known, else the last boss with a kill or drop.
     * Going by the last drop alone left a fresh Riftborn quest showing Voidgloom's numbers
     * until its first loot arrived. A quest whose family is not read yet keeps the last boss.
     */
    private static String hudBoss() {
        return profitQuestActive && isSlayer(slayer) && !UNKNOWN_ENDERMAN.equals(slayer) ? slayer : displayedBoss;
    }

    private static String periodText(String period) { return period.equals("All Time") ? "[Total]" : "[Session]"; }

    private static boolean periodHit(int[] bounds, float scale, double mouseX, double mouseY, int textWidth) {
        if (bounds == null) return false;
        // Reserve only the header's small period label, never the rows or inventory slots beneath.
        return mouseX >= bounds[0] + bounds[2] - (textWidth + 4) * scale && mouseX < bounds[0] + bounds[2]
                && mouseY >= bounds[1] && mouseY < bounds[1] + 12 * scale;
    }

    private static String itemColour(String item) {
        int rgb = Rarity.colour(item, Drops.colour(tier(item))) & 0xFFFFFF;
        for (var colour : net.minecraft.ChatFormatting.values())
            if (colour.isColor() && colour.getColor() != null && colour.getColor() == rgb) return "§" + colour.getChar();
        return "§f";
    }

    private static List<Map.Entry<String, Integer>> valueSorted(Map<String, Integer> drops) {
        // Price lookup normalizes names and checks recorded Slayer loot. Resolve it
        // once per item instead of doing that work again for every sort comparison.
        final class PricedDrop {
            final Map.Entry<String, Integer> drop;
            final long value;
            private int rank = Integer.MIN_VALUE;
            private String name;

            PricedDrop(Map.Entry<String, Integer> drop) {
                this.drop = drop;
                value = NpcPrices.value(drop.getKey(), drop.getValue());
            }

            int rank() {
                if (rank == Integer.MIN_VALUE) rank = rankOf(drop.getKey());
                return rank;
            }

            String name() {
                if (name == null) name = plain(drop.getKey());
                return name;
            }
        }
        return drops.entrySet().stream().filter(e -> e.getValue() > 0)
                .map(PricedDrop::new)
                .sorted((a, b) -> {
                    int compared = Long.compare(b.value, a.value);
                    if (compared != 0) return compared;
                    int rank = Integer.compare(b.rank(), a.rank());
                    return rank == 0 ? a.name().compareToIgnoreCase(b.name()) : rank;
                }).map(e -> e.drop).toList();
    }

    private static boolean missingValues(Count c) {
        return c.drops.entrySet().stream().anyMatch(e -> e.getValue() > 0 && NpcPrices.priceInfo(e.getKey()) == null);
    }

    private static long profit(Count c) {
        long loot = 0;
        for (Map.Entry<String, Integer> item : c.drops.entrySet()) {
            long value = NpcPrices.value(item.getKey(), item.getValue());
            loot = value > Long.MAX_VALUE - loot ? Long.MAX_VALUE : loot + value;
        }
        return loot - c.spawnCost - c.historicalCost;
    }

    private static int[] drawHudAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        String boss = sample ? GOLEM : hudBoss();
        return drawCountsHudAt(g, font, x, y, sample, boss, hudPeriod, compactHud, boss + " Drops");
    }

    private static int[] drawProfitHudAt(ProfitHud hud, GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        String family = profitFamily();
        String boss = sample || family == null ? VOIDGLOOM : family;   // preview with no quest running
        return drawCountsHudAt(g, font, x, y, sample, boss, hud.period, hud.compact, boss + " Profit");
    }

    private static Count hudCount(String boss, String period) {
        if (DRAGON.equals(boss)) {
            if (!period.equals("All Time")) return dragons(session);
            Count total = dragons(loaded), fresh = dragons(unsaved);
            total.kills += fresh.kills;
            fresh.drops.forEach((item, n) -> total.drops.merge(item, n, Integer::sum));
            return total;
        }
        String key = bossKey(boss);
        if (!period.equals("All Time")) return of(session, key);
        Count base = loaded.get(key), fresh = unsaved.get(key);
        if (base == null) return fresh == null ? new Count() : fresh;
        if (fresh == null) return base;
        // A Slayer readout only needs its own counts. Copying every other boss's
        // drop history here scaled rendering cost with the entire saved account.
        Count total = new Count();
        for (Count part : List.of(base, fresh)) {
            total.kills += part.kills;
            total.starts += part.starts;
            total.unknownCosts += part.unknownCosts;
            total.spawnCost += part.spawnCost;
            total.historicalCost += part.historicalCost;
            total.activeMs += part.activeMs;
            total.incompleteTime |= part.incompleteTime;
            part.drops.forEach((item, n) -> total.drops.merge(item, n, Integer::sum));
        }
        return total;
    }

    private record CountState(int kills, int starts, int unknownCosts, long spawnCost, long historicalCost,
                              boolean incompleteTime, Map<String, Integer> drops) {
        static CountState of(Count count, boolean snapshot) {
            return count == null ? null : new CountState(count.kills, count.starts, count.unknownCosts,
                    count.spawnCost, count.historicalCost, count.incompleteTime,
                    snapshot ? Map.copyOf(count.drops) : count.drops);
        }
    }

    private record CountsHudState(String boss, String period, boolean compact, boolean sample,
                                  long prices, Object palette, long quarterSecond, CountState base, CountState fresh,
                                  List<CountState> dragons) { }
    private record CountsHudContext(String boss, String period, boolean compact, boolean sample) { }
    private record CountsHudRows(CountsHudState state, List<HudCard.Row> rows) { }
    // Boss Drops and Slayer Profit can both be visible, with separate periods and
    // preview data. A single last-result cache made those two cards evict each other
    // every frame. Bound the few display contexts rather than retaining history.
    private static final Map<CountsHudContext, CountsHudRows> shownHudRows = new LinkedHashMap<>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<CountsHudContext, CountsHudRows> eldest) {
            return size() > 16;
        }
    };

    private static CountsHudState countsHudState(String boss, String period, boolean compact, boolean sample,
                                                 long now, boolean snapshot) {
        boolean allTime = period.equals("All Time");
        List<CountState> dragons = List.of();
        if (DRAGON.equals(boss)) {
            List<CountState> counts = new ArrayList<>();
            for (Map<String, Count> source : allTime ? List.of(loaded, unsaved) : List.of(session))
                for (Map.Entry<String, Count> entry : source.entrySet())
                    if (isDragon(entry.getKey())) counts.add(CountState.of(entry.getValue(), snapshot));
            dragons = List.copyOf(counts);
        }
        return new CountsHudState(boss, period, compact, sample, NpcPrices.revision(), Theme.palette(), now / 250,
                CountState.of((allTime ? loaded : session).get(bossKey(boss)), snapshot),
                allTime ? CountState.of(unsaved.get(bossKey(boss)), snapshot) : null, dragons);
    }

    private static List<HudCard.Row> countsHudRows(String boss, String period, boolean compact, boolean sample, long now) {
        CountsHudContext context = new CountsHudContext(boss, period, compact, sample);
        CountsHudState state = countsHudState(boss, period, compact, sample, now, false);
        CountsHudRows shown = shownHudRows.get(context);
        // The short refresh also catches newly seen item rarity and a reloaded tier
        // list. Counts, period, prices and palette edits invalidate immediately.
        if (shown != null && state.equals(shown.state())) return shown.rows();
        List<HudCard.Row> rows = List.copyOf(buildCountsHudRows(boss, period, compact, sample));
        shownHudRows.put(context, new CountsHudRows(countsHudState(boss, period, compact, sample, now, true), rows));
        return rows;
    }

    private static int[] drawCountsHudAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample,
                                          String boss, String period, boolean compact, String title) {
        return TrackerHud.draw(g, font, x, y, title, periodText(period), Theme.muted(),
                countsHudRows(boss, period, compact, sample, System.currentTimeMillis()), -1);
    }

    private static List<HudCard.Row> buildCountsHudRows(String boss, String period, boolean compact, boolean sample) {
        Count c = hudCount(boss, period);
        if (sample && isSlayer(boss)) {
            c = new Count();
            c.kills = 12;
            c.starts = 12;
            c.spawnCost = 180_000_000L;
            c.activeMs = 1_800_000L;
            c.drops.put("Null Ovoid", 120);
            c.drops.put(RIFTBORN.equals(boss) ? "Rift Pass" : "Judgement Core", 2);
        }
        List<HudCard.Row> rows = new ArrayList<>();
        rows.add(new HudCard.Row("Kills", sample ? "12" : String.format(Locale.ROOT, "%,d", c.kills)));
        // Price order applies to the short HUD; the command retains the full rarity list.
        List<Map.Entry<String, Integer>> list = valueSorted(c.drops);
        int limit = compact ? 5 : 10;
        for (Map.Entry<String, Integer> drop : list.stream().limit(limit).toList()) {
            String value = String.format(Locale.ROOT, "%,d", drop.getValue());
            if (isSlayer(boss)) value += " §8· §7" + (NpcPrices.value(drop.getKey(), drop.getValue()) > 0
                    ? compactCoins(NpcPrices.value(drop.getKey(), drop.getValue())) : "?");
            rows.add(new HudCard.Row(itemColour(drop.getKey()) + plain(drop.getKey()), value));
        }
        if (sample && !isSlayer(boss)) rows.add(new HudCard.Row("§6Giant's Core", "2"));
        else if (list.isEmpty()) rows.add(new HudCard.Row("Drops", "None yet"));
        if (list.size() > limit) rows.add(new HudCard.Row("More items", "+" + (list.size() - limit) + " §8(/drops)"));
        if (isSlayer(boss)) {
            rows.add(new HudCard.Row("Spawn costs", costText(c), Theme.neg()));
            long profit = profit(c);
            String unknown = missingValues(c) || c.unknownCosts > 0 ? " + ?" : "";
            rows.add(new HudCard.Row("Profit", compactCoins(profit) + unknown, profit < 0 ? Theme.neg() : Theme.pos()));
            rows.add(new HudCard.Row("Profit/h", c.activeMs == 0 || c.incompleteTime ? "—"
                    : compactCoins(Math.round(profit * (3_600_000.0 / c.activeMs))) + unknown,
                    profit < 0 ? Theme.neg() : Theme.pos()));
        }
        return rows;
    }

    /** A name to sort by: a pet's "[Lvl 1] " is not part of what it is, and "[" sorts before every letter. */
    private static String plain(String item) {
        return petBase(item).replaceFirst("^\\[[^\\]]*\\]\\s*", "");
    }

    public static List<String> knownSlayerDropNames() {
        Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(SLAYER_ITEMS);
        for (Map<String, Count> counts : List.of(session, loaded, unsaved))
            for (String boss : List.of(REVENANT, VOIDGLOOM, RIFTBORN, LEGACY_ENDERMAN, UNKNOWN_ENDERMAN))
                names.addAll(of(counts, boss).drops.keySet());
        NpcPrices.knownNames().stream().filter(BossDrops::isSlayerDropName).forEach(names::add);
        return List.copyOf(names);
    }

    public static boolean isSlayerDropName(String item) {
        if (item == null) return false;
        String name = plain(Zealots.strip(item)).trim().replace('\u2019', '\'');
        if (SLAYER_ITEMS.stream().anyMatch(n -> n.equalsIgnoreCase(name))) return true;
        for (Map<String, Count> counts : List.of(session, loaded, unsaved))
            for (String boss : List.of(REVENANT, VOIDGLOOM, RIFTBORN, LEGACY_ENDERMAN, UNKNOWN_ENDERMAN))
                if (of(counts, boss).drops.keySet().stream().anyMatch(n -> plain(n).replace('\u2019', '\'').equalsIgnoreCase(name))) return true;
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

    /** /drops: every boss with something to show, all time or this session. */
    private static int sayAll(boolean thisSession) {
        Map<String, Count> m = thisSession ? session : totals();
        for (String boss : BOSSES) {
            Count counts = DRAGON.equals(boss) ? dragons(m) : of(m, boss);
            if (counts.kills > 0 || counts.starts > 0) say(boss, thisSession);
        }
        return 1;
    }

    /**
     * One boss's numbers into chat, the same order and colours as the readout: all time
     * by default, or this session's with "session" on the end.
     */
    private static int say(String boss, boolean thisSession) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        Map<String, Count> m = thisSession ? session : totals();
        Count c = DRAGON.equals(boss) ? dragons(m) : of(m, boss);
        mc.player.sendSystemMessage(Component.literal("§d" + boss + " §7- §f" + c.kills + (c.kills == 1 ? " kill" : " kills")
                + (thisSession ? " §8(session)" : " §8(all time)")));
        if (boss.equals(LEGACY_ENDERMAN)) mc.player.sendSystemMessage(Component.literal(
                "§7Saved before family separation; these kills and drops may include Voidgloom and Riftborn."));
        if (isSlayer(boss)) mc.player.sendSystemMessage(Component.literal("§7Spawn costs: §c" + costText(c)));
        if (DRAGON.equals(boss)) {
            StringBuilder kinds = new StringBuilder("§8  ");
            for (String k : KINDS) if (of(m, k + " Dragon").kills > 0) kinds.append(k).append(' ').append(of(m, k + " Dragon").kills).append("  ");
            if (kinds.length() > 4) mc.player.sendSystemMessage(Component.literal(kinds.toString().trim()));
        }
        List<Map.Entry<String, Integer>> list = sorted(boss, c.drops);
        if (list.isEmpty()) {
            mc.player.sendSystemMessage(Component.literal("§8  nothing worth a row yet"));
            return 1;
        }
        for (Map.Entry<String, Integer> e : list) {
            int colour = Rarity.colour(e.getKey(), Drops.colour(tier(e.getKey())));
            mc.player.sendSystemMessage(Component.literal("  ")
                    .append(Component.literal(e.getKey()).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(colour & 0xFFFFFF))))
                    .append(Component.literal("  §f" + e.getValue())));
        }
        return 1;
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
        // Someone else wrote the file since we read it: take theirs as the base. Counts add
        // up across the two; a streak is a position, and this client's is the newest.
        try {
            if (Files.exists(file()) && Files.getLastModifiedTime(file()).toMillis() != fileStamp) {
                int superior = sinceSuperior, pet = sincePet;
                boolean superiorPinned = superiorKnown, petPinned = petKnown;
                load();
                sinceSuperior = superior;
                sincePet = pet;
                superiorKnown = superiorPinned;
                petKnown = petPinned;
            }
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
        lines.add(FAMILY_SCHEMA);
        lines.add("# all-time: kill, drop, tier; cost<TAB>boss<TAB>starts<TAB>coins<TAB>unknown<TAB>historical estimate; dragons by kind");
        totals().forEach((boss, c) -> {
            lines.add("kill\t" + boss + "\t" + c.kills);
            c.drops.forEach((item, n) -> lines.add("drop\t" + boss + "\t" + item + "\t" + n));
            if (isSlayer(boss)) lines.add("cost\t" + boss + "\t" + c.starts + "\t" + c.spawnCost
                    + "\t" + c.unknownCosts + "\t" + c.historicalCost);
            if (isSlayer(boss)) lines.add("time\t" + boss + "\t" + c.activeMs + "\t" + (c.incompleteTime ? "0" : "1"));
        });
        seen.forEach((item, t) -> lines.add("tier\t" + item + "\t" + t));
        lines.add("streak\tsuperior\t" + sinceSuperior + "\t" + (superiorKnown ? 1 : 0));
        lines.add("streak\tpet\t" + sincePet + "\t" + (petKnown ? 1 : 0));
        lobbyTaken.forEach((boss, n) -> lines.add("lobby\t" + boss + "\t" + n));
        return lines;
    }

    /**
     * "streak<TAB>pet<TAB>37<TAB>1": the count, then 1 once a real pet or Superior has pinned
     * it. A count of -1 is how an unreleased build wrote "unknown"; it reads as unknown from 0.
     */
    private static void readStreak(String[] p) {
        int n = Integer.parseInt(p[2]);
        boolean known = n >= 0 && p.length >= 4 && p[3].equals("1");
        if (p[1].equals("superior")) {
            sinceSuperior = Math.max(0, n);
            superiorKnown = known;
        } else if (p[1].equals("pet")) {
            sincePet = Math.max(0, n);
            petKnown = known;
        }
    }

    private static void readCounts(List<String> lines) {
        Set<String> costRecords = new java.util.HashSet<>();
        Set<String> timeRecords = new java.util.HashSet<>();
        boolean separated = lines.contains(FAMILY_SCHEMA);
        for (String line : lines) {
            String[] p = line.split("\t");
            String boss = p.length > 1 ? storedBoss(p[1], separated) : "";
            if (p[0].equals("kill") && p.length == 3) of(loaded, boss).kills = Integer.parseInt(p[2]);
            // Retain the existing loot rules; this migration never changes kill/drop counts.
            else if (p[0].equals("drop") && p.length == 4 && counted(boss, p[2])) of(loaded, boss).drops.put(p[2], Integer.parseInt(p[3]));
            else if (p[0].equals("tier") && p.length == 3) seen.put(p[1], Integer.parseInt(p[2]));
            else if (p[0].equals("streak") && p.length >= 3) readStreak(p);
            else if (p[0].equals("lobby") && p.length == 3) lobbyTaken.put(p[1], Integer.parseInt(p[2]));
            else if (p[0].equals("cost") && p.length == 6) {
                Count c = of(loaded, boss);
                c.starts = Integer.parseInt(p[2]);
                c.spawnCost = Long.parseLong(p[3]);
                c.unknownCosts = Integer.parseInt(p[4]);
                c.historicalCost = Long.parseLong(p[5]);
                costRecords.add(boss);
            }
            else if (p[0].equals("time") && p.length >= 3) {
                Count c = of(loaded, boss);
                c.activeMs = Math.max(0, Long.parseLong(p[2]));
                c.incompleteTime = p.length >= 4 && !p[3].equals("1");
                timeRecords.add(boss);
            }
        }
        for (String boss : List.of(REVENANT, LEGACY_ENDERMAN)) {
            // Only legacy files lack a cost record. Persisting even a zero prevents
            // this requested T5 assumption from being applied again on a later load.
            if (!costRecords.contains(boss)) {
                Count c = of(loaded, boss);
                c.historicalCost = SlayerCosts.historicalCost(boss.equals(LEGACY_ENDERMAN) ? VOIDGLOOM : boss, c.kills);
            }
        }
        loaded.forEach((boss, c) -> {
            // Saved kills without their tracked time cannot produce a meaningful
            // all-time hourly rate, even once a later session has a running clock.
            if (isSlayer(boss) && !timeRecords.contains(boss) && (c.kills > 0 || c.starts > 0)) c.incompleteTime = true;
        });
    }

    private static boolean isSlayer(String boss) {
        return REVENANT.equals(boss) || endermanSlayer(boss);
    }

    private static boolean endermanSlayer(String boss) {
        return boss != null && List.of(VOIDGLOOM, RIFTBORN, LEGACY_ENDERMAN, UNKNOWN_ENDERMAN).contains(boss);
    }

    private static String storedBoss(String boss, boolean separated) {
        String key = bossKey(boss);
        // The old file never distinguished quest families. A drop's name cannot
        // recover which boss paid it, so preserve that history under its own key.
        return !separated && key.equals(VOIDGLOOM) ? LEGACY_ENDERMAN : key;
    }

    private static String costText(Count c) {
        long coins = c.spawnCost + c.historicalCost;
        String value = (c.historicalCost > 0 ? "~" : "") + "-" + compactCoins(coins);
        return c.unknownCosts > 0 ? (coins == 0 ? "Unknown" : value + " + ?") : value;
    }

    private static String compactCoins(long coins) {
        if (coins < 0) return "-" + compactCoins(coins == Long.MIN_VALUE ? Long.MAX_VALUE : -coins);
        if (coins < 1_000) return String.valueOf(coins);
        long divisor = coins >= 1_000_000_000 ? 1_000_000_000 : coins >= 1_000_000 ? 1_000_000 : 1_000;
        String suffix = divisor == 1_000_000_000 ? "b" : divisor == 1_000_000 ? "m" : "k";
        return String.format(Locale.ROOT, "%.1f", coins / (double) divisor).replaceFirst("\\.0$", "") + suffix;
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

}
