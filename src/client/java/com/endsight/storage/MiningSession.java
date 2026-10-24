package com.endsight.storage;

import com.endsight.dragons.DragonTimer;
import com.endsight.hud.Area;
import com.endsight.hud.HudLayout;
import com.endsight.hud.HudCard;
import com.endsight.hud.TrackerHud;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A mining session clock that stops when the player is not actually mining.
 *
 * The important number is active time, not wall time: opening a menu, walking back
 * to the Forge, or standing still should not dilute a rate. Material gain is counted
 * from inventory changes while mining, so Auto Compactor does not erase progress:
 * one Enchanted Obsidian counts as 160 raw Obsidian.
 */
public final class MiningSession {

    private MiningSession() {
    }

    private static final String ID = "mining.session";
    private static final long GRACE_MS = 15_000;
    private static final Pattern SOLD = Pattern.compile("^You sold ([\\d,]+)x (.+?) for ([\\d,]+(?:\\.\\d{1,2})?) coins!?$");
    private static final String RAW = "Raw";
    private static final String ENCH = "Ench";
    private static final String REF = "Ref";
    private static final long AMETHYST_FLAWED = 80L;
    private static final long AMETHYST_FINE = AMETHYST_FLAWED * 80L;
    private static final long AMETHYST_FLAWLESS = AMETHYST_FINE * 80L;
    private static final long AMETHYST_PERFECT = AMETHYST_FLAWLESS * 5L;

    private static boolean enabled = true;
    private static boolean showWhenPaused = true;
    private static boolean profitEstimate = true;
    private static boolean breakdown = true;
    private static boolean compactList = true;
    private static String hudPeriod = "Session";
    private static final MiningTotals lifetime = new MiningTotals();
    private static boolean lifetimeDirty;
    private static long lifetimeSaved;
    private static String materialUnits = ENCH;
    private static long activeMs;
    private static long lastTick;
    private static long lastActive;
    private static long lastToolHeld;
    private static ClientLevel hudLevel;
    private static Area.Where hudArea = Area.Where.UNKNOWN;
    private static boolean petAlert = true;
    private static final java.util.regex.Pattern PET_DROP = java.util.regex.Pattern.compile("DROP!.*\\[Lvl \\d+\\]\\s*(.+)$");
    private static final java.util.regex.Pattern VOID_DROP = java.util.regex.Pattern.compile("DROP!\\s*(Void Fragment|Void Core)\\b");

    /**
     * Whether the tracker is up and on amethyst. The Void Fragment line only means
     * something while you mine amethyst, and "mine some amethyst" sat on screen through
     * obsidian sessions and slayer both.
     */
    public static boolean amethystActive() {
        boolean up = mining || (showWhenPaused && activeMs > 0);
        return hudVisible() && up && "Amethyst".equals(currentMaterial)
                && com.endsight.zealots.ZealotTracker.lastActivity() <= lastActive;
    }

