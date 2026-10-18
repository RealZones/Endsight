package com.endsight.visual;

import com.endsight.hud.Area;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Highlights only recently confirmed, server-particle-visible End nodes. */
public final class ShinyNodes {
    private ShinyNodes() { }

    private static final long OBSERVATION_MS = 8_000;
    private static final long BURST_MS = 3_000;
    private static final long VISIBLE_MS = 5_000;
    private static final double RANGE_SQR = 24 * 24;
    private static final int REQUIRED_EACH = 5;
    private static final int MAX_TRACKED = 128;

    public static boolean enabled;
    private static int colour = 0xFFFF5A1F;
    private static double width = 3.5;
    private static final Map<BlockPos, Node> nodes = new HashMap<>();
    private static Object currentLevel;

    public static Module module() {
        return new Module("visual.shinyNodes", "Shiny End Nodes",
                "Clear, depth-tested outline after the End node's particles confirm it.",
                "Visual", () -> enabled, value -> {
                    enabled = value;
                    if (!value) nodes.clear();
                }, List.of(
                        new Setting.Color("Node colour", "Pick a solid colour or Chroma.",
                                () -> colour, value -> colour = value),
                        new Setting.Slider("Node width", "Thickness of the bright outline.",
                                2, 6, .5, () -> width, value -> width = value, "px")));
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != currentLevel || Area.where() != Area.Where.END || !enabled) {
                nodes.clear();
                currentLevel = client.level;
                return;
            }
            long now = System.currentTimeMillis();
            nodes.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > VISIBLE_MS
                    || !validBlock(client, entry.getKey(), entry.getValue().burst));
        });
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> render());
    }

    public static void particle(ClientboundLevelParticlesPacket packet) {
        if (!enabled || Area.where() != Area.Where.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.level != currentLevel) return;
        ParticleOptions particle = packet.getParticle();
        boolean portal = particle.getType() == ParticleTypes.PORTAL;
        if (!portal && particle.getType() != ParticleTypes.WITCH) return;
        double x = packet.getX(), y = packet.getY(), z = packet.getZ();
        if (mc.player.distanceToSqr(x, y, z) > RANGE_SQR) return;
        // On this server the two resource-pack node types are sandstone and black
        // concrete, with a repeated portal burst above the block. The older face
        // particle signature remains available for actual End Stone/Obsidian.
        Candidate burst = portal ? burstCandidate(x, y, z, packet.getCount(),
                packet.getXDist(), packet.getYDist(), packet.getZDist(), packet.getMaxSpeed()) : null;
        Candidate candidate = burst != null ? burst : candidate(x, y, z);
        if (candidate == null || !validBlock(mc, candidate.pos(), burst != null)) return;
        long now = System.currentTimeMillis();
        if (nodes.size() >= MAX_TRACKED && !nodes.containsKey(candidate.pos())) {
            nodes.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > VISIBLE_MS);
            if (nodes.size() >= MAX_TRACKED) return;
        }
        Node node = nodes.computeIfAbsent(candidate.pos(), ignored -> new Node());
        if (burst != null) node.observeBurst(now);
        else node.observe(candidate.face(), portal, now);
    }

    private static boolean validBlock(Minecraft mc, BlockPos pos, boolean burst) {
        if (mc.level == null) return false;
        var state = mc.level.getBlockState(pos);
        return burst ? state.is(Blocks.SANDSTONE) || state.is(Blocks.BLACK_CONCRETE)
                : state.is(Blocks.END_STONE) || state.is(Blocks.OBSIDIAN);
    }

    private static void render() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.level == null
                || mc.level != currentLevel || Area.where() != Area.Where.END) return;
        long now = System.currentTimeMillis();
        for (var entry : nodes.entrySet()) {
            if (now - entry.getValue().confirmed > VISIBLE_MS
                    || !validBlock(mc, entry.getKey(), entry.getValue().burst)) continue;
            BlockPos pos = entry.getKey();
            if (mc.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > RANGE_SQR) continue;
            // Gizmos are depth-tested by default. Never setAlwaysOnTop: that would turn
            // a visible-particle cue into a through-wall node finder.
            AABB box = new AABB(pos).inflate(.02);
            int live = Setting.Color.live(colour);
            int fill = (live & 0x00FFFFFF) | 0x66000000;
            // The dark wide stroke separates the chosen colour from both pale End
            // Stone and near-black Obsidian. Both remain depth-tested like the fill.
            Gizmos.cuboid(box, GizmoStyle.stroke(0xFF100B11, (float) width + 3f));
            if (colour == Setting.Color.CHROMA) {
                Gizmos.cuboid(box, GizmoStyle.fill(fill));
                Etherwarp.chromaEdges(box, (float) width, false);
            } else {
                Gizmos.cuboid(box, GizmoStyle.strokeAndFill(live, (float) width, fill));
            }
        }
    }

    private static void reset() {
        nodes.clear();
        currentLevel = null;
    }

    record Candidate(BlockPos pos, Direction face) { }

    /** The captured packet is centred 0.1 above the block, with eight spread particles. */
    static Candidate burstCandidate(double x, double y, double z, int count,
                                    double dx, double dy, double dz, double speed) {
        if (count != 8 || !near(fraction(x), .5) || !near(fraction(y), .1)
                || !near(fraction(z), .5) || !near(dx, .35) || !near(dy, .25)
                || !near(dz, .35) || !near(speed, 0)) return null;
        return new Candidate(BlockPos.containing(x, y - 1, z), Direction.UP);
    }

    /** Server particles mark the centre of an exposed face, 1/4 block outside it. */
    static Candidate candidate(double x, double y, double z) {
        double fx = fraction(x), fy = fraction(y), fz = fraction(z);
        // The face offset is fixed; the two sideways coordinates may vary. Requiring
        // their centre would silently miss real server particles on some faces.
        if (near(fy, .25)) return new Candidate(BlockPos.containing(x, y - 1, z), Direction.UP);
        if (near(fy, .75)) return new Candidate(BlockPos.containing(x, y + 1, z), Direction.DOWN);
        if (near(fx, .25)) return new Candidate(BlockPos.containing(x - 1, y, z), Direction.EAST);
        if (near(fx, .75)) return new Candidate(BlockPos.containing(x + 1, y, z), Direction.WEST);
        if (near(fz, .25)) return new Candidate(BlockPos.containing(x, y, z - 1), Direction.SOUTH);
        if (near(fz, .75)) return new Candidate(BlockPos.containing(x, y, z + 1), Direction.NORTH);
        return null;
    }

    private static double fraction(double coordinate) {
        return coordinate - Math.floor(coordinate);
    }

    private static boolean near(double value, double target) {
        return Math.abs(value - target) < .001;
    }

    private static final class Node {
        private final Map<Direction, Counts> faces = new EnumMap<>(Direction.class);
        private long lastSeen;
        private long confirmed;
        private long burstLast;
        private int burstCount;
        private boolean burst;

        private void observeBurst(long now) {
            if (now - burstLast > BURST_MS) burstCount = 0;
            burstLast = lastSeen = now;
            burst = true;
            if (++burstCount >= 2) confirmed = now;
        }

        private void observe(Direction face, boolean portal, long now) {
            Counts counts = faces.computeIfAbsent(face, ignored -> new Counts());
            if (now - counts.last > OBSERVATION_MS) {
                counts.portal = 0;
                counts.witch = 0;
            }
            counts.last = now;
            lastSeen = now;
            if (portal) counts.portal++;
            else counts.witch++;
            if (counts.portal >= REQUIRED_EACH && counts.witch >= REQUIRED_EACH) {
                confirmed = now;
                counts.portal = 0;
                counts.witch = 0;
            }
        }
    }

    private static final class Counts {
        private int portal;
        private int witch;
        private long last;
    }
}
