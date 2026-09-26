package com.endsight.storage;

import com.endsight.hud.HudLayout;
import com.endsight.hud.Readout;
import com.endsight.hud.Toast;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Powder is never shown when it is gained; the only readout is the Heart of the Dragon
 * menu. So the total is estimated between visits from the blocks broken, and corrected
 * to the exact figure at every visit.
 *
 * The rates came from a 25-minute calibration on 2026-09-20: eight visits bracketing
 * End Stone gave 16.1-16.9 Ender per block broken, two on Obsidian 41.1-41.2, four on
 * Crying Obsidian 138-140 Void - all with Powder Buff at +24%. Linear in blocks,
 * spread blocks included, so blocks are counted by watching the neighbourhood rather
 * than the crosshair. Those rates seed a fresh install, rescaled to that player's
 * buff, and each visit that brackets one kind of block re-learns that kind's rate.
 */
public final class PowderTracker {

    private PowderTracker() {
    }

    private static final String ID = "mining.powder";
    private static final String HOTD = "Heart of the Dragon";
    private static final Pattern FUEL = Pattern.compile("^Fuel: ([0-9,]+)/([0-9,]+)");
    private static final String FUEL_ID = "mining.fuel";
    private static final Pattern MINING_XP = Pattern.compile("\\+([\\d,.]+)\\s+Mining\\s*\\(([\\d.]+)%\\)");
    private static final Pattern POWDER = Pattern.compile("(?i)^(ender|void)\\s+powder\\D{0,4}([\\d,]{2,})");
    private static final Pattern MORE = Pattern.compile("(?i)\\+(\\d+)%\\s+more\\s+powder");
    private static final Pattern LEVEL = Pattern.compile("(?i)^Level\\s+(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern COST = Pattern.compile("(?i)([\\d,]+)\\s+(Ender|Void)\\s+Powder");
    /** How far around the player disappearing blocks are counted. Spread reaches a few blocks. */
    private static final int SCAN_R = 5;
    /** More than this vanishing in one tick is a chunk reload or a warp, not mining. */
    private static final int SCAN_MAX = 40;
    /** How far from the aimed block a spread-broken block can be and still be yours. */
    private static final int SPREAD_R = 7;
    private static long lastSwing;
    private static final int SCREEN_EVERY = 5;
    /** Hourly figures are the pace over this long, so a sell trip shows as a dip, not a lie. */
    private static final long PACE_MS = 600_000;
    /** No block for this long and the session pauses: the clock stops, the header says so. */
    private static final long PAUSE_MS = 15_000;
    /** A visit learns a kind's rate only when that kind was nearly all of what broke. */
    private static final double PURE = 0.9;
    /** Blocks behind a learned rate, and the most it counts for - enough to steady it, not enough to freeze it. */
    private static final double[] rateWeight = new double[4];
    private static final double RATE_WEIGHT_CAP = 5_000;

    private static final String[] KIND = {"End Stone", "Obsidian", "Crying Obsidian", "Amethyst"};
    private static final int[] POWDER_OF = {0, 0, 1, 1};
    private static final String[] TYPE = {"Ender", "Void"};
    /** Per block broken, and the Powder Buff percentage they were measured at. */
    // Amethyst is unmeasured: it counts nothing until two HOTD opens bracket an amethyst run.
    private static final double[] rate = {16.4, 41.2, 139.0, 0};
    private static final int[] rateBuff = {24, 24, 24, 24};

    /** An upgrade the menu offered: what it is, which powder, and the price. */
    private record Upgrade(String perk, int type, long cost) {
    }

    private static boolean enabled = true;
    private static boolean showPowder = true;
    private static boolean remind = true;
    private static final long[] total = {-1, -1};
    private static final long[] sessionBase = {-1, -1};
    /**
     * Whether the session's starting point is known, kept apart from its value.
     *
     * It used to be "sessionBase < 0 means unknown", and spending powder subtracts from
     * the base - so an evening of HOTD purchases drove it past zero and the readout read
     * its own baseline as unknown: Session +0 and Powder/h 0 for the rest of the session,
     * while the blocks kept counting. A base below zero is perfectly ordinary once you
     * have spent more than you had when the session began.
     */
    private static final boolean[] sessionSet = new boolean[2];
    private static final int[] buff = {24, 24};
    // Blocks broken, weighted: a Void block mined under Void Infusion counts as two, since
    // the drill's ability doubles Void Powder for its 30s. So the learned rate is the base
    // rate whether or not the ability ran, and the estimate follows the ability live.
    private static final double[] blocks = new double[KIND.length];
    private static final double[] blocksAtVisit = new double[KIND.length];
    private static final double[] blocksAtSession = new double[KIND.length];
    private static long infusedUntil;
    private static final Pattern INFUSION_FOR = Pattern.compile("for (\\d+)s");
    private static List<Upgrade> upgrades = List.of();
    private static final Map<String, Long> reminded = new HashMap<>();
    private static final Map<BlockPos, Integer> nearby = new HashMap<>();
    private static final Deque<long[]> recent = new ArrayDeque<>();   // {ms, kind}
    public static final int AMETHYST = 3;
    /** Blocks broken this session by kind, one each - blocks[] is powder-weighted. */
    private static final long[] rawBlocks = new long[KIND.length];

    public static long rawBlocks(int kind) {
        return rawBlocks[kind];
    }

    /** Blocks by the session's own clock - {activeMs when broken, kind} - so a pause does not thin the window. */
    private static final Deque<long[]> recentActive = new ArrayDeque<>();

    /**
     * Blocks of a kind an hour, over the last ten minutes of MINING - the session clock
     * that stops when you do, the same one the Paused label runs on. On wall time the
     * pace drained away while you stood at the forge and the fragment estimate climbed
     * forever; scaling two minutes up as if they were ten did the opposite at the start.
     */
    /** Minutes of mining behind the pace, so a reader can decide whether it is worth a number yet. */
    public static long activeMs() {
        return activeMs;
    }

    public static double blocksPerHour(int kind) {
        long cut = activeMs - PACE_MS;
        while (!recentActive.isEmpty() && recentActive.peekFirst()[0] < cut) recentActive.pollFirst();
        long span = Math.min(PACE_MS, Math.max(30_000, activeMs));
        int n = 0;
        for (long[] r : recentActive) if (r[1] == kind) n++;
        return activeMs < 30_000 ? 0 : n * 3_600_000.0 / span;
    }
    private static final Deque<long[]> recentXp = new ArrayDeque<>(); // {ms, xp*100}
    private static long lastActive;
    /** Time spent actually mining this session; the hourly rate is gain over this, as Mining Session does it. */
    private static long activeMs, lastTickMs;
    /** The held drill's fuel, read off its lore; -1 when nothing held says Fuel. */
    private static long fuel = -1, fuelMax;
    private static long fuelLogged = -1;
    /** The fuel line's switch lives on the Mining Session page, beside the Drill CD's. */
    public static boolean showFuel = true;
    private static BlockPos lastCentre;
    /** Perks already dumped this session; the Ender and Void trees are separate pages of the same menu. */
    private static final java.util.Set<String> dumped = new java.util.HashSet<>();
    private static int tickN;

    public static Module module() {
        return new Module(ID, "Powder Tracker",
                "Tracks Ender/Void powder, powder rates, and HOTD upgrade reminders.", "Mining",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Section("Readout"),
                        new Setting.Toggle("Powder Rates", "Shows Ender/Void powder gain and powder per hour.",
                                () -> showPowder, v -> showPowder = v),
                        new Setting.Section("Alerts"),
                        new Setting.Toggle("HOTD Reminders", "Alerts when you can afford a Heart of the Dragon upgrade.",
                                () -> remind, v -> remind = v),
                        new Setting.Section("Session"),
                        new Setting.Action("Reset Session", "Clears powder, XP, and rate totals.", "Reset", PowderTracker::reset),
                        new Setting.Note("Status", PowderTracker::status)));
    }

