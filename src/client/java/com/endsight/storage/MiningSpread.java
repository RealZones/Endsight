package com.endsight.storage;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Learns DragSim's Mining Spread from what the client can actually see.
 *
 * The stat tells us "how much" spread you have, not exactly which neighbour blocks the
 * server will pick at a weird angle. Rather than fake certainty, this watches the
 * nearby blocks that turn into bedrock right after your mined block does, records their
 * offsets for that hit angle, and reuses those observed offsets as the overlay.
 */
public final class MiningSpread {

    private MiningSpread() {
    }

    private static final Pattern TOTAL = Pattern.compile("(?i).*\\bMining\\s+Spread\\D+([\\d,]+).*");
    private static final Pattern GRANT = Pattern.compile("(?i).*Grants\\s+\\+?([\\d,]+)\\s+Mining\\s+Spread.*");
    private static final int WATCH_R = 3;
    private static final long SAMPLE_MS = 1_800;
    private static final long RECENT_MS = 2_500;
    private static final int TARGET_STROKE = 0xDDFFFF55;
    private static final int TARGET_FILL = 0x08FFFF55;
    private static final int CONNECTED_STROKE = 0xAA55FF88;
    private static final int CONNECTED_FILL = 0x0455FF88;
    private static final int CONFIRMED_STROKE = 0xCC55FF88;
    private static final int CONFIRMED_FILL = 0x0A55FF88;

    private static boolean enabled = true;
    private static boolean overlay = true;
    private static boolean learn = true;
    private static boolean log = true;
    private static int spread = -1;
    private static int efficientMiner = -1;
    private static long spreadSeen;
    private static long lastMenuScan;

    private static Sample sample;
    private static final Map<String, Set<Off>> learned = new LinkedHashMap<>();
    private static final Map<String, Set<Off>> latest = new LinkedHashMap<>();
    private static final List<Recent> recent = new ArrayList<>();

    private record Off(int x, int y, int z) {
        BlockPos at(BlockPos origin) {
            return origin.offset(x, y, z);
        }

        boolean zero() {
            return x == 0 && y == 0 && z == 0;
        }
    }

    private record Recent(BlockPos pos, long until) {
    }

    private static final class Sample {
        final BlockPos origin;
        final String material;
        final String key;
        final long started;
        final Map<BlockPos, Block> before;
        final boolean clean;
        final Set<Off> seen = new LinkedHashSet<>();

        Sample(BlockPos origin, String material, String key, long started, Map<BlockPos, Block> before, boolean clean) {
            this.origin = origin;
            this.material = material;
            this.key = key;
            this.started = started;
            this.before = before;
            this.clean = clean;
        }
    }

