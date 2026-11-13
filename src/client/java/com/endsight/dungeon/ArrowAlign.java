package com.endsight.dungeon;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The arrow device: on each frame, how many clicks it still needs, and a click on a frame
 * that already has all it needs does not go out. Every click is the player's own.
 *
 * The number counts your clicks the moment you make them, not when the server's turn comes
 * back a ping later; clicking faster than your ping, the number still drops with each click,
 * and the click that would spin an arrow past its target - a whole extra lap of seven - is
 * stopped. On Terminal Solvers' page, beside Simon Says.
 *
 * Only in a dungeon, and only on a wall of arrow frames that fits one of the nine routes
 * exactly: anywhere else nothing is drawn and no click is touched.
 */
public final class ArrowAlign {
    private ArrowAlign() { }

    private static boolean enabled = true;
    private static ClientLevel world;
    private static ArrowAlignLogic.Board board;
    private static int boardX, ticks;
    private static final Map<Integer, ArrowAlignLogic.Count> counts = new HashMap<>();
    private static final List<ItemFrame> frames = new ArrayList<>();

    static boolean enabled() { return enabled; }
    static void setEnabled(boolean value) { enabled = value; if (!value) reset(); }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(ArrowAlign::tick);
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> render());
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                level.isClientSide() && player == Minecraft.getInstance().player ? click(entity) : InteractionResult.PASS);
    }

    private static void reset() {
        world = null; board = null; counts.clear(); frames.clear(); ticks = 0;
    }

    private static boolean active(Minecraft mc) {
        return enabled && TerminalSolver.enabledForSimon() && mc.level != null && mc.player != null && DungeonContext.active();
    }

    /** Every tick on a board, to keep the numbers live; twice a second while looking for one. */
    private static void tick(Minecraft mc) {
        if (!active(mc)) { if (board != null || world != null) reset(); return; }
        if (world != mc.level) { reset(); world = mc.level; }
        if (board == null && ++ticks % 10 != 0) return;
        List<ItemFrame> nearby = mc.level.getEntitiesOfClass(ItemFrame.class,
                mc.player.getBoundingBox().inflate(16), f -> f.getItem().is(Items.ARROW));
        ArrowAlignLogic.Board found = null;
        int foundX = 0;
        for (ItemFrame seed : nearby) {
            int plane = seed.blockPosition().getX();
            if (found != null && plane == foundX) continue;
            List<ArrowAlignLogic.Frame> shape = nearby.stream().filter(f -> f.blockPosition().getX() == plane)
                    .map(f -> new ArrowAlignLogic.Frame(f.blockPosition().getY(), f.blockPosition().getZ(), f.getRotation())).toList();
            ArrowAlignLogic.Board candidate = ArrowAlignLogic.locate(shape);
            if (candidate == null) continue;
            if (found != null) { board = null; frames.clear(); return; }   // two boards: say nothing
            found = candidate;
            foundX = plane;
        }
        board = found;
        boardX = foundX;
        frames.clear();
        if (found == null) { counts.clear(); return; }
        long now = System.nanoTime();
        for (ItemFrame frame : nearby) {
            if (frame.blockPosition().getX() != foundX || target(frame) < 0) continue;
            frames.add(frame);
            count(frame).server(frame.getRotation(), now);
        }
        counts.keySet().removeIf(id -> frames.stream().noneMatch(f -> f.getId() == id));
    }

    private static int target(ItemFrame frame) {
        return board == null ? -1 : board.targetAt(frame.blockPosition().getY(), frame.blockPosition().getZ());
    }

    private static ArrowAlignLogic.Count count(ItemFrame frame) {
        return counts.computeIfAbsent(frame.getId(), id -> new ArrowAlignLogic.Count(frame.getRotation()));
    }

    /** A right click on a board frame: counted, or stopped when the frame needs no more. */
    private static InteractionResult click(Entity entity) {
        if (board == null || !(entity instanceof ItemFrame frame) || frame.blockPosition().getX() != boardX
                || target(frame) < 0 || !frames.contains(frame)) return InteractionResult.PASS;
        long now = System.nanoTime();
        ArrowAlignLogic.Count count = count(frame);
        count.server(frame.getRotation(), now);
        if (count.remaining(target(frame)) == 0) return InteractionResult.FAIL;
        count.sent(now);
        return InteractionResult.PASS;
    }

    /** Green when done; then green, yellow, orange, red as the clicks left grow. */
    private static int colour(int left) {
        return left == 0 ? 0xFF55FF55 : left <= 2 ? 0xFFB5FF55 : left <= 4 ? 0xFFFFD452 : left <= 5 ? 0xFFFF9A3C : 0xFFFF5555;
    }

    private static void render() {
        Minecraft mc = Minecraft.getInstance();
        if (board == null || mc.level != world || mc.options.hideGui) return;
        for (ItemFrame frame : frames) {
            ArrowAlignLogic.Count count = counts.get(frame.getId());
            if (count == null) continue;
            int left = count.remaining(target(frame));
            // A little off the wall, toward whoever is looking, so the arrow does not hide it.
            Vec3 at = frame.position().add(frame.getDirection().getStepX() * .12, .02, frame.getDirection().getStepZ() * .12);
            Gizmos.billboardText(String.valueOf(left), at, TextGizmo.Style.forColorAndCentered(colour(left)).withScale(.55f));
        }
    }
}
