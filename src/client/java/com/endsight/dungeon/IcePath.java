package com.endsight.dungeon;

import com.endsight.dragons.DragonTimer;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Ice Path": hit the silverfish so it slides into the chest at the far end.
 *
 * The room, from a recording of a real one: a flat sheet of packed ice, a scatter of
 * polished andesite standing on it that the fish stops against, and a single chest at
 * the sheet's far edge, halfway along - the "opening" the hint means.
 *
 *   packed_ice   297 cells at one level, x -5..+15  z -10..+6
 *   chest        +15 floor+1 -2          <- the goal, far end, mid
 *   silverfish   -5  floor+1 +6
 *
 * The search lives in {@link IcePathLogic}; this half only finds those three things and
 * draws every slide. Finding the chest rather than assuming a corner matters: the
 * sheet is laid out differently in each room, and the two earlier recordings were
 * clipped at the radius, so a guessed "back middle" would have aimed at open ice.
 */
public final class IcePath {
    private static final String ID = "dungeon.icePath";
    private static final String ENTRY = "PUZZLE! Ice Path";

    private static boolean enabled = true, throughWalls = true, announce = true, active;
    private static ClientLevel world;
    private static List<Vec3> points = List.of();
    private static BlockPos goalBlock;
    private static IcePathLogic.Sheet sheet;
    private static BlockPos scannedAt, plannedAt, lastFishAt;
    private static int scanTicks;
    private static int lastHits = -1;

    private IcePath() { }