    /** Shared by the mining and powder readouts; visibility never resets their totals. */
    public static boolean hudVisible() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null && mc.level == hudLevel
                && hudVisibleAt(System.currentTimeMillis(), lastToolHeld, Area.end());
    }

    private static boolean hudVisibleAt(long now, long heldAt, boolean miningArea) {
        return miningArea && heldAt > 0 && now >= heldAt && now - heldAt < GRACE_MS;
    }

    private static void watchHud(Minecraft mc) {
        Area.Where area = Area.where();
        // A warp must not carry the old pocket's grace window into another activity.
        if (mc.level != hudLevel || area != hudArea) {
            lastToolHeld = 0;
            lastValues.clear();
            lastCraftedValues.clear();
            gainLedger.clear();
            inventoryBaseline = false;
            lastTick = 0;
            lastActive = 0;
            mining = false;
            pendingMaterial = "";
            pendingSince = 0;
        }
        hudLevel = mc.level;
        hudArea = area;
        if (mc.player == null || mc.level == null || !Area.end()) {
            lastToolHeld = 0;
        } else if (miningTool(mc.player.getMainHandItem())) {
            lastToolHeld = System.currentTimeMillis();
        }
    }

    /** When mining last happened, for anything that should stand down while it is going on. */
    public static long lastActive() {
        return lastActive;
    }
    private static boolean mining;
    private static String currentMaterial = "Material";
    /** The ore trying to take the display, and since when. */
    private static String pendingMaterial = "";
    private static long pendingSince;
    /** How long a new ore must hold the cursor before the readout follows it. */
    private static final long FOCUS_MS = 3_000;

    /**
     * Which ore the readout is about. It used to be assigned outright from whatever the
     * cursor touched, so mining crying obsidian and plain obsidian along one path flipped
     * it several times a second and every row flipped with it. A new ore now has to hold
     * the cursor for three unbroken seconds before the display moves, which is longer than
     * any stray block on the way past and far shorter than a real switch. The first ore of
     * a session is taken at once - there is nothing to flicker against yet.
     */
    private static void focus(String m, long now) {
        if (m == null || m.equals(currentMaterial)) {
            pendingMaterial = "";
            return;
        }
        if ("Material".equals(currentMaterial)) {
            currentMaterial = m;
            pendingMaterial = "";
            return;
        }
        if (!m.equals(pendingMaterial)) {
            pendingMaterial = m;
            pendingSince = now;
        } else if (now - pendingSince >= FOCUS_MS) {
            currentMaterial = m;
            pendingMaterial = "";
        }
    }
    private static final Map<String, Long> lastValues = new LinkedHashMap<>();
    private static final Map<String, Long> lastCraftedValues = new LinkedHashMap<>();
    private static final MiningGainLedger gainLedger = new MiningGainLedger();
    private static boolean inventoryBaseline;
    private static final Map<String, Long> totals = new LinkedHashMap<>();
    private static final Map<String, Long> activeByMaterial = new LinkedHashMap<>();
    private static long soldCoins;

    public static Module module() {
        return new Module("mining.session", "Mining Session",
                "Active mining time, material gain and rate; pauses when you stop mining.", "Mining",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Section("Readout"),
                        new Setting.Choice("Period", "Session is resettable; Total is saved across restarts.",
                                List.of("Session", "Total"), () -> hudPeriod, v -> hudPeriod = v),
                        new Setting.Toggle("Show while paused", "Keep paused totals visible while a mining tool is held or was just put away.",
                                () -> showWhenPaused, v -> showWhenPaused = v),
                        new Setting.Choice("Material units",
                                "Show mined material counts as raw blocks, enchanted items or refined crafts.",
                                List.of(RAW, ENCH, REF), () -> materialUnits, v -> materialUnits = v),
                        new Setting.Toggle("Profit", "Estimated NPC value after compaction, plus collected Void Fragments.",
                                () -> profitEstimate, v -> profitEstimate = v),
                        new Setting.Toggle("Breakdown", "Materials and mining drops, highest coin value first.",
                                () -> breakdown, v -> breakdown = v),
                        new Setting.Toggle("Top five items", "Keep the five most valuable rows; profit still includes every item.",
                                () -> compactList, v -> compactList = v),

                        new Setting.Section("Extra lines"),
                        new Setting.Toggle("Drill CD", "Drill CD line: Ready, or the countdown.",
                                com.endsight.visual.Cooldowns::drillOn, com.endsight.visual.Cooldowns::setDrillOn),
                        new Setting.Toggle("Fuel", "Fuel line read off the drill in your hand.",
                                () -> PowderTracker.showFuel, v -> PowderTracker.showFuel = v),
                        new Setting.Toggle("Void Fragments", "Fragments found this session and the observed rate while mining amethyst.",
                                () -> VoidFragments.show, v -> VoidFragments.show = v),

                        new Setting.Section("Alerts"),
                        new Setting.Toggle("Drop alert", "Alert for a Void Fragment, a Void Core or an epic or better pet while you mine.",
                                () -> petAlert, v -> petAlert = v),
                        new Setting.Toggle("Drill ready", "Big alert and a bell when the drill's ability is back.",
                                com.endsight.visual.Cooldowns::readyAlertOn, com.endsight.visual.Cooldowns::setReadyAlert),

                        new Setting.Section("Session"),
                        new Setting.Action("Session", "Start the mining readout over.", "Reset", MiningSession::reset),
                        new Setting.Note("Now", () -> currentMaterial + ": "
                                + formatMaterialCount(currentMaterial, totals.getOrDefault(currentMaterial, 0L))
                                + ", " + time(activeMs) + " active")));
    }

    public static void init() {
        loadLifetime();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> saveLifetime());
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!HudLayout.interactiveScreen(screen)) return;
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                if (click.button() != 0 || !readoutVisible()) return true;
                int[] bounds = HudLayout.bounds(ID);
                if (bounds == null) return true;
                float scale = HudLayout.scale(ID);
                int labelW = mc.font.width("[" + hudPeriod + "]");
                double right = bounds[0] + bounds[2] - TrackerHud.PAD * scale;
                if (click.x() < right - (labelW + 2) * scale || click.x() >= right + 2 * scale
                        || click.y() < bounds[1] || click.y() >= bounds[1] + TrackerHud.HEADER_H * scale) return true;
                hudPeriod = hudPeriod.equals("Session") ? "Total" : "Session";
                com.endsight.Config.save(com.endsight.EndsightClient.registry());
                return false;
            });
        });
        // Stashed drops ("You have N materials stashed away") are not counted. The stash
        // does not say which tier went in, so any count of it is a guess, and with a
        // Personal Compactor - which everyone mining has - the bag does not fill.
        // A pet worth stopping for, dropped while you mine - a Scatha off the amethyst.
        // Kept here and not in Loot Alerts: that list is crowded, and a pet from a
        // slayer or a dragon is not what anyone at the mine is waiting on. Epic or
        // better by the pet's own colour, while the session has been active this minute.
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || !petAlert || overlay) return;
            String raw = message.getString();
            String line = Zealots.strip(raw).trim();
            if (com.endsight.dragons.DragonTimer.isPlayerChat(line)) return;
            if (System.currentTimeMillis() - lastActive > 60_000) return;
            String what = null, kind = null;
            int tier = 4;
            // The two the mine is actually waiting on. Named outright rather than by the
            // drop's tier word: the Fragment comes as a RARE DROP and is rarer than most
            // of what that word covers.
            java.util.regex.Matcher v = VOID_DROP.matcher(line);
            java.util.regex.Matcher m = PET_DROP.matcher(line);
            if (v.find()) {
                what = v.group(1);
                kind = "";              // the name says it; a subtitle under it is noise
            } else if (m.find() && (tier = com.endsight.qol.Drops.petTier(raw)) >= 2) {
                what = m.group(1).trim();
                kind = "pet drop";
            }
            if (what == null) return;
            com.endsight.hud.Alert.show(what, kind, com.endsight.qol.Drops.colour(tier), 1.4f, 5);
            Minecraft mc = Minecraft.getInstance();
            if (com.endsight.hud.Alerts.sound() && mc.player != null) {
                mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 2f);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(MiningSession::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && enabled) onLine(message);
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "mining_session"), (g, delta) -> draw(g));
        HudLayout.register(ID, "Mining Session", 0.006f, 0.26f,
                (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    private static void reset() {
        activeMs = 0;
        lastTick = 0;
        lastActive = 0;
        mining = false;
        currentMaterial = "Material";
        lastValues.clear();
        lastCraftedValues.clear();
        gainLedger.clear();
        inventoryBaseline = false;
        totals.clear();
        activeByMaterial.clear();
        soldCoins = 0;
        VoidFragments.reset();
    }

    private static void tick(Minecraft mc) {
        // Powder has its own toggle, so its visibility must still update with this off.
        watchHud(mc);
        if (!enabled || mc.player == null || mc.level == null) {
            lastTick = 0;
            mining = false;
            return;
        }
        long now = System.currentTimeMillis();
        boolean active = miningNow(mc);
        String target = active ? targetMaterial(mc) : null;
        if (active) {
            if (target != null) focus(target, now);
            lastActive = now;
        }
        boolean counting = active || (lastActive != 0 && now - lastActive <= GRACE_MS);
        if (counting && lastTick != 0) {
            long dt = Math.min(now - lastTick, 1_000);
            activeMs += dt;
            lifetime.activeMs += dt;
            lifetimeDirty = true;
            // Credited to the ore under the cursor, not the one on screen. The display
            // lags on purpose (see focus); the per-ore clocks must not, or a rate would
            // be divided by someone else's seconds.
            String credit = target != null ? target : currentMaterial;
            if (!"Material".equals(credit)) activeByMaterial.merge(credit, dt, Long::sum);
        }
        // Not with a window open: what comes out of the PV or a chest in the grace window
        // after a swing is not mined. 21 Fine "mined" in four minutes were 21 Fine pulled.
        countInventoryGain(mc, counting && mc.screen == null);
        if (System.currentTimeMillis() - auditFlushed > 5_000) flushAudit();
        if (lifetimeDirty && now - lifetimeSaved > 5_000) saveLifetime();
        mining = counting;
        lastTick = now;
    }

    private static void onLine(Component message) {
        String line = Zealots.strip(message.getString()).trim();
        if (DragonTimer.isPlayerChat(line)) return;
        Matcher m = SOLD.matcher(line);
        if (!m.matches()) return;
        String material = materialName(m.group(2));
        if (material == null && !m.group(2).equalsIgnoreCase("Void Fragment")
                && !m.group(2).equalsIgnoreCase("Void Core")) return;
        long coins = parseCoins(m.group(3));
        soldCoins += coins;
        lifetime.sold += coins;
        lifetimeDirty = true;
        if (material != null) currentMaterial = material;
    }

    private static long parseCoins(String s) {
        try {
            // Rough gems can sell for fractional coins; the HUD stores whole coins.
            return new java.math.BigDecimal(s.replace(",", ""))
                    .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return 0;
        }
    }

    private static boolean miningNow(Minecraft mc) {
        if (mc.screen != null || mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return false;
        if (!mc.options.keyAttack.isDown()) return false;
        if (!miningTool(mc.player.getMainHandItem())) return false;
        BlockPos pos = ((BlockHitResult) mc.hitResult).getBlockPos();
        return !mc.level.getBlockState(pos).isAir();
    }

    private static boolean miningTool(ItemStack s) {
        if (s.isEmpty()) return false;
        if (s.is(ItemTags.PICKAXES)) return true;
        String name = Zealots.strip(s.getHoverName().getString()).toLowerCase(Locale.ROOT);
        return miningToolName(name);
    }

    private static boolean miningToolName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("pickaxe") || (lower.contains("drill") && !lower.contains("drill motor"));
    }

    private static String targetMaterial(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || mc.level == null) return null;
        String name = Zealots.strip(mc.level.getBlockState(hit.getBlockPos()).getBlock().getName().getString()).trim();
        return materialName(name);
    }

    /**
     * What came into the bag this tick, per material, counted net - not gains only.
     *
     * It used to count rises and ignore falls. The Personal Compactor makes that wrong:
     * it takes 80 Rough out and puts 1 Flawed in, the same 80 in raw units, and when
     * the two land in different ticks the fall was dropped and the rise kept - 80 Rough
     * of amethyst that was never mined. Net counting handles tracked material; a short
     * crafted-item carry also handles material brought into a session, whose removal
     * hits the zero floor before its compacted form arrives in a later tick.
     *
     * Every change that is counted is written to mining.tsv, so a session can be
     * checked line by line against the blocks in powder.tsv.
     */
    private static void countInventoryGain(Minecraft mc, boolean count) {
        Map<String, Long> now = materialValues(mc);
        Map<String, Long> crafted = craftedValues(mc);
        if (!count) gainLedger.clear();
        if (inventoryBaseline && count) {
            java.util.Set<String> seen = new java.util.HashSet<>(now.keySet());
            seen.addAll(lastValues.keySet());
            for (String m : seen) {
                long delta = now.getOrDefault(m, 0L) - lastValues.getOrDefault(m, 0L);
                if (delta == 0) continue;
                long before = totals.getOrDefault(m, 0L);
                long craftedIncrease = Math.max(0, crafted.getOrDefault(m, 0L) - lastCraftedValues.getOrDefault(m, 0L));
                long after = before + gainLedger.delta(m, before, delta, craftedIncrease, System.currentTimeMillis());
                if (after == before) continue;
                totals.put(m, after);
                lifetime.material(m, after - before);
                lifetimeDirty = true;
                if (delta > 0) focus(m, System.currentTimeMillis());
                audit(m, after - before, after, "bag");
            }
        }
        lastValues.clear();
        lastValues.putAll(now);
        lastCraftedValues.clear();
        lastCraftedValues.putAll(crafted);
        inventoryBaseline = true;
    }

    private static Map<String, Long> craftedValues(Minecraft mc) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (mc.player == null) return out;
        for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) {
            MaterialValue material = materialValue(stack);
            if (material != null && material.units() / stack.getCount() > 1)
                out.merge(material.name(), material.units(), Long::sum);
        }
        return out;
    }

    // ── the audit trail ──────────────────────────────────────────────────────
    private static final StringBuilder auditPending = new StringBuilder();
    private static long auditFlushed;

    private static void audit(String material, long delta, long total, String from) {
        auditPending.append(System.currentTimeMillis()).append('\t').append(material).append('\t')
                .append(delta).append('\t').append(total).append('\t').append(from).append('\n');
    }

    private static void flushAudit() {
        if (auditPending.length() == 0) return;
        String out = auditPending.toString();
        auditPending.setLength(0);
        auditFlushed = System.currentTimeMillis();
        try {
            java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("endsight");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path log = dir.resolve("mining.tsv");
            if (java.nio.file.Files.exists(log) && java.nio.file.Files.size(log) > 4L * 1024 * 1024) {
                java.nio.file.Files.move(log, dir.resolve("mining.tsv.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            java.nio.file.Files.writeString(log, out, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ignored) {
        }
    }

    private static Map<String, Long> materialValues(Minecraft mc) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (mc.player == null) return out;
        for (ItemStack s : mc.player.getInventory().getNonEquipmentItems()) {
            MaterialValue v = materialValue(s);
            if (v == null) continue;
            out.merge(v.name(), v.units(), Long::sum);
        }
        return out;
    }

    private record MaterialValue(String name, long units) {
    }

    private static MaterialValue materialValue(ItemStack s) {
        if (s.isEmpty()) return null;
        String name = Zealots.strip(s.getHoverName().getString()).trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("refined ") || lower.contains("pickaxe") || lower.contains("helmet")
                || lower.contains("chestplate") || lower.contains("leggings") || lower.contains("boots")) {
            return null;
        }
        String material = materialName(name);
        if (material == null) return null;
        long each = material.equals("Amethyst") ? amethystUnits(lower)
                : lower.contains("enchanted ") ? 160L : 1L;
        return new MaterialValue(material, (long) s.getCount() * each);
    }

    private static long amethystUnits(String lower) {
        if (lower.contains("perfect ")) return AMETHYST_PERFECT;
        if (lower.contains("flawless ")) return AMETHYST_FLAWLESS;
        if (lower.contains("fine ")) return AMETHYST_FINE;
        if (lower.contains("flawed ")) return AMETHYST_FLAWED;
        return 1L;
    }

    /** The material itself or its Enchanted/Refined/gem-tier forms; a drill with "obsidian" in its name is not obsidian. */
    private static String materialName(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replace("enchanted ", "").replace("refined ", "")
                .replace("rough ", "").replace("flawed ", "").replace("fine ", "").replace("flawless ", "").replace("perfect ", "").trim();
        return switch (lower) {
            case "crying obsidian" -> "Crying Obsidian";
            case "obsidian" -> "Obsidian";
            case "end stone" -> "End Stone";
            case "amethyst" -> "Amethyst";
            default -> null;
        };
    }

    public static String formatItemCount(String item, long count) {
        String material = materialName(item);
        if (material == null) return compact(count);
        String lower = item.toLowerCase(Locale.ROOT);
        if (lower.contains("rough ") || lower.contains("flawed ") || lower.contains("fine ")
                || lower.contains("flawless ") || lower.contains("perfect ")) {
            return compact(count);
        }
        if (lower.contains("refined ")) return tier(count, 1, REF);
        if (lower.contains("enchanted ")) return tier(count, 1, ENCH);
        return formatMaterialCount(material, count);
    }

    public static String formatMaterialCount(String material, long raw) {
        if (materialName(material) == null) return compact(raw);
        if (material.toLowerCase(Locale.ROOT).contains("amethyst")) {
            if (REF.equals(materialUnits)) return tier(raw, AMETHYST_FINE, "Fine");
            if (ENCH.equals(materialUnits)) return tier(raw, AMETHYST_FLAWED, "Flawed");
        }
        if (REF.equals(materialUnits) && supportsRef(material)) return tier(raw, 160L * 16L, REF);
        if (ENCH.equals(materialUnits) || REF.equals(materialUnits)) return tier(raw, 160L, ENCH);
        return compact(raw);
    }

    private static boolean supportsRef(String material) {
        return !material.toLowerCase(Locale.ROOT).contains("amethyst");
    }

    private static void draw(GuiGraphicsExtractor g) {
        if (!readoutVisible()) return;
        Minecraft mc = Minecraft.getInstance();
        HudLayout.draw(ID, g, mc.font, false);
    }

    private static boolean readoutVisible() {
        Minecraft mc = Minecraft.getInstance();
        return enabled && mc.player != null && !mc.options.hideGui && hudVisible()
                && (mining || showWhenPaused && activeMs > 0)
                && com.endsight.zealots.ZealotTracker.lastActivity() <= lastActive;
    }

    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        return TrackerHud.draw(g, font, x, y, "Mining Profit", "[" + hudPeriod + "]", Theme.muted(), hudRows(sample), -1);
    }

    private record MiningHudState(boolean sample, String period, String units, boolean breakdown, boolean compact,
                                   boolean profit, boolean fragmentsShown, long fragments, long sold,
                                   long elapsedQuarter, Map<String, Long> materials, Object palette) { }
    private static final Map<String, Long> SAMPLE_MATERIALS = Map.of("Amethyst", 3_008_000L);
    private static MiningHudState shownState;
    private static List<HudCard.Row> shownRows;

    private static List<HudCard.Row> hudRows(boolean sample) {
        boolean allTime = hudPeriod.equals("Total");
        long shownMs = sample ? 754_000L : allTime ? lifetime.activeMs : activeMs;
        Map<String, Long> materials = sample ? SAMPLE_MATERIALS
                : allTime ? lifetime.materials : totals;
        long fragments = sample ? 3 : allTime ? lifetime.fragments : VoidFragments.found();
        long coins = sample ? 1_237_500L : allTime ? lifetime.sold : soldCoins;
        MiningHudState state = new MiningHudState(sample, hudPeriod, materialUnits, breakdown, compactList,
                profitEstimate, VoidFragments.show, fragments, coins, shownMs / 250, materials, Theme.palette());
        if (state.equals(shownState)) return shownRows;
        // Comparing against a snapshot catches inventory/reset/load changes without
        // sorting and formatting the complete material list in every render pass.
        shownRows = List.copyOf(buildHudRows(materials, fragments, coins, shownMs));
        shownState = new MiningHudState(sample, hudPeriod, materialUnits, breakdown, compactList,
                profitEstimate, VoidFragments.show, fragments, coins, shownMs / 250, Map.copyOf(materials), Theme.palette());
        return shownRows;
    }

    private static List<HudCard.Row> buildHudRows(Map<String, Long> materials, long fragments, long coins, long shownMs) {
        long profit = profitValue(materials, fragments);
        List<HudCard.Row> rows = new ArrayList<>();
        if (breakdown) {
            List<MiningItem> mined = new ArrayList<>();
            materials.forEach((name, count) -> {
                if (count > 0) mined.add(new MiningItem(name, formatMaterialCount(name, count), value(name, count)));
            });
            if (VoidFragments.show && fragments > 0)
                mined.add(new MiningItem("Void Fragment", compact(fragments), fragments * VoidFragments.NPC_PRICE));
            mined.sort(java.util.Comparator.comparingLong(MiningItem::coins).reversed().thenComparing(MiningItem::name));
            for (MiningItem item : mined.stream().limit(compactList ? 5 : Long.MAX_VALUE).toList()) {
                String label = item.name().equals("Void Fragment") ? "§5◇ Void Fragment"
                        : item.name().equals("Amethyst") ? "§d◇ Amethyst" : "§f◇ " + shortMaterial(item.name());
                rows.add(new HudCard.Row(label, item.amount() + " §8· §7" + coinAmount(item.coins()), Theme.text()));
            }
        }
        if (rows.isEmpty() && breakdown) rows.add(new HudCard.Row("No materials yet", "", Theme.muted()));
        if (profitEstimate) {
            // Use every mined material and fragment, even when the visible list is
            // compact or a row is disabled. Sold items are already part of this value.
            rows.add(new HudCard.Row("§6Profit", coinAmount(profit) + " coins", Theme.pos()));
            rows.add(new HudCard.Row("§6Profit/h", rate(profit, shownMs), Theme.pos()));
        } else if (coins > 0) {
            rows.add(new HudCard.Row("Sold", coinAmount(coins) + " coins", Theme.pos()));
            rows.add(new HudCard.Row("Sold/h", rate(coins, shownMs), Theme.pos()));
        }
        rows.add(new HudCard.Row("Time mined", time(shownMs)));
        return rows;
    }

    private record MiningItem(String name, String amount, long coins) { }

    static long profitValue(Map<String, Long> materials, long fragments) {
        long out = Math.max(0, fragments) * VoidFragments.NPC_PRICE;
        for (Map.Entry<String, Long> entry : materials.entrySet()) out += value(entry.getKey(), Math.max(0, entry.getValue()));
        return out;
    }

    public static boolean acceptsMiningDrop() {
        return enabled && lastActive > 0 && System.currentTimeMillis() - lastActive <= 60_000 && hudVisible();
    }

    static void recordFragments(long count) {
        if (count > 0) { lifetime.fragments += count; lifetimeDirty = true; }
    }

    private static java.nio.file.Path lifetimeFile() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("endsight/mining-totals.properties");
    }

    private static void loadLifetime() {
        java.nio.file.Path path = lifetimeFile();
        if (!java.nio.file.Files.exists(path)) return;
        try (var input = java.nio.file.Files.newInputStream(path)) {
            java.util.Properties data = new java.util.Properties();
            data.load(input);
            lifetime.load(data);
        } catch (java.io.IOException | IllegalArgumentException e) {
            System.err.println("[Endsight] Could not read mining totals: " + e.getMessage());
        }
    }

    private static void saveLifetime() {
        if (!lifetimeDirty) return;
        java.nio.file.Path path = lifetimeFile(), temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            java.nio.file.Files.createDirectories(path.getParent());
            try (var output = java.nio.file.Files.newOutputStream(temporary)) {
                lifetime.properties().store(output, "Endsight mining totals");
            }
            try { java.nio.file.Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            lifetimeDirty = false;
            lifetimeSaved = System.currentTimeMillis();
        } catch (java.io.IOException e) {
            System.err.println("[Endsight] Could not save mining totals: " + e.getMessage());
        }
    }

    private static String rate(long n, long ms) {
        if (n <= 0 || ms < 5_000) return "—";
        long perHour = Math.round(n * 3_600_000.0 / ms);
        return coinAmount(perHour);
    }

    private static String materialRate(String material, long raw, long ms) {
        if (raw <= 0 || ms < 5_000) return "—";
        long perHour = Math.round(raw * 3_600_000.0 / ms);
        return formatMaterialCount(material, perHour);
    }

    private static String coinAmount(long n) {
        if (n >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1fB", n / 1_000_000_000.0).replace(".0B", "B");
        if (n >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0).replace(".0M", "M");
        return compact(n);
    }

    private static long totalValue() {
        long out = 0;
        for (Map.Entry<String, Long> e : totals.entrySet()) out += value(e.getKey(), e.getValue());
        return out;
    }

    private static long value(String material, long raw) {
        if (material.equalsIgnoreCase("Amethyst")) {
            // Sale evidence on 2026-10-20: Fine/Flawless have the same raw-unit
            // value, but unfinished Flawed and Rough sell for less. The tracker
            // stores raw-equivalent gains so compactor moves stay neutral.
            long remainder = raw % AMETHYST_FINE;
            return raw / AMETHYST_FINE * 1_305_600L
                    + remainder / AMETHYST_FLAWED * 8_160L
                    + Math.round(remainder % AMETHYST_FLAWED * 50.4);
        }
        return raw * price(material);
    }

    private static long price(String material) {
        String m = material.toLowerCase(Locale.ROOT);
        if (m.contains("crying obsidian")) return 2857;
        if (m.contains("obsidian")) return 400;
        if (m.contains("end stone")) return 50;
        return 0;
    }

    private static String shortMaterial(String material) {
        return material.replace("Crying Obsidian", "Crying Ob.")
                .replace("Obsidian", "Obsidian")
                .replace("End Stone", "End Stone");
    }

    private static String compact(long n) {
        if (n >= 1_000_000) return String.format("%.1fm", n / 1_000_000.0);
        if (n >= 10_000) return Math.round(n / 1_000.0) + "k";
        if (n >= 1_000) return String.format("%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }

    /**
     * Whole units only. Less than one is said in the unit below - "8 Ench" rather than
     * "0.5 Ref", a raw count rather than "0.01 Ench" - because a fraction of a refine is
     * not a thing anyone holds.
     */
    private static String tier(long n, long unit, String suffix) {
        if (n <= 0) return "0 " + suffix;
        if (n < unit) {
            if (REF.equals(suffix)) return tier(n, 160L, ENCH);
            if ("Fine".equals(suffix)) return tier(n, AMETHYST_FLAWED, "Flawed");
            return compact(n);
        }
        long v = n / unit;
        return (v >= 1_000 ? String.format("%.1fk", v / 1_000.0) : String.valueOf(v)) + " " + suffix;
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3_600; s %= 3_600;
        long m = s / 60; s %= 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
}
