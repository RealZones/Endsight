package com.endsight.storage;

import com.endsight.hud.HudCard;
import com.endsight.hud.HudLayout;
import com.endsight.hud.TrackerDropOrder;
import com.endsight.hud.TrackerHud;
import com.endsight.qol.Drops;
import com.endsight.qol.Rarity;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Own completed Cache openings, with all rewards retained regardless of mob family. */
public final class CacheTracker {
    private static final String ID = "tracker.caches";
    private static final CacheLedger ledger = new CacheLedger();
    private static final CacheVisibility visibility = new CacheVisibility();
    private static Object level;
    private static BlockPos pendingBlock, ownBlock;
    private static long attemptedAt;
    private static boolean enabled = true, compact = true, dirty, historySafe = true;
    private static String period = "Session";
    private static long savedAt;
    private static long rowVersion = -1, rowQuarter = -1;
    private static String rowPeriod;
    private static boolean rowCompact;
    private static Object rowPalette;
    private static List<HudCard.Row> cachedRows = List.of();
    private CacheTracker() { }

    public static Module module() {
        return new Module(ID, "Cache Tracker", "Crypt Caches opened and every reward, in your usual drop order.",
                "The End", () -> enabled, value -> { enabled = value; if (!value) resetSource(); }, List.of(
                new Setting.Choice("Period", "Show this session or saved totals.", List.of("Session", "All Time"), () -> period, value -> period = value),
                new Setting.Toggle("Compact list", "Show five drops; /drops cache lists every item.", () -> compact, value -> compact = value),
                new Setting.Action("Reset session", "Clear this session without changing saved totals.", "Reset", ledger::resetSession),
                new Setting.Note("Counting", () -> "Shows from your placement until 3 seconds after despawn. Only your Cache rewards count; /drops cache shows saved totals anytime.")));
    }