    public static Module module() {
        return new Module(ID, "Ice Path", "Draws the full silverfish route, highlighting the next slide.",
                "Dungeons", () -> enabled, value -> { enabled = value; if (!value) reset(); }, List.of(
                new Setting.Toggle("Through walls", "Keep the line visible through blocks.",
                        () -> throughWalls, value -> throughWalls = value),
                new Setting.Toggle("Say the hits", "Name the direction and how many hits are left in chat.",
                        () -> announce, value -> announce = value)));
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !enabled) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || !DungeonContext.active()) return;
            String line = plain(message.getString());
            if (DragonTimer.isPlayerChat(line)) return;
            if (entry(line)) {
                reset();
                active = true;
                world = mc.level;
            } else if (ends(line)) {
                reset();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(IcePath::tick);
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> draw());
    }

    static String plain(String raw) {
        return raw == null ? "" : raw.replaceAll("§.", "").trim();
    }

    static boolean entry(String line) {
        String text = plain(line);
        return text.equals(ENTRY) || text.startsWith(ENTRY + " -") || text.startsWith(ENTRY + ":");
    }

    static boolean ends(String line) {
        String text = plain(line);
        return text.startsWith("PUZZLE SOLVED! Ice Path") || text.startsWith("PUZZLE FAIL! Ice Path")
                || text.startsWith("PUZZLE! ") && !entry(text);
    }

    private static boolean visible(Minecraft mc) {
        return enabled && active && mc.player != null && mc.level == world && DungeonContext.active();
    }

    private static void reset() {
        active = false; world = null; points = List.of(); goalBlock = null; lastHits = -1;
        sheet = null; scannedAt = null; plannedAt = null; lastFishAt = null; scanTicks = 0;
    }

    private static void tick(Minecraft mc) {
        if (!visible(mc)) { if (active) reset(); return; }
        Entity fish = nearestSilverfish(mc);
        if (fish == null) {
            points = List.of(); plannedAt = null; lastFishAt = null; scanTicks = 0;
            goalBlock = null; sheet = null; scannedAt = null;
            return;
        }

        BlockPos at = fish.blockPosition();
        IcePathLogic.Cell start = new IcePathLogic.Cell(at.getX(), at.getZ());
        boolean moved = !at.equals(lastFishAt);
        lastFishAt = at;
        boolean missingGoal = goalBlock != null && (!mc.level.hasChunkAt(goalBlock)
                || !isChest(mc.level, goalBlock));
        // The room is static while the fish moves. Re-reading 2,401 floor blocks and
        // rerunning BFS every tick was doing the same work between every hit. Refresh
        // the sheet twice a second for late chunks/obstacles; a new floor or a fish
        // outside the cached sheet is read immediately, and every fish cell replans.
        if (--scanTicks <= 0 || scannedAt == null || at.getY() != scannedAt.getY()
                || missingGoal || moved && (sheet == null || !sheet.ice().contains(start))) {
            scanTicks = 10;
            scannedAt = at;
            IcePathLogic.Sheet previous = sheet;
            sheet = readSheet(mc, at);
            if (!java.util.Objects.equals(previous, sheet)) plannedAt = null;
        }
        if (sheet == null) { points = List.of(); plannedAt = null; return; }
        if (at.equals(plannedAt)) return;
        plannedAt = at;
        int floor = at.getY() - 1;
        List<IcePathLogic.Direction> route = IcePathLogic.solve(sheet, start);
        if (route.isEmpty()) { points = List.of(); say(mc, -1, null); return; }
        IcePathLogic.Direction first = route.get(0);
        List<Vec3> planned = new ArrayList<>();
        IcePathLogic.Cell cursor = start;
        planned.add(new Vec3(cursor.x() + .5, floor + 1.1, cursor.z() + .5));
        for (IcePathLogic.Direction direction : route) {
            cursor = IcePathLogic.slide(sheet, cursor, direction);
            planned.add(new Vec3(cursor.x() + .5, floor + 1.1, cursor.z() + .5));
        }
        points = List.copyOf(planned);
        say(mc, route.size(), first);
    }

    private static boolean isChest(ClientLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST);
    }

    private static IcePathLogic.Sheet readSheet(Minecraft mc, BlockPos at) {
        // The sheet is the ice the fish is standing on, so the floor comes from the fish
        // rather than from a scan that might find a different level's decoration.
        int floor = at.getY() - 1;
        Set<IcePathLogic.Cell> ice = new HashSet<>();
        Set<IcePathLogic.Cell> blocked = new HashSet<>();
        BlockPos chest = null;
        BlockPos centre = new BlockPos(at.getX(), floor, at.getZ());
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-24, 0, -24), centre.offset(24, 0, 24))) {
            if (!mc.level.hasChunkAt(pos)) continue;
            if (!mc.level.getBlockState(pos).is(Blocks.PACKED_ICE)) continue;
            IcePathLogic.Cell cell = new IcePathLogic.Cell(pos.getX(), pos.getZ());
            ice.add(cell);
            BlockPos above = pos.above();
            var state = mc.level.getBlockState(above);
            if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)) {
                chest = above.immutable();
            } else if (!state.isAir() && !state.getCollisionShape(mc.level, above).isEmpty()) {
                blocked.add(cell);
            }
        }
        if (chest == null || ice.isEmpty()) { goalBlock = null; return null; }

        // The recorded chest approach has stone floor between patches of ice. Include
        // its local supported cells; scanning stone throughout the room would let the
        // search leave the puzzle and route through the surrounding walkways.
        for (BlockPos pos : BlockPos.betweenClosed(chest.offset(-3, -1, -3), chest.offset(3, -1, 3))) {
            if (!mc.level.hasChunkAt(pos)) continue;
            if (mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty()) continue;
            IcePathLogic.Cell cell = new IcePathLogic.Cell(pos.getX(), pos.getZ());
            ice.add(cell);
            BlockPos above = pos.above();
            if (!above.equals(chest) && !mc.level.getBlockState(above).getCollisionShape(mc.level, above).isEmpty())
                blocked.add(cell);
        }

        goalBlock = chest;
        IcePathLogic.Cell goal = new IcePathLogic.Cell(chest.getX(), chest.getZ());
        blocked.remove(goal);                       // the goal catches the fish, it does not stop it
        return new IcePathLogic.Sheet(ice, blocked, goal);
    }

    /** One line per change of plan, not per tick. */
    private static void say(Minecraft mc, int hits, IcePathLogic.Direction direction) {
        if (!announce || mc.player == null || hits == lastHits) return;
        lastHits = hits;
        if (hits < 0) {
            mc.player.sendSystemMessage(Component.literal("§bIce Path §7- §eno route from where it is"));
            return;
        }
        mc.player.sendSystemMessage(Component.literal("§bIce Path §7- hit it §f" + direction
                + " §7(" + hits + (hits == 1 ? " hit" : " hits") + " left)"));
    }

    private static Entity nearestSilverfish(Minecraft mc) {
        Entity best = null;
        double nearest = 40 * 40;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.getType() != EntityType.SILVERFISH) continue;
            double distance = entity.distanceToSqr(mc.player);
            if (distance < nearest) { nearest = distance; best = entity; }
        }
        return best;
    }

    private static void draw() {
        Minecraft mc = Minecraft.getInstance();
        if (!visible(mc)) return;
        if (goalBlock != null) {
            var outline = Gizmos.cuboid(new AABB(goalBlock).inflate(.02),
                    GizmoStyle.strokeAndFill(0xFF55FFFF, 2f, 0x3055FFFF));
            if (throughWalls) outline.setAlwaysOnTop();
        }
        for (int i = 1; i < points.size(); i++) {
            Vec3 from = points.get(i - 1), to = points.get(i);
            int colour = i == 1 ? 0xFF55FF55 : 0xFF55FFFF;
            var wide = Gizmos.line(from, to, (colour & 0x00FFFFFF) | 0x40000000, 6f);
            var thin = Gizmos.line(from, to, colour, 2f);
            var lands = Gizmos.cuboid(new AABB(to.x - .12, to.y - .08, to.z - .12,
                    to.x + .12, to.y + .08, to.z + .12), GizmoStyle.stroke(colour, 2f));
            if (throughWalls) { wide.setAlwaysOnTop(); thin.setAlwaysOnTop(); lands.setAlwaysOnTop(); }
        }
    }
}
