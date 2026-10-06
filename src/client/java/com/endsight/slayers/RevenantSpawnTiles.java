package com.endsight.slayers;

import com.endsight.hud.Area;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Observed Revenant spawn tiles, not a prediction that every tile has been found.
 * The Crypt map coordinates have repeated across several private-lobby IDs, but new
 * layouts can still appear. The tiles mapped so far ship in the jar; new ones are
 * learned from the boss's first position into the player's local config, and a
 * learned tile needs a second sighting before it gets a permanent mark.
 */
public final class RevenantSpawnTiles {
    private RevenantSpawnTiles() { }

    private static boolean enabled = true;
    private static double range = 12;
    private static boolean spawnTracer = true;
    private static boolean throughWalls = true;
    private static int colour = 0xFF59FF00;
    private static final double HUE_SWEEP_DEGREES = 60;
    private static double width = 3;
    private static final long CAPTURE_NS = TimeUnit.SECONDS.toNanos(3);
    private static final long TRACE_HOLD_NS = TimeUnit.SECONDS.toNanos(3);
    private static final long TRACE_FADE_NS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long DEAD_TILE_SKIP_NS = TimeUnit.SECONDS.toNanos(1);
    private static final double BOSS_RANGE_SQR = 30 * 30;
    private static final Map<BlockPos, Integer> tiles = new HashMap<>();
    private static final Map<Entity, BlockPos> candidates = new IdentityHashMap<>();
    private static ClientLevel armedLevel;
    private static long armedNs;
    private static Entity activeBoss;
    private static BlockPos activeSpawnTile;
    private static BlockPos pendingTile;
    private static ClientLevel activeLevel;
    private static long activeSpawnNs;
    private static BlockPos lastBossTile;
    private static long lastBossEndNs;

    public static Module module() {
        return new Module("slayer.revenantTiles", "Revenant Spawn Tiles",
                "Block ESP for repeated Revenant spawn tiles in the Crypts.", "Visual",
                () -> enabled, v -> enabled = v,
                List.of(new Setting.Slider("Show within", "Distance for nearby observed tiles.",
                                12, 64, 4, () -> range, v -> range = v, "blocks"),
                        new Setting.Color("Chroma colour", "Pick a base hue, or Chroma for full rainbow.",
                                () -> colour, v -> colour = v),
                        new Setting.Slider("Edge width", "Thickness of the block outline.",
                                1, 5, 0.5, () -> width, v -> width = v, "px"),
                        new Setting.Toggle("Through walls", "Show the full box through nearby blocks.",
                                () -> throughWalls, v -> throughWalls = v),
                        new Setting.Toggle("Nearest tracer", "Line to the nearest tile; briefly follows the boss spawn.",
                                () -> spawnTracer, v -> spawnTracer = v)));
    }

