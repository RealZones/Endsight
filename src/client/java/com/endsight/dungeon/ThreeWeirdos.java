package com.endsight.dungeon;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only hints: solving the observed clues never opens a chest for the player. */
public final class ThreeWeirdos {
    private static final String ID = "dungeon.threeWeirdos";
    private static final ThreeWeirdosLogic clues = new ThreeWeirdosLogic();
    private static boolean enabled = true, announce = true, highlight = true, throughWalls = true;
    private static String announced;   // the answer already said, so it is said once per room
    private static ClientLevel world;
    private static Vec3 entryOrigin, npcCenter;
    private static BlockPos correctChest;
    private static Map<String, Vec3> chestPositions = Map.of();
    private static Map<String, BlockPos> matchedChests = Map.of();
    private static int ticks, chestScanTick;

    private ThreeWeirdos() { }

    public static Module module() {
        return new Module(ID, "Three Weirdos", "Solve the room's three clues and mark the correct chest.", "Dungeons",
                () -> enabled, value -> { enabled = value; if (!value) reset(); },
                List.of(new Setting.Toggle("Say the answer", "Name the chest in chat once all three NPCs have spoken.", () -> announce, value -> announce = value),
                        new Setting.Toggle("Highlight chest", "Only marks a nearby chest matched to the correct named NPC.", () -> highlight, value -> highlight = value),
                        new Setting.Toggle("Through walls", "Keep the chest outline visible through nearby blocks.", () -> throughWalls, value -> throughWalls = value)));
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !enabled) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || !DungeonContext.active()) return;
            if (world != null && world != mc.level) reset();
            String line = ThreeWeirdosLogic.plain(message.getString());
            if (ThreeWeirdosLogic.entry(line)) {
                reset();
                world = mc.level;
                entryOrigin = mc.player.position();
            }
            if (clues.accept(line)) {
                correctChest = null;
                chestPositions = Map.of(); matchedChests = Map.of();
                if (!clues.active()) reset();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(ThreeWeirdos::tick);
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> drawChest());
    }

    private static void reset() {
        announced = null;
        clues.clear(); world = null; entryOrigin = null; npcCenter = null; correctChest = null;
        chestPositions = Map.of(); matchedChests = Map.of(); ticks = 0; chestScanTick = 0;
    }

    private static boolean visible(Minecraft mc) {
        return enabled && clues.active() && mc.level == world && mc.player != null && DungeonContext.active();
    }

    private static void tick(Minecraft mc) {
        if (!visible(mc)) { if (clues.active()) reset(); return; }
        Vec3 origin = npcCenter == null ? entryOrigin : npcCenter;
        double distance = npcCenter == null ? 40 : 24;
        if (origin == null || mc.player.position().distanceToSqr(origin) > distance * distance) { reset(); return; }
        if (++ticks % 10 != 0) return;
        Map<String, Vec3> positions = npcPositions(mc);
        if (positions.size() != 3) { correctChest = null; return; }
        npcCenter = positions.values().stream().reduce(Vec3.ZERO, Vec3::add).scale(1.0 / 3);
        String answer = clues.solution();
        if (answer == null) { correctChest = null; return; }
        // Once per room: this runs every tenth tick for as long as you stand there.
        if (announce && !answer.equals(announced) && mc.player != null) {
            announced = answer;
            mc.player.sendSystemMessage(Component.literal("§5Three Weirdos §7- open §a" + answer + "§7's chest"));
        }
        if (!highlight) { correctChest = null; return; }
        // The named NPCs and their chests do not move between clues. Keep checking
        // the three chosen blocks, but avoid three overlapping 9x9x9 room scans on
        // every sample. A moved label or removed/unloaded chest invalidates at once;
        // periodic discovery still notices late chunks or a newly ambiguous match.
        boolean sameChests = positions.equals(chestPositions) && matchedChests.size() == 3
                && matchedChests.values().stream().allMatch(chest -> isChest(mc.level, chest));
        if (sameChests && ticks - chestScanTick < 40) {
            correctChest = matchedChests.get(answer.toLowerCase(Locale.ROOT));
            return;
        }
        chestScanTick = ticks;
        chestPositions = Map.of(); matchedChests = Map.of();
        Map<String, BlockPos> matched = new HashMap<>();
        for (Map.Entry<String, Vec3> npc : positions.entrySet()) {
            BlockPos chest = nearestChest(npc.getValue(), chestsNear(mc.level, npc.getValue()));
            if (chest == null) { correctChest = null; return; }
            matched.put(npc.getKey(), chest);
        }
        // If two names resolve to one chest, the labels are not enough to identify
        // this room safely. Keep the textual answer, but never mark a guessed block.
        if (new HashSet<>(matched.values()).size() != 3) { correctChest = null; return; }
        chestPositions = Map.copyOf(positions);
        matchedChests = Map.copyOf(matched);
        correctChest = matched.get(answer.toLowerCase(Locale.ROOT));
    }

    private static boolean isChest(ClientLevel level, BlockPos chest) {
        if (!level.hasChunkAt(chest)) return false;
        var state = level.getBlockState(chest);
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST);
    }

    private static Map<String, Vec3> npcPositions(Minecraft mc) {
        Map<String, Vec3> found = new HashMap<>();
        List<String> names = clues.names().stream().map(name -> name.toLowerCase(Locale.ROOT)).toList();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Player || entity.distanceToSqr(mc.player) > 30 * 30) continue;
            var label = entity instanceof Display.TextDisplay text ? text.getText() : entity.getCustomName();
            if (label == null) continue;
            String name = ThreeWeirdosLogic.plain(label.getString()).toLowerCase(Locale.ROOT);
            if (name.startsWith("[npc] ")) name = name.substring(6);
            if (!names.contains(name)) continue;
            Vec3 old = found.get(name);
            if (old == null || entity.position().distanceToSqr(mc.player.position()) < old.distanceToSqr(mc.player.position())) found.put(name, entity.position());
        }
        return found;
    }

    private static List<BlockPos> chestsNear(ClientLevel level, Vec3 position) {
        List<BlockPos> chests = new ArrayList<>();
        BlockPos center = BlockPos.containing(position);
        for (BlockPos block : BlockPos.betweenClosed(center.offset(-4, -4, -4), center.offset(4, 4, 4))) {
            if (!level.hasChunkAt(block)) continue;
            var state = level.getBlockState(block);
            if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)) chests.add(block.immutable());
        }
        return chests;
    }

    static BlockPos nearestChest(Vec3 npc, List<BlockPos> chests) {
        BlockPos result = null;
        double best = 4 * 4;
        boolean tied = false;
        for (BlockPos chest : chests) {
            double distance = Vec3.atCenterOf(chest).distanceToSqr(npc);
            if (distance < best - 1e-6) { best = distance; result = chest; tied = false; }
            else if (result != null && Math.abs(distance - best) < 1e-6) tied = true;
        }
        return tied ? null : result;
    }

    private static void drawChest() {
        Minecraft mc = Minecraft.getInstance();
        if (!highlight || correctChest == null || !visible(mc) || !mc.level.hasChunkAt(correctChest)) return;
        var state = mc.level.getBlockState(correctChest);
        if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST)) return;
        var shape = Gizmos.cuboid(new AABB(correctChest).inflate(.02), GizmoStyle.strokeAndFill(0xFF55FF55, 2.5f, 0x4055FF55));
        if (throughWalls) shape.setAlwaysOnTop();
    }
}
