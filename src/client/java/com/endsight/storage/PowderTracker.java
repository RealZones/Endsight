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
    private static final int SCREEN_EVERY = 5;
    /** Hourly figures are the pace over this long, so a sell trip shows as a dip, not a lie. */
    private static final long PACE_MS = 600_000;
    /** No block for this long and the session pauses: the clock stops, the header says so. */
    private static final long PAUSE_MS = 15_000;
    /** A visit learns a kind's rate only when that kind was nearly all of what broke. */
    private static final double PURE = 0.9;

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
                        new Setting.Toggle("Powder Rates", "Shows Ender/Void powder gain and powder per hour.",
                                () -> showPowder, v -> showPowder = v),
                        new Setting.Toggle("HOTD Reminders", "Alerts when you can afford a Heart of the Dragon upgrade.",
                                () -> remind, v -> remind = v),
                        new Setting.Action("Reset Session", "Clears powder, XP, and rate totals.", "Reset", PowderTracker::reset),
                        new Setting.Note("Status", PowderTracker::status)));
    }

    public static void init() {
        loadRates();
        ClientTickEvents.END_CLIENT_TICK.register(PowderTracker::tick);
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
        HudLayout.register(ID, "Powder Tracker", 0.006f, 0.60f, (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
        HudLayout.register(FUEL_ID, "Drill Fuel", 0.006f, 0.55f, (g, font, x, y, sample) -> drawFuel(g, font, x, y, sample));
        log("S", "session start");
    }

    private static void reset() {
        for (int t = 0; t < 2; t++) sessionBase[t] = total[t] < 0 ? -1 : estimate(t);
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
        if (!enabled || mc.player == null || mc.level == null) return;
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
        } else if (mc.screen == null && remind) {
            remindReady();
        }
    }

    /**
     * Powder blocks that were within reach a tick ago and are gone now.
     *
     * Watching the crosshair block misses most of what gets mined: Mining Spread breaks
     * blocks the crosshair never touched, and at speed the crosshair slides off a block
     * before it breaks. So this diffs the whole neighbourhood. Anyone mining beside you
     * counts too; the calibration windows agreed to 2% so in practice that is rare.
     */
    private static void watchBlocks(Minecraft mc) {
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
        if (!warped && gone.size() <= SCAN_MAX && !gone.isEmpty()) {
            long ms = System.currentTimeMillis();
            lastActive = ms;
            for (Map.Entry<BlockPos, Integer> e : gone) {
                BlockPos p = e.getKey();
                int w = POWDER_OF[e.getValue()] == 1 && ms < infusedUntil ? 2 : 1;
                blocks[e.getValue()] += w;
                recent.addLast(new long[]{ms, e.getValue(), w});
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
        double since = 0;
        int major = -1;
        for (int k = 0; k < KIND.length; k++) if (POWDER_OF[k] == t) since += blocksSince(k);
        for (int k = 0; k < KIND.length; k++) if (POWDER_OF[k] == t && since > 0 && blocksSince(k) >= PURE * since) major = k;
        if (old >= 0 && value > old && major >= 0) {
            double others = 0;
            for (int k = 0; k < KIND.length; k++) if (POWDER_OF[k] == t && k != major) others += effective(k) * blocksSince(k);
            double learned = (value - old - others) / blocksSince(major);
            if (learned > 0) {
                rate[major] = learned;
                rateBuff[major] = buff[t];
                saveRates();
                log("RATE", KIND[major], value - old, blocksSince(major), String.format(Locale.ROOT, "%.3f", learned), buff[t]);
            }
        }
        // The session gain carries across the correction: what was estimated as gained
        // so far becomes exact, rather than snapping the counter to zero.
        if (sessionBase[t] < 0) sessionBase[t] = value;
        else sessionBase[t] += value - estimate(t);
        total[t] = value;
        for (int k = 0; k < KIND.length; k++) if (POWDER_OF[k] == t) blocksAtVisit[k] = blocks[k];
        log("H", TYPE[t], value, since);
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
        if (!enabled || mc.player == null || mc.options.hideGui) return;
        if (showFuel && fuel >= 0) HudLayout.draw(FUEL_ID, g, mc.font, false);   // before the powder gate: fuel needs no HOTD visit
        if (total[0] < 0 && total[1] < 0) return;
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
        int h = Readout.ROW_H + 3 + rows.size() * (Readout.height(false) + 2);
        if (g == null) return new int[]{w, h};

        Readout.tick(g, x, y, w, Theme.accent());
        Draw.text(g, font, "POWDER", Readout.left(x), y, Theme.muted());
        Draw.textRight(g, font, title, Readout.right(x, w), y, Theme.accent());
        int ry = y + Readout.ROW_H + 3;
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            ry += Readout.draw(g, font, x, ry, w, row[0], row[1], i == 0, -1f) + 2;
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
        return rate[k] * (100.0 + buff[POWDER_OF[k]]) / (100.0 + rateBuff[k]);
    }

    private static long estimate(int t) {
        if (total[t] < 0) return 0;
        double est = total[t];
        for (int k = 0; k < KIND.length; k++) if (POWDER_OF[k] == t) est += effective(k) * blocksSince(k);
        return Math.round(est);
    }

    private static long gain(int t) {
        return sessionBase[t] < 0 ? 0 : Math.max(0, estimate(t) - sessionBase[t]);
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

    /** kind, rate, buff it was learned at - one line each, so a re-learned rate survives a restart. */
    private static void saveRates() {
        StringBuilder sb = new StringBuilder("# kind\tpowder per block\tPowder Buff % when measured\n");
        for (int k = 0; k < KIND.length; k++) {
            sb.append(KIND[k]).append('\t').append(String.format(Locale.ROOT, "%.3f", rate[k])).append('\t').append(rateBuff[k]).append('\n');
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
                if (f.length < 3 || line.startsWith("#")) continue;
                for (int k = 0; k < KIND.length; k++) {
                    if (!KIND[k].equals(f[0])) continue;
                    rate[k] = Double.parseDouble(f[1]);
                    rateBuff[k] = Integer.parseInt(f[2]);
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
    private static void log(String kind, Object... fields) {
        StringBuilder sb = new StringBuilder(kind).append('\t').append(System.currentTimeMillis());
        for (Object f : fields) sb.append('\t').append(String.valueOf(f).replace('\t', ' ').replace('\n', ' '));
        sb.append('\n');
        try {
            Files.createDirectories(file("").getParent());
            Files.writeString(file("powder.tsv"), sb, StandardCharsets.UTF_8,
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