    public static Module module() {
        return new Module("mining.spread", "Mining Spread",
                "Reads your Mining Spread stat and learns the extra blocks DragSim mines.", "Mining",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Toggle("Block overlay",
                                "Highlight learned spread blocks around the block you are mining.",
                                () -> overlay, v -> overlay = v),
                        new Setting.Toggle("Learn from bedrock",
                                "Watch nearby blocks turn into bedrock and remember those offsets.",
                                () -> learn, v -> learn = v),
                        new Setting.Toggle("Write samples",
                                "Save observed spread hits to config/endsight/mining-spread-log.txt.",
                                () -> log, v -> log = v),
                        new Setting.Action("Patterns",
                                "Forget learned spread offsets.",
                                "Clear", MiningSpread::clear),
                        new Setting.Note("Known", MiningSpread::summary)));
    }

    public static void init() {
        load();
        ClientTickEvents.END_CLIENT_TICK.register(MiningSpread::tick);
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> gizmos());
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?> container) {
                ScreenEvents.afterTick(screen).register(s -> scanMenu(container));
            }
        });
    }

    private static String summary() {
        String s = spread >= 0 ? String.valueOf(spread) : "unknown";
        String e = efficientMiner >= 0 ? ", Efficient Miner +" + efficientMiner : "";
        return "Spread " + s + e + ", " + learned.values().stream().mapToInt(Set::size).sum()
                + " learned offsets";
    }

    private static void clear() {
        learned.clear();
        latest.clear();
        recent.clear();
        sample = null;
        save();
    }

    private static void scanMenu(AbstractContainerScreen<?> screen) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if (now - lastMenuScan < 250) return;
        lastMenuScan = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Container playerInv = mc.player.getInventory();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == playerInv || slot.getItem().isEmpty()) continue;
            readStats(slot.getItem());
        }
    }

    private static void readStats(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        for (Component c : stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL)) {
            String line = Zealots.strip(c.getString()).trim();
            Matcher grant = GRANT.matcher(line);
            if (grant.matches()) {
                efficientMiner = parse(grant.group(1));
                continue;
            }
            if (line.toLowerCase(Locale.ROOT).contains("grants")) continue;
            Matcher total = TOTAL.matcher(line);
            if (total.matches()) {
                spread = parse(total.group(1));
                spreadSeen = System.currentTimeMillis();
            }
        }
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) {
            sample = null;
            recent.clear();
            return;
        }
        long now = System.currentTimeMillis();
        recent.removeIf(r -> r.until() <= now);
        Hit hit = hit(mc);
        if (!attackingWithTool(mc)) {
            sample = null;
            return;
        }
        if (hit == null) {
            if (learn && sample != null && now - sample.started <= SAMPLE_MS) observe(mc, sample);
            else sample = null;
            return;
        }
        if (!learn) return;
        if (sample == null || !sample.origin.equals(hit.pos()) || now - sample.started > SAMPLE_MS) {
            Snapshot snap = snapshot(mc, hit.pos());
            sample = new Sample(hit.pos(), hit.material(), hit.key(), now, snap.blocks(), snap.clean());
            return;
        }
        observe(mc, sample);
    }

    private static void observe(Minecraft mc, Sample s) {
        if (!s.clean) return;
        Set<Off> added = new LinkedHashSet<>();
        for (Map.Entry<BlockPos, Block> e : s.before.entrySet()) {
            Block nowBlock = mc.level.getBlockState(e.getKey()).getBlock();
            if (nowBlock == e.getValue()) continue;
            if (nowBlock != Blocks.BEDROCK && nowBlock != Blocks.AIR) continue;
            if (nowBlock == Blocks.BEDROCK) markRecent(e.getKey().immutable(), System.currentTimeMillis() + RECENT_MS);
            Off off = new Off(e.getKey().getX() - s.origin.getX(),
                    e.getKey().getY() - s.origin.getY(),
                    e.getKey().getZ() - s.origin.getZ());
            if (off.zero() || !s.seen.add(off)) continue;
            added.add(off);
        }
        if (added.isEmpty()) return;
        latest.put(s.key, new LinkedHashSet<>(s.seen));
        Set<Off> set = learned.computeIfAbsent(s.key, k -> new LinkedHashSet<>());
        if (set.addAll(added)) save();
        if (log) writeLog(s, added);
    }

    private static void markRecent(BlockPos pos, long until) {
        recent.removeIf(r -> r.pos().equals(pos));
        recent.add(new Recent(pos, until));
    }

    private record Snapshot(Map<BlockPos, Block> blocks, boolean clean) {
    }

    private static Snapshot snapshot(Minecraft mc, BlockPos origin) {
        Map<BlockPos, Block> out = new HashMap<>();
        boolean clean = true;
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-WATCH_R, -WATCH_R, -WATCH_R),
                origin.offset(WATCH_R, WATCH_R, WATCH_R))) {
            BlockPos key = p.immutable();
            Block b = mc.level.getBlockState(key).getBlock();
            if (b == Blocks.BEDROCK && !key.equals(origin)) clean = false;
            if (b != Blocks.AIR && b != Blocks.BEDROCK) out.put(key, b);
        }
        return new Snapshot(out, clean);
    }

    private static Hit hit(Minecraft mc) {
        if (mc.screen != null || mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return null;
        if (!attackingWithTool(mc)) return null;
        BlockHitResult blockHit = (BlockHitResult) mc.hitResult;
        BlockPos pos = blockHit.getBlockPos();
        String material = materialName(mc.level.getBlockState(pos).getBlock().getName().getString());
        if (material == null) return null;
        return new Hit(pos.immutable(), material, key(mc, material, blockHit.getDirection()));
    }

    private record Hit(BlockPos pos, String material, String key) {
    }

    private static boolean miningTool(ItemStack s) {
        if (s.isEmpty()) return false;
        if (s.is(ItemTags.PICKAXES)) return true;
        String name = Zealots.strip(s.getHoverName().getString()).toLowerCase(Locale.ROOT);
        return name.contains("pickaxe") || name.contains("drill");
    }

    private static boolean attackingWithTool(Minecraft mc) {
        return mc.screen == null && mc.options.keyAttack.isDown() && miningTool(mc.player.getMainHandItem());
    }

    private static String materialName(String name) {
        String lower = Zealots.strip(name).toLowerCase(Locale.ROOT);
        if (lower.contains("crying obsidian")) return "crying_obsidian";
        if (lower.contains("obsidian")) return "obsidian";
        if (lower.contains("end stone")) return "end_stone";
        if (lower.contains("amethyst")) return "amethyst";
        return null;
    }

    private static String key(Minecraft mc, String material, Direction face) {
        Vec3 look = mc.player.getLookAngle();
        double yaw = Math.atan2(-look.x, look.z);
        int yaw8 = Math.floorMod((int) Math.round(yaw / (Math.PI / 4.0)), 8);
        int pitch = look.y > 0.35 ? 1 : look.y < -0.35 ? -1 : 0;
        return material + "|" + face.getName() + "|y" + yaw8 + "|p" + pitch;
    }

    private static void gizmos() {
        if (!enabled || !overlay) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        Hit hit = hit(mc);
        BlockPos origin = hit != null ? hit.pos() : sample != null && attackingWithTool(mc) ? sample.origin : null;
        String key = hit != null ? hit.key() : sample != null && attackingWithTool(mc) ? sample.key : null;
        for (Recent r : recent) {
            if (mc.level.getBlockState(r.pos()).getBlock() == Blocks.BEDROCK) {
                markBlock(r.pos(), CONFIRMED_STROKE, 1.5f, CONFIRMED_FILL);
            }
        }
        if (origin != null && mc.level.getBlockState(origin).getBlock() != Blocks.BEDROCK) {
            Block target = mc.level.getBlockState(origin).getBlock();
            if (target == Blocks.AIR) return;
            Set<Off> shape = key == null ? null : latest.get(key);
            if (shape == null || shape.isEmpty()) shape = key == null ? null : learned.get(key);
            if (shape != null) {
                int drawn = 0;
                for (Off off : shape) {
                    if (drawn >= extraCap()) break;
                    BlockPos p = off.at(origin);
                    if (mc.level.getBlockState(p).getBlock() == target) {
                        markBlock(p, CONNECTED_STROKE, 1.0f, CONNECTED_FILL);
                        drawn++;
                    }
                }
            }
            markBlock(origin, TARGET_STROKE, 1.8f, TARGET_FILL);
        }
    }

    private static void markBlock(BlockPos p, int stroke, float width, int fill) {
        double in = 0.035;
        Gizmos.cuboid(new AABB(p.getX() + in, p.getY() + in, p.getZ() + in,
                        p.getX() + 1 - in, p.getY() + 1 - in, p.getZ() + 1 - in),
                GizmoStyle.strokeAndFill(stroke, width, fill)).setAlwaysOnTop();
    }

    private static int extraCap() {
        return spread > 0 ? Math.max(1, Math.min(8, spread / 100)) : 4;
    }

    private static Path patternsFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("mining-spread-patterns.txt");
    }

    private static Path logFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("mining-spread-log.txt");
    }

    private static void save() {
        List<String> lines = new ArrayList<>();
        lines.add("# key\tdx,dy,dz ...");
        for (Map.Entry<String, Set<Off>> e : learned.entrySet()) {
            StringBuilder sb = new StringBuilder(e.getKey());
            for (Off o : e.getValue()) sb.append('\t').append(o.x()).append(',').append(o.y()).append(',').append(o.z());
            lines.add(sb.toString());
        }
        try {
            Files.createDirectories(patternsFile().getParent());
            Files.write(patternsFile(), lines, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            System.err.println("[Endsight] could not write mining spread patterns: " + ex);
        }
    }

    private static void load() {
        Path f = patternsFile();
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\\t");
                Set<Off> set = learned.computeIfAbsent(parts[0], k -> new LinkedHashSet<>());
                for (int i = 1; i < parts.length; i++) {
                    String[] xyz = parts[i].split(",");
                    if (xyz.length != 3) continue;
                    set.add(new Off(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
                }
            }
            loadLatestLog();
        } catch (IOException | NumberFormatException ex) {
            System.err.println("[Endsight] could not read mining spread patterns: " + ex);
        }
    }

    private static void loadLatestLog() {
        Path f = logFile();
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\\t");
                if (parts.length < 5) continue;
                String key = parts[2];
                String offsets = "";
                for (String p : parts) if (p.startsWith("offsets=")) offsets = p.substring("offsets=".length());
                Set<Off> parsed = parseOffsets(offsets);
                if (!parsed.isEmpty()) latest.put(key, parsed);
            }
        } catch (IOException ignored) {
        }
    }

    private static Set<Off> parseOffsets(String offsets) {
        Set<Off> out = new LinkedHashSet<>();
        for (String one : offsets.split(";")) {
            String[] xyz = one.split(",");
            if (xyz.length != 3) continue;
            try {
                out.add(new Off(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static void writeLog(Sample s, Set<Off> added) {
        StringBuilder line = new StringBuilder(Instant.now().toString())
                .append('\t').append("spread=").append(spread)
                .append('\t').append(s.key)
                .append('\t').append("origin=").append(s.origin.getX()).append(',').append(s.origin.getY()).append(',').append(s.origin.getZ())
                .append('\t').append("offsets=");
        boolean first = true;
        for (Off o : added) {
            if (!first) line.append(';');
            line.append(o.x()).append(',').append(o.y()).append(',').append(o.z());
            first = false;
        }
        line.append('\n');
        try {
            Files.createDirectories(logFile().getParent());
            Files.writeString(logFile(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            System.err.println("[Endsight] could not write mining spread log: " + ex);
        }
    }
}
