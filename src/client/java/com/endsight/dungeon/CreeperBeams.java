package com.endsight.dungeon;

import com.endsight.dungeon.CreeperBeamsLogic.Pair;
import com.endsight.dungeon.CreeperBeamsLogic.Point;
import com.endsight.ui.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * "Creeper Beams": shoot each pair of sea lanterns whose beam passes through the creeper.
 * Which lanterns pair up is {@link CreeperBeamsLogic}; this half finds the room and draws a
 * line per unlinked pair, each pair in its own colour.
 *
 * Found from its pieces, not chat: a creeper entity with lanterns mirrored through it. Lit
 * lanterns (prismarine) are read as well as unlit ones, so walking in halfway through still
 * pairs every lantern, and a pair is dropped once both ends are lit.
 */
public final class CreeperBeams {
    private static final String ID = "dungeon.creeperBeams";
    private static final int[] COLOURS = {0xFF55FF55, 0xFFFFFF55, 0xFFFF55FF, 0xFF55FFFF, 0xFFFFAA00, 0xFFFF5555};

    private static boolean enabled = true;
    private static ClientLevel world;
    private static int ticks;
    private static List<BlockPos[]> pairs = List.of();
    private static List<BlockPos> pairedFrom = List.of();      // the lanterns `pairs` was worked out from
    private static int scannedFor = -1, sinceScan;              // the creeper the room was last searched for

    private CreeperBeams() { }

    public static Module module() {
        return new Module(ID, "Creeper Beams", "Links each pair of sea lanterns whose beam crosses the creeper.", "Dungeons",
                () -> enabled, value -> { enabled = value; if (!value) reset(); }, List.of());
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(CreeperBeams::tick);
        // Drawn from the client tick, like Key Alert: lines added in the frame's gizmo
        // event never showed in the test client, and ones added in a tick are kept and
        // drawn every frame of it. The lines only change per tick anyway.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> draw());
    }

    private static void reset() {
        world = null; ticks = 0; pairs = List.of(); pairedFrom = List.of(); scannedFor = -1; sinceScan = 0;
    }

    private static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null || !DungeonContext.active()) {
            if (world != null) reset();
            return;
        }
        if (world != mc.level) { reset(); world = mc.level; }
        // The lanterns never move; only which of them are lit changes, and draw() reads
        // that live. Once a second is enough to notice the room as you walk in.
        if (++ticks % 20 != 0) return;
        pairs = find(mc);
    }

    private static List<BlockPos[]> find(Minecraft mc) {
        // The creeper, when one is near: the beam's target is its middle.
        Entity creeper = null;
        double nearest = 24 * 24;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.getType() != EntityType.CREEPER) continue;
            double distance = entity.distanceToSqr(mc.player);
            if (distance < nearest) { nearest = distance; creeper = entity; }
        }
        // No creeper, no puzzle. There used to be a fallback that took the point most
        // lantern pairs mirror through as the creeper, and on 2026-11-09 it drew beams in
        // ordinary F1 and F2 rooms (a screenshots of the bars room and the fairy room):
        // rooms are built symmetric, so the sea lanterns that light them pair up through
        // the room's middle just like the puzzle's. The real room has a creeper entity,
        // charged, floating in the middle (a clip of the same night).
        if (creeper == null) { scannedFor = -1; return List.of(); }
        // The lanterns are part of the room and never move, and draw() reads which are lit.
        // So the room is searched once for this creeper and again every ten seconds for a
        // late chunk, not every second: the search reads 27,225 blocks, and once a second
        // that was the heaviest thing the mod did in a dungeon (performance audit, 2026-11-09).
        if (creeper.getId() == scannedFor && ++sinceScan < 10) return pairs;
        scannedFor = creeper.getId();
        sinceScan = 0;
        Vec3 centre = creeper.getBoundingBox().getCenter();
        // The real room's lanterns sit 5-11 blocks from the creeper.
        BlockPos c = BlockPos.containing(centre);
        BlockPos from = c.offset(-16, -12, -16), to = c.offset(16, 12, 16);
        List<BlockPos> blocks = new ArrayList<>();
        List<Point> lanterns = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            var state = mc.level.getBlockState(pos);
            if (!state.is(Blocks.SEA_LANTERN) && !state.is(Blocks.PRISMARINE)) continue;
            if (!exposed(mc, pos)) continue;
            blocks.add(pos.immutable());
            lanterns.add(new Point(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5));
        }
        if (lanterns.size() < 4 || lanterns.size() > 200) return List.of();
        // The same lanterns keep the same pairs. Re-pairing every second let the creeper's
        // drift reshuffle near ties, and the lines jumped between lanterns (a clip of
        // 2026-11-09, 17:22). Only a changed set of lanterns pairs again.
        if (blocks.equals(pairedFrom) && !pairs.isEmpty()) return pairs;
        pairedFrom = blocks;
        List<Pair> found = CreeperBeamsLogic.pairs(lanterns, new Point(centre.x, centre.y, centre.z));
        // Two lined-up lanterns can happen by chance in any room; the puzzle has several.
        if (found.size() < 2) return List.of();
        List<BlockPos[]> out = new ArrayList<>();
        for (Pair pair : found) out.add(new BlockPos[]{blocks.get(pair.a()), blocks.get(pair.b())});
        return List.copyOf(out);
    }

    /**
     * Whether any face of the block is open, i.e. it could be seen and shot. The same clip
     * showed a lantern sealed in under the pillar being paired; a block with every face
     * against something solid is part of the build, not the puzzle.
     */
    private static boolean exposed(Minecraft mc, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (!mc.level.getBlockState(pos.relative(direction)).canOcclude()) return true;
        }
        return false;
    }

    private static void draw() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.level == null || mc.level != world || pairs.isEmpty() || !DungeonContext.active()) return;
        for (int i = 0; i < pairs.size(); i++) {
            BlockPos a = pairs.get(i)[0], b = pairs.get(i)[1];
            boolean litA = mc.level.getBlockState(a).is(Blocks.PRISMARINE), litB = mc.level.getBlockState(b).is(Blocks.PRISMARINE);
            if (litA && litB) continue;                                    // linked
            int colour = COLOURS[i % COLOURS.length];
            var wide = Gizmos.line(Vec3.atCenterOf(a), Vec3.atCenterOf(b), (colour & 0x00FFFFFF) | 0x40000000, 6f);
            var thin = Gizmos.line(Vec3.atCenterOf(a), Vec3.atCenterOf(b), colour, 2f);
            wide.setAlwaysOnTop(); thin.setAlwaysOnTop();                  // the lanterns are set in the walls
            for (BlockPos end : new BlockPos[]{a, b}) {
                boolean lit = end == a ? litA : litB;
                // One end lit means its partner is due before the hit wears off: fill it.
                boolean next = !lit && (litA || litB);
                var box = Gizmos.cuboid(new AABB(end).inflate(.02), next
                        ? GizmoStyle.strokeAndFill(colour, 3f, (colour & 0x00FFFFFF) | 0x70000000)
                        : GizmoStyle.stroke(lit ? (colour & 0x00FFFFFF) | 0x60000000 : colour, 2f));
                box.setAlwaysOnTop();
            }
        }
    }
}