    public static void init() {
        load();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) message(formatted(message)); });
        ClientReceiveMessageEvents.GAME_CANCELED.register((message, overlay) -> { if (!overlay) message(formatted(message)); });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetSource());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { resetSource(); save(); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> save());
        ClientTickEvents.END_CLIENT_TICK.register(CacheTracker::tick);
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (enabled && player == Minecraft.getInstance().player
                    && Zealots.strip(player.getItemInHand(hand).getHoverName().getString()).trim().equals("Crypt Cache")) {
                pendingBlock = hit.getBlockPos().relative(hit.getDirection());
                attemptedAt = System.currentTimeMillis();
            }
            return InteractionResult.PASS;
        });
        // The [Session] label looked like Mining Session's switch but had no click behind it,
        // so clicking it in chat did nothing. Same hit box as Mining Session's now.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!HudLayout.interactiveScreen(screen)) return;
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                if (click.button() != 0 || !enabled || mc.options.hideGui) return true;
                int[] bounds = HudLayout.bounds(ID);
                if (bounds == null) return true;
                float scale = HudLayout.scale(ID);
                int labelW = mc.font.width(period.equals("All Time") ? "[Total]" : "[Session]");
                double right = bounds[0] + bounds[2] - TrackerHud.PAD * scale;
                if (click.x() < right - (labelW + 2) * scale || click.x() >= right + 2 * scale
                        || click.y() < bounds[1] || click.y() >= bounds[1] + TrackerHud.HEADER_H * scale) return true;
                period = period.equals("All Time") ? "Session" : "All Time";
                com.endsight.Config.save(com.endsight.EndsightClient.registry());
                return false;
            });
        });
        HudLayout.register(ID, "Cache Tracker", .006f, .78f, CacheTracker::drawAt);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "cache_tracker"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (enabled && mc.player != null && !mc.options.hideGui
                    && visibility.visible(System.currentTimeMillis()))
                HudLayout.draw(ID, g, mc.font, false);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            var cache = ClientCommands.literal("cache").executes(c -> say(true));
            cache.then(ClientCommands.literal("session").executes(c -> say(false)));
            cache.then(ClientCommands.literal("reset").executes(c -> { ledger.resetSession(); return say(false); }));
            dispatcher.register(ClientCommands.literal("drops").then(cache));
        });
    }

    private static void message(String raw) {
        if (!enabled) { ledger.resetSource(); return; }
        Minecraft mc = Minecraft.getInstance();
        checkLevel(mc);
        long now = System.currentTimeMillis(), before = ledger.opened(false);
        var update = ledger.message(raw, now);
        if (update.placed()) {
            visibility.placed(now);
            ownBlock = now - attemptedAt <= 3_000 ? pendingBlock : null;
            if (ownBlock == null && mc.hitResult instanceof BlockHitResult hit && mc.level != null) {
                ownBlock = mc.level.getBlockState(hit.getBlockPos()).is(Blocks.VAULT)
                        ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
            }
            pendingBlock = null;
        }
        if (ledger.opened(false) > before) visibility.result(now);
        if (update.changed()) dirty = true;
    }

    // getString() discards styled component colours. Keep those colours so unseen
    // rewards and Epic/Legendary pets retain their identity before recipe lookup.
    static String formatted(Component message) {
        StringBuilder raw = new StringBuilder();
        message.visit((style, text) -> {
            raw.append('§').append(code(style.getColor() == null ? 0xFFFFFF : style.getColor().getValue())).append(text);
            return Optional.<Object>empty();
        }, Style.EMPTY);
        return raw.toString();
    }

    private static void resetSource() {
        ledger.resetSource(); visibility.reset(); ownBlock = pendingBlock = null;
    }
    private static void checkLevel(Minecraft mc) {
        if (level != mc.level) { resetSource(); level = mc.level; }
    }
    private static void tick(Minecraft mc) {
        checkLevel(mc);
        long now = System.currentTimeMillis();
        // Follow the block the local player clicked, armed only by the private
        // confirmation. Scanning arbitrary nearby vaults would follow other players.
        if (enabled && mc.level != null && ownBlock != null)
            visibility.observe(mc.level.getBlockState(ownBlock).is(Blocks.VAULT), now);
        if (dirty && now - savedAt > 2_000) save();
    }

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("endsight/cache-tracker.json"); }
    private static void load() {
        if (!Files.isRegularFile(file())) return;
        try {
            CacheLedger.Counts counts = new Gson().fromJson(Files.readString(file()), CacheLedger.Counts.class);
            if (counts == null || counts.opened < 0 || counts.drops == null || counts.colours == null
                    || counts.drops.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null || e.getValue() < 0))
                throw new IllegalArgumentException("Invalid Cache totals");
            ledger.restore(counts);
        } catch (Exception e) {
            historySafe = false;
            System.err.println("[Endsight] Cache history could not be read; preserving the file and counting this session only: " + e);
        }
    }
    private static void save() {
        if (!dirty || !historySafe) return;
        try {
            Path target = file(), temp = target.resolveSibling("cache-tracker.json.tmp");
            Files.createDirectories(target.getParent());
            Files.writeString(temp, new Gson().toJson(ledger.counts(true)), StandardCharsets.UTF_8);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            dirty = false; savedAt = System.currentTimeMillis();
        } catch (Exception e) { savedAt = System.currentTimeMillis(); System.err.println("[Endsight] Could not save Cache totals: " + e); }
    }

    static List<Map.Entry<String,Integer>> ordered(CacheLedger.Counts counts) {
        return counts.drops.entrySet().stream().filter(e -> e.getValue() > 0).sorted(TrackerDropOrder.comparator(
                name -> rank(name, counts), name -> tier(name, counts), CacheTracker::plain)).toList();
    }
    private static int tier(String name, CacheLedger.Counts counts) {
        int listed = Drops.listedTierOf(name);
        if (listed >= 0) return listed;
        return switch (observedRank(name, counts)) { case 8,7,6,5 -> 3; case 4 -> 2; case 3 -> 1; default -> 0; };
    }
    private static int rank(String name, CacheLedger.Counts counts) {
        if (Drops.listedTierOf(name) < 0) return Math.max(1, observedRank(name, counts));
        return switch (tier(name, counts)) { case 3 -> 5; case 2 -> 4; case 1 -> 3; default -> 1; };
    }
    private static int observedRank(String name, CacheLedger.Counts counts) {
        int known = Rarity.rank(name);
        if (known >= 0) return known;
        return switch (counts.colours.getOrDefault(name, 0xFFFFFF)) {
            case 0xFF5555 -> 8; case 0x55FFFF -> 7; case 0xFF55FF -> 6; case 0xFFAA00 -> 5;
            case 0xAA00AA -> 4; case 0x5555FF -> 3; case 0x55FF55 -> 2; default -> 1;
        };
    }
    private static String plain(String item) { return item.replaceFirst("^\\[Lvl \\d+\\]\\s*", ""); }
    private static int colour(String name, CacheLedger.Counts counts) { return Rarity.colour(name, counts.colours.getOrDefault(name, 0xFFFFFF)); }

    static List<HudCard.Row> rows(CacheLedger.Counts counts, boolean compact) {
        List<HudCard.Row> rows = new ArrayList<>();
        rows.add(new HudCard.Row("Opened", String.format(Locale.ROOT, "%,d", counts.opened)));
        var drops = ordered(counts); int limit = compact ? 5 : 10;
        for (var drop : drops.stream().limit(limit).toList())
            rows.add(new HudCard.Row("§" + code(colour(drop.getKey(), counts)) + plain(drop.getKey()), String.format(Locale.ROOT, "%,d", drop.getValue())));
        if (drops.isEmpty()) rows.add(new HudCard.Row("Drops", "None yet"));
        if (drops.size() > limit) rows.add(new HudCard.Row("More items", "+" + (drops.size() - limit) + " §8(/drops cache)"));
        return rows;
    }
    private static char code(int colour) {
        for (var value : net.minecraft.ChatFormatting.values())
            if (value.isColor() && value.getColor() != null && value.getColor() == (colour & 0xFFFFFF)) return value.getChar();
        return 'f';
    }
    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        if (sample) {
            var counts = new CacheLedger.Counts(); counts.opened = 18;
            counts.drops.put("Giant's Core", 1); counts.colours.put("Giant's Core", 0xFFAA00);
            counts.drops.put("Summoning Eye", 3); counts.colours.put("Summoning Eye", 0xAA00AA);
            counts.drops.put("Dragon Scale", 6); counts.colours.put("Dragon Scale", 0x5555FF);
            return TrackerHud.draw(g, font, x, y, "Cache Tracker", "[Session]", Theme.muted(), rows(counts, compact), -1);
        }
        // Snapshots and rarity/name ordering scale with recorded items, so rebuild
        // only after a change or the same short refresh used by the boss tracker.
        long quarter = System.currentTimeMillis() / 250;
        if (rowVersion != ledger.revision() || rowQuarter != quarter || !period.equals(rowPeriod)
                || rowCompact != compact || rowPalette != Theme.palette()) {
            cachedRows = rows(ledger.counts(period.equals("All Time")), compact);
            rowVersion = ledger.revision(); rowQuarter = quarter;
            rowPeriod = period; rowCompact = compact; rowPalette = Theme.palette();
        }
        return TrackerHud.draw(g, font, x, y, "Cache Tracker", period.equals("All Time") ? "[Total]" : "[Session]",
                Theme.muted(), cachedRows, -1);
    }
    private static int say(boolean allTime) {
        Minecraft mc = Minecraft.getInstance(); if (mc.player == null) return 0;
        var counts = ledger.counts(allTime);
        mc.player.sendSystemMessage(Component.literal("§6Crypt Caches §8(" + (allTime ? "all time" : "session") + ") §7— §f" + counts.opened + " opened"));
        var list = ordered(counts);
        if (list.isEmpty()) mc.player.sendSystemMessage(Component.literal("§8  No drops recorded yet."));
        for (var drop : list) mc.player.sendSystemMessage(Component.literal("  " + drop.getKey()).withColor(colour(drop.getKey(), counts))
                .append(Component.literal(" §f" + String.format(Locale.ROOT, "%,d", drop.getValue()))));
        return 1;
    }
}