    public static void init() {
        load();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String line = Zealots.strip(message.getString()).trim();
            if (line.contains("NICE! SLAYER BOSS SLAIN")) {
                endBoss();
                candidates.clear();
                armedNs = 0;
                armedLevel = null;
                return;
            }
            if (!line.contains("SLAYER BOSS SPAWNING")) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || !Area.crypts()) return;
            clearSpawn();
            armedLevel = mc.level;
            armedNs = System.nanoTime();
            candidates.clear();
        });
        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            Minecraft mc = Minecraft.getInstance();
            if (entity.getType() != EntityType.ZOMBIE || mc.player == null
                    || level != armedLevel || !capturing(System.nanoTime())
                    || entity.distanceToSqr(mc.player) > BOSS_RANGE_SQR) return;
            // NAME packets arrive after the zombie's spawn packet. Save its birth tile
            // now; reading its position only when named records a moved boss instead.
            candidates.put(entity, BlockPos.containing(entity.position()).below());
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            candidates.remove(entity);
            if (entity == activeBoss) endBoss();
        });
        ClientTickEvents.END_CLIENT_TICK.register(RevenantSpawnTiles::tick);
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> render());
    }

    private static boolean capturing(long now) {
        return armedNs != 0 && now >= armedNs && now - armedNs <= CAPTURE_NS;
    }

    /** The burst is known about a second before the boss entity arrives. Focus that
     * tile immediately, then replace it with the entity's exact birth tile. */
    static void onBurst(Vec3 position, ClientLevel level) {
        if (armedLevel != level || !capturing(System.nanoTime()) || !Area.crypts()) return;
        pendingTile = burstTile(position, tiles.keySet());
        activeLevel = level;
    }

    static BlockPos burstTile(Vec3 position, Set<BlockPos> observed) {
        BlockPos estimated = BlockPos.containing(position.x, position.y - .01, position.z);
        BlockPos match = null;
        double best = 2.5 * 2.5;
        for (BlockPos tile : observed) {
            if (Math.abs(tile.getY() - estimated.getY()) > 2) continue;
            double dx = tile.getX() + .5 - position.x;
            double dz = tile.getZ() + .5 - position.z;
            double distance = dx * dx + dz * dz;
            if (distance < best) {
                best = distance;
                match = tile;
            }
        }
        return match == null ? estimated : match;
    }

    private static void tick(Minecraft mc) {
        if (activeLevel != null && mc.level != activeLevel) clearSpawn();
        if (activeBoss != null && !activeBoss.isAlive()) endBoss();
        long now = System.nanoTime();
        if (mc.level != armedLevel || !capturing(now)) {
            candidates.clear();
            armedNs = 0;
            armedLevel = null;
            if (activeBoss == null) pendingTile = null;
            return;
        }
        var it = candidates.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Entity entity = entry.getKey();
            if (entity.getCustomName() == null
                    || !Zealots.strip(entity.getCustomName().getString()).contains("Atoned Horror")) continue;
            tiles.merge(entry.getValue(), 1, Integer::sum);
            save();
            activeBoss = entity;
            activeSpawnTile = entry.getValue();
            pendingTile = null;
            activeLevel = mc.level;
            activeSpawnNs = now;
            it.remove();
            // A warning is for one boss. Other zombies loaded in the same burst are
            // irrelevant, and must not be counted when their tags change later.
            candidates.clear();
            armedNs = 0;
            armedLevel = null;
            return;
        }
    }

    private static void endBoss() {
        if (activeBoss != null || pendingTile != null) {
            lastBossTile = activeSpawnTile != null ? activeSpawnTile : pendingTile;
            lastBossEndNs = System.nanoTime();
        }
        activeBoss = null;
        activeSpawnTile = null;
        pendingTile = null;
        activeLevel = null;
        activeSpawnNs = 0;
    }

    private static void clearSpawn() {
        endBoss();
    }

    private static double traceFade(long now) {
        if (activeSpawnNs == 0) return 0;
        long elapsed = now - activeSpawnNs;
        if (elapsed <= TRACE_HOLD_NS) return 1;
        if (elapsed >= TRACE_HOLD_NS + TRACE_FADE_NS) return 0;
        return 1 - (elapsed - TRACE_HOLD_NS) / (double) TRACE_FADE_NS;
    }

    private static void render() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.level == null || mc.options.hideGui || !Area.crypts()) return;
        long now = System.nanoTime();
        boolean pending = pendingTile != null && capturing(now) && mc.level == activeLevel;
        BlockPos focused = activeBoss != null && mc.level == activeLevel
                ? activeSpawnTile : pending ? pendingTile : null;
        double trace = pending ? 1 : mc.level == activeLevel ? traceFade(now) : 0;
        List<VisibleTile> visible = new ArrayList<>();
        if (focused != null) {
            addVisible(mc, visible, focused, Math.max(range, 30), 1);
        } else {
            for (var entry : tiles.entrySet()) {
                if (entry.getValue() < 2) continue;
                addVisible(mc, visible, entry.getKey(), range, 0);
            }
        }
        double phase = (System.currentTimeMillis() % 4000) / 4000.0;
        float[] hsb = colour == Setting.Color.CHROMA ? null
                : java.awt.Color.RGBtoHSB((colour >> 16) & 255, (colour >> 8) & 255, colour & 255, null);
        for (VisibleTile tile : visible) drawBox(tile, phase, hsb);
        if (spawnTracer) {
            BlockPos skip = now - lastBossEndNs < DEAD_TILE_SKIP_NS ? lastBossTile : null;
            BlockPos nearest = focused == null ? nearestVisibleTile(visible, mc.player.position(), skip) : null;
            TraceTarget target = chooseTracer(pending ? pendingTile : null,
                    mc.level == activeLevel ? activeSpawnTile : null,
                    trace, activeBoss != null, nearest);
            if (target != null) tracer(mc, target.pos(), target.fade(), phase, hsb);
        }
    }

    private static void addVisible(Minecraft mc, List<VisibleTile> visible, BlockPos tile,
                                   double showRange, double focus) {
        if (!mc.level.hasChunkAt(tile) || mc.level.getBlockState(tile).isAir()) return;
        double dx = tile.getX() + 0.5 - mc.player.getX();
        double dy = tile.getY() + 1 - mc.player.getY();
        double dz = tile.getZ() + 0.5 - mc.player.getZ();
        double distanceSqr = dx * dx + dy * dy + dz * dz;
        if (distanceSqr > showRange * showRange) return;
        double fade = Math.min(1, (showRange - Math.sqrt(distanceSqr)) / 8);
        visible.add(new VisibleTile(tile, fade, focus));
    }

    record VisibleTile(BlockPos pos, double fade, double focus) { }

    static BlockPos nearestVisibleTile(List<VisibleTile> visible, Vec3 player, BlockPos skip) {
        BlockPos nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (VisibleTile tile : visible) {
            BlockPos pos = tile.pos();
            if (pos.equals(skip)) continue;
            double dx = pos.getX() + .5 - player.x;
            double dy = pos.getY() + 1 - player.y;
            double dz = pos.getZ() + .5 - player.z;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < best) {
                best = distance;
                nearest = pos;
            }
        }
        return nearest;
    }

    record TraceTarget(BlockPos pos, double fade) { }

    static TraceTarget chooseTracer(BlockPos pending, BlockPos spawned, double spawnFade,
                                    boolean bossAlive, BlockPos nearest) {
        if (pending != null) return new TraceTarget(pending, 1);
        if (spawned != null && spawnFade > 0) return new TraceTarget(spawned, spawnFade);
        // The boss's tile is the only highlight during the fight. Do not revive
        // its tracer after the requested short fade just because it is nearest.
        if (bossAlive || nearest == null) return null;
        return new TraceTarget(nearest, 1);
    }

    /** The same twelve-edge full-block box as Etherwarp, with the picker setting the
     * center of the animated hue sweep rather than turning the animation off. */
    private static void drawBox(VisibleTile tile, double phase, float[] hsb) {
        BlockPos p = tile.pos();
        AABB box = new AABB(p.getX(), p.getY(), p.getZ(),
                p.getX() + 1, p.getY() + 1, p.getZ() + 1);
        int rgb = chroma(phase, 0, hsb);
        int fill = ((int) ((27 + 19 * tile.focus()) * tile.fade()) << 24) | rgb;
        var shape = Gizmos.cuboid(box, GizmoStyle.fill(fill));
        if (throughWalls) shape.setAlwaysOnTop();
        float lineWidth = (float) (width * (1 + .2 * tile.focus()));
        for (int[] edge : EDGES) {
            Vec3 a = corner(box, edge[0]), b = corner(box, edge[1]);
            for (int i = 0; i < 4; i++) {
                double from = i / 4.0, to = (i + 1) / 4.0;
                int color = ((int) ((155 + 80 * tile.focus()) * tile.fade()) << 24)
                        | chroma(phase, (from + to) / 2 * .18 + (edge[0] + edge[1]) / 80.0, hsb);
                var line = Gizmos.line(a.lerp(b, from), a.lerp(b, to), color, lineWidth);
                if (throughWalls) line.setAlwaysOnTop();
            }
        }
    }

    /** Bit 0=x max, bit 1=y max, bit 2=z max. */
    private static final int[][] EDGES = {
            {0, 1}, {2, 3}, {4, 5}, {6, 7},
            {0, 2}, {1, 3}, {4, 6}, {5, 7},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}};

    private static Vec3 corner(AABB b, int bit) {
        return new Vec3((bit & 1) == 0 ? b.minX : b.maxX,
                (bit & 2) == 0 ? b.minY : b.maxY,
                (bit & 4) == 0 ? b.minZ : b.maxZ);
    }

    private static int chroma(double phase, double along, float[] hsb) {
        float hue;
        if (hsb == null) {
            hue = (float) ((phase + along * 0.75) % 1.0);
        } else {
            hue = (float) ((hsb[0] + Math.sin((phase + along) * Math.PI * 2) * HUE_SWEEP_DEGREES / 720.0 + 1) % 1.0);
        }
        float saturation = hsb == null ? .85f : Math.max(.8f, hsb[1]);
        return Mth.hsvToRgb(hue, saturation, 1f) & 0x00FFFFFF;
    }

    private static void tracer(Minecraft mc, BlockPos p, double fade, double phase, float[] hsb) {
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.position();
        Vec3 target = new Vec3(p.getX() + .5, p.getY() + 1.04, p.getZ() + .5);
        // Crypt boss tiles are often underfoot; the old 2.5-block near cull hid
        // the tracer on those spawns even though the burst and tile capture worked.
        if (eye.distanceToSqr(target) > 64 * 64) return;
        var forward = camera.forwardVector();
        Vec3 from = eye.add(forward.x() * .8, forward.y() * .8 - .45, forward.z() * .8);
        int rgb = chroma(phase, 0, hsb);
        int alpha = (int) (190 * fade);
        Gizmos.line(from, target, ((alpha / 5) << 24) | rgb, 6f).setAlwaysOnTop();
        Gizmos.line(from, target, (alpha << 24) | rgb, 1.8f).setAlwaysOnTop();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("revenant-spawn-tiles.txt");
    }

    /**
     * The bundled tiles first, then your own on top. Before they were bundled, a tile
     * only showed once you had seen a boss spawn on it twice yourself, so for anyone
     * but the player who mapped the Crypts the feature drew nothing at all.
     */
    private static void load() {
        try (InputStream in = RevenantSpawnTiles.class.getResourceAsStream("/endsight-revenant-tiles.txt")) {
            if (in != null) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    Tile entry = parse(line);
                    if (entry != null) tiles.merge(entry.pos(), entry.sightings(), Math::max);
                }
            }
        } catch (IOException e) {
            System.err.println("[Endsight] could not read the bundled Revenant spawn tiles: " + e);
        }
        Path path = file();
        if (!Files.exists(path)) return;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                Tile entry = parse(line);
                if (entry != null) tiles.merge(entry.pos(), entry.sightings(), Math::max);
            }
        } catch (IOException e) {
            System.err.println("[Endsight] could not load Revenant spawn tiles: " + e);
        }
    }

    record Tile(BlockPos pos, int sightings) { }

    static Tile parse(String line) {
        if (line.isBlank() || line.startsWith("#")) return null;
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 4) return null;
        try {
            int sightings = Integer.parseInt(parts[3]);
            if (sightings < 1 || sightings > 1_000_000) return null;
            return new Tile(new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])), sightings);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void save() {
        Path path = file();
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        List<String> out = new ArrayList<>();
        out.add("# Observed Atoned Horror ground tiles: x y z sightings");
        tiles.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<BlockPos, Integer> e) -> e.getKey().getX())
                        .thenComparingInt(e -> e.getKey().getY())
                        .thenComparingInt(e -> e.getKey().getZ()))
                .forEach(e -> out.add(e.getKey().getX() + " " + e.getKey().getY() + " "
                        + e.getKey().getZ() + " " + e.getValue()));
        try {
            Files.createDirectories(path.getParent());
            Files.write(temp, out, StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[Endsight] could not save Revenant spawn tiles: " + e);
        }
    }
}
