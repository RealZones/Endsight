package com.endsight.storage;

import com.endsight.dragons.DragonTimer;
import com.endsight.hud.HudLayout;
import com.endsight.hud.Readout;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
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
    private static final Pattern SOLD = Pattern.compile("^You sold ([\\d,]+)x (.+?) for ([\\d,]+) coins!?$");
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
    private static String materialUnits = ENCH;
    private static long activeMs;
    private static long lastTick;
    private static long lastActive;
    private static boolean mining;
    private static String currentMaterial = "Material";
    private static final Map<String, Long> lastValues = new LinkedHashMap<>();
    private static final Map<String, Long> totals = new LinkedHashMap<>();
    private static final Map<String, Long> activeByMaterial = new LinkedHashMap<>();
    private static long soldCoins;

    public static Module module() {
        return new Module("mining.session", "Mining Session",
                "Active mining time, material gain and rate; pauses when you stop mining.", "Mining",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Toggle("Show while paused", "Keep the readout up after you stop mining.",
                                () -> showWhenPaused, v -> showWhenPaused = v),
                        new Setting.Choice("Material units",
                                "Show mined material counts as raw blocks, enchanted items or refined crafts.",
                                List.of(RAW, ENCH, REF), () -> materialUnits, v -> materialUnits = v),
                        new Setting.Toggle("Profit estimate", "Estimate NPC sell value from mined blocks.",
                                () -> profitEstimate, v -> profitEstimate = v),
                        new Setting.Action("Session", "Start the mining readout over.", "Reset", MiningSession::reset),
                        new Setting.Note("Now", () -> currentMaterial + ": "
                                + formatMaterialCount(currentMaterial, totals.getOrDefault(currentMaterial, 0L))
                                + ", " + time(activeMs) + " active")));
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(MiningSession::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && enabled) onLine(message);
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "mining_session"), (g, delta) -> draw(g));
        HudLayout.register(ID, "Mining Session", 0.006f, 0.63f,
                (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    private static void reset() {
        activeMs = 0;
        lastTick = 0;
        lastActive = 0;
        mining = false;
        currentMaterial = "Material";
        lastValues.clear();
        totals.clear();
        activeByMaterial.clear();
        soldCoins = 0;
    }

    private static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) {
            lastTick = 0;
            mining = false;
            return;
        }
        long now = System.currentTimeMillis();
        boolean active = miningNow(mc);
        String target = active ? targetMaterial(mc) : null;
        if (active) {
            if (target != null) currentMaterial = target;
            lastActive = now;
        }
        boolean counting = active || (lastActive != 0 && now - lastActive <= GRACE_MS);
        if (counting && lastTick != 0) {
            long dt = Math.min(now - lastTick, 1_000);
            activeMs += dt;
            if (!"Material".equals(currentMaterial)) activeByMaterial.merge(currentMaterial, dt, Long::sum);
        }
        countInventoryGain(mc, counting);
        mining = counting;
        lastTick = now;
    }

    private static void onLine(Component message) {
        String line = Zealots.strip(message.getString()).trim();
        if (DragonTimer.isPlayerChat(line)) return;
        Matcher m = SOLD.matcher(line);
        if (!m.matches()) return;
        soldCoins += parseLong(m.group(3));
        String material = materialName(m.group(2));
        if (material != null) currentMaterial = material;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
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
        return name.contains("pickaxe") || name.contains("drill");
    }

    private static String targetMaterial(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || mc.level == null) return null;
        String name = Zealots.strip(mc.level.getBlockState(hit.getBlockPos()).getBlock().getName().getString()).trim();
        return materialName(name);
    }

    private static void countInventoryGain(Minecraft mc, boolean count) {
        Map<String, Long> now = materialValues(mc);
        if (!lastValues.isEmpty() && count) {
            for (Map.Entry<String, Long> e : now.entrySet()) {
                long old = lastValues.getOrDefault(e.getKey(), 0L);
                long delta = e.getValue() - old;
                if (delta > 0) {
                    totals.merge(e.getKey(), delta, Long::sum);
                    currentMaterial = e.getKey();
                }
            }
        }
        lastValues.clear();
        lastValues.putAll(now);
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

    private static String materialName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("crying obsidian")) return "Crying Obsidian";
        if (lower.contains("obsidian")) return "Obsidian";
        if (lower.contains("end stone")) return "End Stone";
        if (lower.contains("amethyst")) return "Amethyst";
        return null;
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
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.options.hideGui) return;
        if (!mining && (!showWhenPaused || activeMs <= 0 || System.currentTimeMillis() - lastActive > 300_000)) return;
        HudLayout.draw(ID, g, mc.font, false);
    }

    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        boolean hot = sample || mining;
        long shownMs = sample ? 754_000L : activeMs;
        String material = sample ? "Obsidian" : currentMaterial;
        long materialAmount = sample ? 318 : totals.getOrDefault(material, 0L);
        long materialMs = sample ? 754_000L : activeByMaterial.getOrDefault(material, 0L);
        long coins = sample ? 1_237_500L : soldCoins;
        long totalEst = sample ? 2_420_000L : totalValue();
        String title = hot ? "Active" : "Paused";
        String rate = materialRate(material, materialAmount, materialMs);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Session", time(shownMs)});
        rows.add(new String[]{shortMaterial(material), formatMaterialCount(material, materialAmount)});
        rows.add(new String[]{"Rate", rate});
        if (profitEstimate && (sample || totalEst > 0)) {
            rows.add(new String[]{"Total", compact(totalEst)});
            rows.add(new String[]{"Coin/h", rate(totalEst, shownMs)});
        } else if (sample || coins > 0) {
            rows.add(new String[]{"Coins", compact(coins)});
            rows.add(new String[]{"Coin/h", rate(coins, shownMs)});
        } else {
            rows.add(new String[]{"Time", time(materialMs)});
        }
        int w = Readout.width(font, "MINING", title);
        for (String[] row : rows) w = Math.max(w, Readout.width(font, row[0], row[1]));
        int h = Readout.ROW_H + 3 + rows.size() * (Readout.ROW_H + 2);
        if (g == null) return new int[]{w, h};

        Readout.tick(g, x, y, w, hot ? Theme.accent() : Theme.line());
        Draw.text(g, font, "MINING", Readout.left(x), y, Theme.muted());
        Draw.textRight(g, font, title, Readout.right(x, w), y, hot ? Theme.accent() : Theme.text());
        int ry = y + Readout.ROW_H + 3;
        for (String[] row : rows) {
            Draw.text(g, font, row[0], Readout.left(x), ry, Theme.dim());
            Draw.textRight(g, font, row[1], Readout.right(x, w), ry, Theme.text());
            ry += Readout.ROW_H + 2;
        }
        return new int[]{w, h};
    }

    private static String rate(long n, long ms) {
        if (n <= 0 || ms < 5_000) return "-/h";
        long perHour = Math.round(n * 3_600_000.0 / ms);
        return compact(perHour) + "/h";
    }

    private static String materialRate(String material, long raw, long ms) {
        if (raw <= 0 || ms < 5_000) return "-/h";
        long perHour = Math.round(raw * 3_600_000.0 / ms);
        return formatMaterialCount(material, perHour) + "/h";
    }

    private static long totalValue() {
        long out = 0;
        for (Map.Entry<String, Long> e : totals.entrySet()) out += value(e.getKey(), e.getValue());
        return out;
    }

    private static long value(String material, long raw) {
        return raw * price(material);
    }

    private static long price(String material) {
        String m = material.toLowerCase(Locale.ROOT);
        if (m.contains("crying obsidian")) return 2857;
        if (m.contains("obsidian")) return 400;
        if (m.contains("end stone")) return 50;
        if (m.contains("amethyst")) return 50;
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

    private static String tier(long n, long unit, String suffix) {
        if (n <= 0) return "0 " + suffix;
        double v = n / (double) unit;
        String s;
        if (v >= 1_000) s = String.format("%.1fk", v / 1_000.0);
        else if (v >= 100) s = String.valueOf(Math.round(v));
        else if (v >= 10) s = String.format("%.1f", v);
        else s = String.format("%.2f", v);
        while (s.contains(".") && s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s + " " + suffix;
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