    public static void init() {
        loadRates();
        ClientTickEvents.END_CLIENT_TICK.register(PowderTracker::tick);
        // Whatever is still buffered when the game closes.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(c -> flush());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String line = clean(message.getString());
            if (line.startsWith("You used Void Infusion")) {
                Matcher m = INFUSION_FOR.matcher(line);
                infusedUntil = System.currentTimeMillis() + Math.round(m.find() ? num(m.group(1)) : 30) * 1000L;
                log("A", "used", line);
            } else if (line.startsWith("Your Void Infusion has worn off")) {
                infusedUntil = 0;
                log("A", "worn");
            }
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "powder_tracker"), (g, delta) -> draw(g));
        HudLayout.register(ID, "Powder Tracker", 0.006f, 0.48f, (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
        HudLayout.register(FUEL_ID, "Drill Fuel", 0.006f, 0.64f, (g, font, x, y, sample) -> drawFuel(g, font, x, y, sample));
        log("S", "session start");
    }

    private static void reset() {
        for (int t = 0; t < 2; t++) {
            sessionSet[t] = total[t] >= 0;
            sessionBase[t] = sessionSet[t] ? estimate(t) : -1;
        }
        for (int k = 0; k < KIND.length; k++) blocksAtSession[k] = blocks[k];
        recent.clear();
        activeMs = 0;
        recentXp.clear();
        reminded.clear();
    }

    private static String status() {
        List<String> rows = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            if (total[t] < 0) continue;
            rows.add(TYPE[t] + ": " + compact(estimate(t)) + ", +" + compact(gain(t)) + " session, +" + buff[t] + "% buff");
        }
        rows.add("Per block: End Stone " + oneDecimal(effective(0)) + ", Obsidian " + oneDecimal(effective(1))
                + ", Crying " + oneDecimal(effective(2)) + ", Amethyst " + oneDecimal(effective(3)));
        List<Upgrade> ready = ready();
        if (!ready.isEmpty()) {
            StringBuilder sb = new StringBuilder("HOTD ready:");
            for (Upgrade u : ready) sb.append(' ').append(u.perk()).append(',');
            rows.add(sb.substring(0, sb.length() - 1));
        } else if (!upgrades.isEmpty()) {
            rows.add("HOTD: nothing affordable yet");
        } else {
            rows.add("Open HOTD once for exact totals.");
        }
        return String.join("\n", rows);
    }

    /** The action bar as the server sets it; the XP popup lives there. */
    public static void onActionBar(String text) {
        VoidFragments.onActionBar(text);
        if (!enabled) return;
        Matcher m = MINING_XP.matcher(Zealots.strip(text));
        if (!m.find()) return;
        long hundredths = Math.round(num(m.group(1)) * 100);
        if (hundredths <= 0) return;
        long now = System.currentTimeMillis();
        recentXp.addLast(new long[]{now, hundredths});
        lastActive = now;
        log("X", m.group(1), m.group(2));
    }

    private static void tick(Minecraft mc) {
        // Not gated on the powder module: the fuel and fragment lines are fed from here
        // and they are switched on their own.
        if (mc.player == null || mc.level == null) return;
        tickN++;
        watchBlocks(mc);
        if (tickN % 5 == 0) readFuel(mc);
        long nowMs = System.currentTimeMillis();
        if (lastTickMs != 0 && nowMs - lastActive < PAUSE_MS) activeMs += nowMs - lastTickMs;
        lastTickMs = nowMs;
        long cut = nowMs - PACE_MS;
        while (!recent.isEmpty() && recent.peekFirst()[0] < cut) recent.pollFirst();
        while (!recentXp.isEmpty() && recentXp.peekFirst()[0] < cut) recentXp.pollFirst();
        if (mc.screen instanceof AbstractContainerScreen<?> s) {
            if (tickN % SCREEN_EVERY == 0 && clean(s.getTitle().getString()).equalsIgnoreCase(HOTD)) scanHotd(s);
            if (tickN % SCREEN_EVERY == 0 && VoidFragments.isMeter(clean(s.getTitle().getString()))) VoidFragments.scanMeter(s);
        } else if (mc.screen == null && remind) {
            remindReady();
        }
        VoidFragments.tick(mc);
        VoidFragments.maybeSave();
        if (nowMs - lastFlush > FLUSH_MS) flush();
    }

    /**
     * Powder blocks that were within reach a tick ago and are gone now.
     *
     * Watching the crosshair block misses most of what gets mined: Mining Spread breaks
     * blocks the crosshair never touched, and at speed the crosshair slides off a block
     * before it breaks. So this diffs the whole neighbourhood. Anyone mining beside you
     * counts too; the calibration windows agreed to 2% so in practice that is rare.
     */
    private static boolean miningTool(ItemStack s) {
        if (s.isEmpty()) return false;
        String name = clean(s.getHoverName().getString()).toLowerCase(Locale.ROOT);
        return name.contains("pickaxe") || name.contains("drill");
    }

    private static void watchBlocks(Minecraft mc) {
        // Eleven cubed block lookups a tick is the mod's biggest standing cost, and it
        // only ever tells us anything while a tool is swinging. Idle - in the hub, at the
        // forge, fighting - it is skipped and the map dropped, so the first scan after
        // picking the drill back up simply starts fresh.
        boolean swingingNow = mc.options.keyAttack.isDown() && miningTool(mc.player.getMainHandItem());
        if (swingingNow) lastSwing = System.currentTimeMillis();
        if (System.currentTimeMillis() - lastSwing > 3_000) {
            nearby.clear();
            return;
        }
        BlockPos c = mc.player.blockPosition();
        boolean warped = lastCentre != null && lastCentre.distManhattan(c) > 8;
        lastCentre = c;
        Map<BlockPos, Integer> now = new HashMap<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -SCAN_R; dx <= SCAN_R; dx++) {
            for (int dy = -SCAN_R; dy <= SCAN_R; dy++) {
                for (int dz = -SCAN_R; dz <= SCAN_R; dz++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    int k = kind(mc.level.getBlockState(m));
                    if (k >= 0) now.put(m.immutable(), k);
                }
            }
        }
        List<Map.Entry<BlockPos, Integer>> gone = new ArrayList<>();
        for (Map.Entry<BlockPos, Integer> e : nearby.entrySet()) {
            BlockPos p = e.getKey();
            if (now.containsKey(p)) continue;
            if (Math.abs(p.getX() - c.getX()) > SCAN_R || Math.abs(p.getY() - c.getY()) > SCAN_R
                    || Math.abs(p.getZ() - c.getZ()) > SCAN_R) continue;   // walked out of range
            gone.add(e);
        }
        // Only what you broke. A block that vanishes five blocks away is someone else's
        // when you are not swinging, and this counted a neighbour's whole session as
        // yours. Yours means: the attack key is down with a mining tool out, and the
        // block is within spread of what you were aiming at.
        boolean mine = System.currentTimeMillis() - lastSwing < 600;
        BlockPos aim = mc.hitResult instanceof BlockHitResult hit ? hit.getBlockPos() : c;
        if (!warped && mine && gone.size() <= SCAN_MAX && !gone.isEmpty()) {
            long ms = System.currentTimeMillis();
            lastActive = ms;
            for (Map.Entry<BlockPos, Integer> e : gone) {
                BlockPos p = e.getKey();
                if (p.distManhattan(aim) > SPREAD_R) continue;
                int w = POWDER_OF[e.getValue()] == 1 && ms < infusedUntil ? 2 : 1;
                blocks[e.getValue()] += w;
                rawBlocks[e.getValue()]++;
                recent.addLast(new long[]{ms, e.getValue(), w});
                recentActive.addLast(new long[]{activeMs, e.getValue()});
                log("B", KIND[e.getValue()], p.getX(), p.getY(), p.getZ());
            }
        }
        nearby.clear();
        nearby.putAll(now);
    }

    private static void scanHotd(AbstractContainerScreen<?> screen) {
        long[] seen = {-1, -1};
        int[] seenBuff = {-1, -1};
        List<Upgrade> offered = new ArrayList<>();
        int slotNo = 0;
        for (Slot slot : containerSlots(screen)) {
            slotNo++;
            ItemStack s = slot.getItem();
            if (s.isEmpty()) continue;
            String name = clean(s.getHoverName().getString());
            List<String> tip = tooltip(s);
            boolean fresh = dumped.add(name);
            if (fresh) for (String line : tip) log("R", slotNo, line);
            int level = -1, max = -1, current = -1;
            for (String line : tip) {
                Matcher p = POWDER.matcher(line);
                if (p.find()) {
                    current = type(p.group(1));
                    seen[current] = Math.round(num(p.group(2)));
                    continue;
                }
                Matcher more = MORE.matcher(line);
                if (more.find() && current >= 0) {
                    seenBuff[current] = (int) num(more.group(1));
                    continue;
                }
                Matcher l = LEVEL.matcher(line);
                if (l.find()) {
                    level = (int) num(l.group(1));
                    max = (int) num(l.group(2));
                    VoidFragments.perk(name, level);
                    continue;
                }
                Matcher cost = COST.matcher(line);
                if (cost.find() && level >= 0 && level < max) {
                    offered.add(new Upgrade(name, type(cost.group(2)), Math.round(num(cost.group(1)))));
                }
            }
            if (level >= 0 && fresh) log("P", name, level, max);
        }
        if (!offered.isEmpty()) upgrades = List.copyOf(offered);
        for (int t = 0; t < 2; t++) {
            if (seenBuff[t] >= 0) buff[t] = seenBuff[t];
            if (seen[t] >= 0) visit(t, seen[t]);
        }
    }

    /**
     * An exact total from the menu. If one kind of block was nearly all of what broke
     * since the last visit, its rate is re-learned from the gain, less what the other
     * kinds contributed at their known rates; then the count restarts.
     */
    private static void visit(int t, long value) {
        long old = total[t];
        if (old == value) return;
        long est = estimate(t);                            // before any rate below is re-learned
        double since = 0;
        int major = -1;
        for (int k = 0; k < KIND.length; k++) if (POWDER_OF[k] == t) since += blocksSince(k);
        for (int k = 0; k < KIND.length; k++) if (POWDER_OF[k] == t && since > 0 && blocksSince(k) >= PURE * since) major = k;
        if (old >= 0 && value > old && major >= 0) {
            double others = 0;
            for (int k = 0; k < KIND.length; k++) if (POWDER_OF[k] == t && k != major) others += effective(k) * blocksSince(k);
            double learned = (value - old - others) / blocksSince(major) / (1 + petBuff(major));
            if (learned > 0) {
                // Averaged over visits, weighted by the blocks behind each, not replaced by
                // the latest one. The log shows four amethyst visits at the same buff
                // reading 349, 330, 324 and 322 a block: each on its own is a few hundred
                // blocks of Pristine luck and infusion timing, and the estimate jumped with
                // every one. The old figure is first moved to today's buff so the two are on
                // one scale, and the weight is capped so the rate still follows real change.
                double w = rateWeight[major];
                double oldNow = rate[major] * (100.0 + buff[t]) / (100.0 + rateBuff[major]);
                double n = blocksSince(major);
                rate[major] = w > 0 && rate[major] > 0 ? (oldNow * w + learned * n) / (w + n) : learned;
                rateWeight[major] = Math.min(RATE_WEIGHT_CAP, w + n);
                rateBuff[major] = buff[t];
                saveRates();
                log("RATE", KIND[major], value - old, blocksSince(major), String.format(Locale.ROOT, "%.3f", learned), buff[t]);
            }
        }
        // The session gain is exact at every visit: the menu's total, less where the
        // session started. It used to put the menu's correction into the start instead,
        // so an estimate that ran low stayed low; and the session only began at the first
        // visit while the hourly clock had run since launch - 568k over 58 minutes read
        // 579k/h when the last ten minutes alone were over 3m/h. Now the first visit
        // counts what was mined before it at the learned rates, and only a drop with
        // nothing mined since - a perk bought in the menu - moves the start, so spending
        // is never negative mining.
        if (!sessionSet[t]) {
            double before = 0;
            for (int k = 0; k < KIND.length; k++) if (POWDER_OF[k] == t) before += effective(k) * (blocks[k] - blocksAtSession[k]);
            sessionBase[t] = value - Math.round(before);
            sessionSet[t] = true;
        } else {
            // The menu's correction goes into the session only when it could be mining:
            // the gain since the last visit within half to twice what its blocks were
            // estimated at. Anything else is not mining - a perk bought on a page this does
            // not read, powder from somewhere else - and moves the start instead. Letting
            // every correction through took a session to Void +0 after an unseen purchase,
            // and put 12k Ender on it from two End Stone.
            double estMined = est - old, realMined = value - old;
            boolean mining = since > 0 && old >= 0 && estMined > 0
                    && realMined >= 0.5 * estMined && realMined <= 2 * estMined;
            if (!mining) sessionBase[t] += value - est;
        }
        total[t] = value;
        for (int k = 0; k < KIND.length; k++) if (POWDER_OF[k] == t) blocksAtVisit[k] = blocks[k];
        log("H", TYPE[t], value, since);
        saveRates();                                       // the totals ride in the same file
    }

    /** Upgrades the estimate now covers. */
    private static List<Upgrade> ready() {
        List<Upgrade> out = new ArrayList<>();
        for (Upgrade u : upgrades) if (total[u.type()] >= 0 && estimate(u.type()) >= u.cost()) out.add(u);
        return out;
    }

    /** Once the estimate crosses an upgrade's cost, say so - once per upgrade. */
    private static void remindReady() {
        List<Upgrade> ready = ready();
        int fresh = 0;
        for (Upgrade u : ready) {
            String key = u.perk() + "|" + u.cost();
            if (reminded.containsKey(key)) continue;
            reminded.put(key, System.currentTimeMillis());
            fresh++;
        }
        if (fresh > 0) Toast.changed("HOTD Upgrade", ready.size() + (ready.size() == 1 ? " perk affordable" : " perks affordable"));
    }

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        // The fuel line and the fragment line have their own switches and live in this
        // class only because it is the one reading the drill and the blocks. Turning the
        // powder readout off took both down with it, which is not what the toggle says.
        if (showFuel && fuel >= 0) HudLayout.draw(FUEL_ID, g, mc.font, false);
        boolean show = enabled && (total[0] >= 0 || total[1] >= 0);
        // The readout has gone missing mid-session with nothing in the log to say why.
        // Every flip of the one gate that hides it is written down, with the state behind
        // it, so the next disappearance names its own cause instead of being guessed at.
        if (show != wasShown) {
            wasShown = show;
            log("VIS", show ? "shown" : "hidden", "enabled=" + enabled, "ender=" + total[0], "void=" + total[1]);
        }
        if (!show) return;
        HudLayout.draw(ID, g, mc.font, false);
    }

    /**
     * POWDER, Active or Rates; XP/h; Ender/hr and Void/hr; Ender and Void as session
     * gain then total; HOTD n ready. Rows with nothing known are left out rather than
     * shown as placeholders - the sample for placement shows them all.
     */
    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        long[] hourly = {sample ? 22_000 : Math.round(pace(0)), sample ? 0 : Math.round(pace(1))};
        long[] gained = {sample ? 20_000 : gain(0), sample ? 83 : gain(1)};
        boolean known = sample || total[0] >= 0 || total[1] >= 0;
        int ready = sample ? 1 : ready().size();
        boolean active = sample || System.currentTimeMillis() - lastActive < PAUSE_MS;
        String title = active ? "Active" : "Paused";


        List<String[]> rows = new ArrayList<>();
        if (showPowder && (hourly[0] > 0 || hourly[1] > 0)) {
            rows.add(new String[]{"Powder/h", "End " + (hourly[0] > 0 ? compact(hourly[0]) : "-") + "   Void " + (hourly[1] > 0 ? compact(hourly[1]) : "-")});
        }
        if (showPowder && known) rows.add(new String[]{"Session", "End +" + compact(gained[0]) + "   Void +" + compact(gained[1])});
        if (ready > 0) rows.add(new String[]{"Perks", ready + (ready == 1 ? " Upgrade available" : " Upgrades available")});

        int w = Readout.width(font, "POWDER", title);
        for (String[] row : rows) w = Math.max(w, Readout.width(font, row[0], row[1]));
        int h = Readout.ROW_H + 3 + rows.size() * (Readout.ROW_H + 2);
        if (g == null) return new int[]{w, h};

        // Drawn the way the MINING readout is: one tick at the title, lit while active,
        // and plain rows under it. Each row carried its own accent tick before - a
        // column of bars down the left edge that read as a list of separate things, and
        // was the "lines on the left" that made this one look off beside MINING.
        Readout.tick(g, x, y, w, active ? Theme.accent() : Theme.line());
        Draw.text(g, font, "POWDER", Readout.left(x), y, Theme.muted());
        Draw.textRight(g, font, title, Readout.right(x, w), y, active ? Theme.accent() : Theme.text());
        int ry = y + Readout.ROW_H + 3;
        for (String[] row : rows) {
            Draw.text(g, font, row[0], Readout.left(x), ry, Theme.dim());
            Draw.textRight(g, font, row[1], Readout.right(x, w), ry, Theme.text());
            ry += Readout.ROW_H + 2;
        }
        return new int[]{w, h};
    }

    /**
     * "Fuel: 1,504/10,000" off the held drill. Logged whenever it changes, with the block
     * count, so fuel per block can be read from the file: the omelette's 25% and the
     * Blazing's 4x are on the lore, the base cost per block is not written anywhere.
     */
    private static void readFuel(Minecraft mc) {
        ItemStack held = mc.player.getMainHandItem();
        long f = -1, max = 0;
        if (!held.isEmpty() && clean(held.getHoverName().getString()).toLowerCase(Locale.ROOT).contains("drill")) {
            for (String line : tooltip(held)) {
                Matcher m = FUEL.matcher(line);
                if (m.find()) {
                    f = Math.round(num(m.group(1)));
                    max = Math.round(num(m.group(2)));
                    break;
                }
            }
        }
        fuel = f;
        fuelMax = max;
        if (f >= 0 && f != fuelLogged) {
            fuelLogged = f;
            double blocksAll = 0;
            for (double b : blocks) blocksAll += b;
            log("F", f, max, Math.round(blocksAll));
        }
    }

    /** Skytils' fuel line: "Fuel: 2,776/10K (28%)", green to red as it drains. */
    private static int[] drawFuel(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        long f = sample ? 2_776 : fuel, max = sample ? 3_000 : fuelMax;
        if (f < 0 || max <= 0) return new int[]{0, 0};
        int pct = (int) Math.round(100.0 * f / max);
        String label = "Fuel: ", value = String.format(Locale.ROOT, "%,d/%s (%d%%)", f, max >= 1000 ? (max / 1000) + "K" : String.valueOf(max), pct);
        int w = font.width(label) + font.width(value), h = 10;
        if (g == null) return new int[]{w, h};
        Draw.text(g, font, label, x, y, 0xFF55FFFF);
        Draw.text(g, font, value, x + font.width(label), y, pct >= 50 ? 0xFF55FF55 : pct >= 25 ? 0xFFFFFF55 : 0xFFFF5555);
        return new int[]{w, h};
    }

    /** A kind's rate at today's buff: measured at one percentage, scaled to the current one. */
    private static double effective(int k) {
        return rate[k] * (100.0 + buff[POWDER_OF[k]]) / (100.0 + rateBuff[k]) * (1 + petBuff(k));
    }

    private static long estimate(int t) {
        if (total[t] < 0) return 0;
        double est = total[t];
        for (int k = 0; k < KIND.length; k++) if (POWDER_OF[k] == t) est += effective(k) * blocksSince(k);
        return Math.round(est);
    }

    private static long gain(int t) {
        return sessionSet[t] ? Math.max(0, estimate(t) - sessionBase[t]) : 0;
    }

    /** Powder per hour of mining time: the session gain over the active clock, which pauses when you stop. */
    private static double pace(int t) {
        return activeMs < 30_000 ? 0 : gain(t) * 3_600_000.0 / activeMs;
    }

    private static double xpPace() {
        double sum = 0;
        for (long[] x : recentXp) sum += x[1] / 100.0;
        return sum * 3_600_000.0 / PACE_MS;
    }

    private static double blocksSince(int k) {
        return blocks[k] - blocksAtVisit[k];
    }

    /** 0 End Stone, 1 Obsidian, 2 Crying Obsidian, 3 Amethyst, -1 anything else. */
    private static int kind(BlockState state) {
        if (state.is(Blocks.END_STONE)) return 0;
        if (state.is(Blocks.OBSIDIAN)) return 1;
        if (state.is(Blocks.CRYING_OBSIDIAN)) return 2;
        if (state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST)) return 3;
        return -1;
    }

    private static int type(String s) {
        return s.equalsIgnoreCase("void") ? 1 : 0;
    }

    private static Path file(String name) {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve(name);
    }

    /**
     * The pet out right now, by the sidebar, and what it adds to powder.
     *
     * A learned rate is what a visit measured, and the pet that was out is inside it -
     * a Bal's "25% more Void Powder from Crying Obsidian" included. So the rate is
     * kept as the plain number: the pet's share is divided out when it is learned and
     * multiplied back in while that buff is out. Swap to a pet without it and the
     * plain rate carries on; nothing already counted this session moves. The buffs
     * themselves are read off pet tooltips as they go past, per pet and rarity.
     */
    private static String petKey = "";
    /** Whether the readout was drawn last frame, so only the changes are logged. */
    private static boolean wasShown;

    public static void petChanged(String pet) {
        petKey = pet;
    }

    /** What the current pet adds to this kind's powder, as a fraction: 0.25 for "25% more". */
    private static double petBuff(int kind) {
        return Pets.powderBuff(petKey, KIND[kind], POWDER_OF[kind] == 1 ? "Void" : "Ender");
    }

    /** kind, rate, buff it was learned at - one line each, so a re-learned rate survives a restart. */
    private static void saveRates() {
        StringBuilder sb = new StringBuilder("# kind\tpowder per block (no pet)\tPowder Buff % when measured\tblocks behind it\n");
        for (int k = 0; k < KIND.length; k++) {
            sb.append(KIND[k]).append('\t').append(String.format(Locale.ROOT, "%.3f", rate[k])).append('\t').append(rateBuff[k])
                    .append('\t').append(Math.round(rateWeight[k])).append('\n');
        }
        // Where the totals stood at the last HOTD visit, so a relaunch does not start
        // from "nothing known". The readout hid itself entirely until the menu had been
        // opened once, which read as the whole thing being broken; the estimate carries
        // over instead and the next visit corrects it.
        for (int t = 0; t < 2; t++) {
            sb.append("#total\t").append(TYPE[t]).append('\t').append(estimate(t)).append('\t').append(buff[t]).append('\n');
        }
        try {
            Files.createDirectories(file("").getParent());
            Files.writeString(file("powder-rates.txt"), sb, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static void loadRates() {
        Path p = file("powder-rates.txt");
        if (!Files.exists(p)) return;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                if (f.length >= 4 && f[0].equals("#total")) {
                    for (int t = 0; t < 2; t++) {
                        if (!TYPE[t].equals(f[1])) continue;
                        total[t] = Long.parseLong(f[2]);
                        buff[t] = Integer.parseInt(f[3]);
                        // Carried over, not measured here: the session starts from it, so a
                        // relaunch mid-session does not count the whole total as gained.
                        sessionBase[t] = total[t];
                        sessionSet[t] = true;
                    }
                    continue;
                }
                if (f.length < 3 || line.startsWith("#")) continue;
                for (int k = 0; k < KIND.length; k++) {
                    if (!KIND[k].equals(f[0])) continue;
                    rate[k] = Double.parseDouble(f[1]);
                    rateBuff[k] = Integer.parseInt(f[2]);
                    // A file from before the weights counts as one good visit's worth.
                    rateWeight[k] = f.length > 3 ? Double.parseDouble(f[3]) : 1_000;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
    }

    private static List<Slot> containerSlots(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        Container playerInv = mc.player == null ? null : mc.player.getInventory();
        List<Slot> out = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) if (slot.container != playerInv) out.add(slot);
        return out;
    }

    private static List<String> tooltip(ItemStack s) {
        Minecraft mc = Minecraft.getInstance();
        if (s.isEmpty() || mc.level == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Component c : s.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL)) {
            String line = clean(c.getString());
            if (!line.isBlank()) out.add(line);
        }
        return out;
    }

    /** One TSV line: kind, wall-clock ms, fields. Appended, never rotated; it is the dataset. */
    /**
     * The measurement log, buffered.
     *
     * This used to open, append to and close powder.tsv for every single block broken -
     * three or four file handles a second while mining, on the thread that draws the
     * frame. That is a stutter you can feel. The lines are held and written every few
     * seconds instead; nothing reads the file while the game is running, so the only
     * cost of being late is the last few seconds if the game is killed outright.
     */
    private static final StringBuilder pending = new StringBuilder();
    private static long lastFlush;
    private static final long FLUSH_MS = 5_000;

    private static void log(String kind, Object... fields) {
        pending.append(kind).append('\t').append(System.currentTimeMillis());
        for (Object f : fields) pending.append('\t').append(String.valueOf(f).replace('\t', ' ').replace('\n', ' '));
        pending.append('\n');
        if (pending.length() > 64_000) flush();
    }

    static void flush() {
        if (pending.length() == 0) return;
        String out = pending.toString();
        pending.setLength(0);
        lastFlush = System.currentTimeMillis();
        try {
            Files.createDirectories(file("").getParent());
            Path log = file("powder.tsv");
            // One line per block broken grew it without end - 7.6 MB and 136,000 lines
            // by September. Past 8 MB the file is moved to powder.tsv.1 (replacing the
            // one before) and a fresh one started, so the recent history is always kept.
            if (Files.exists(log) && Files.size(log) > 8L * 1024 * 1024) {
                Files.move(log, file("powder.tsv.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(log, out, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static String clean(String s) {
        return Zealots.strip(s).trim();
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String compact(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fm", n / 1_000_000.0);
        if (n >= 10_000) return Math.round(n / 1_000.0) + "k";
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }

    private static String oneDecimal(double n) {
        return String.format(Locale.ROOT, "%.1f", n);
    }
}
