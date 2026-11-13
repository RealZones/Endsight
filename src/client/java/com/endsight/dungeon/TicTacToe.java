package com.endsight.dungeon;

import com.endsight.dragons.DragonTimer;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Reads the visible board and marks the best red move; never clicks for the player. */
public final class TicTacToe {
    private static final String ID = "dungeon.ticTacToe";
    // The name identifies the puzzle; the sentence after it is flavour text and is not
    // ours to depend on. Matching the whole line meant one reworded hint silently
    // disabled the solver - no HUD, no error, nothing to see. endsRoom() below already
    // matched on the prefix, so this was inconsistent as well as fragile.
    private static final String ENTRY = "PUZZLE! Tic Tac Toe";
    private static final TicTacToeBoard.Stabilizer SAMPLES = new TicTacToeBoard.Stabilizer();
    private static boolean enabled = true, highlight = true, active;
    private static ClientLevel world;
    private static Vec3 entryOrigin;
    private static TicTacToeBoard.Grid grid;
    private static TicTacToeBoard.Snapshot solved;
    private static int solvedMove = -1;
    private static BlockPos recommended;
    private static String status = "Finding board";
    private static int ticks, armTicks;
    private static final List<ItemFrame> frames = new ArrayList<>();
    private static String described;
    private static int unreadable;

    private TicTacToe() { }

    public static Module module() {
        return new Module(ID, "Tic Tac Toe", "Marks your best move after the machine plays.", "Dungeons",
                () -> enabled, value -> { enabled = value; if (!value) reset(); }, List.of(
                new Setting.Toggle("Highlight button", "Outline the recommended button without clicking it.",
                        () -> highlight, value -> highlight = value)));
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
                entryOrigin = mc.player.position();
            } else if (endsRoom(line)) reset();
        });
        ClientTickEvents.END_CLIENT_TICK.register(TicTacToe::tick);
        // Drawn from the tick, like Ice Fill: the box only changes per tick, and lines
        // added in the frame's gizmo event never show in the test client.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> drawButton());
    }

    static String plain(String raw) {
        return Zealots.strip(raw).trim().replace('’', '\'').replace('–', '-');
    }

    static boolean entry(String line) {
        String text = plain(line);
        return text.equals(ENTRY) || text.startsWith(ENTRY + " -") || text.startsWith(ENTRY + ":");
    }

    static boolean endsRoom(String line) {
        String text = plain(line);
        return text.startsWith("PUZZLE! ") && !entry(text)
                || text.startsWith("PUZZLE FAIL! ") && text.contains("Tic Tac Toe")
                || text.startsWith("PUZZLE SOLVED! ") && text.contains("Tic Tac Toe");
    }

    private static void reset() {
        active = false; world = null; entryOrigin = null; grid = null; recommended = null;
        solved = null; solvedMove = -1;
        status = "Finding board"; ticks = 0; SAMPLES.clear();
    }

    private static boolean visible(Minecraft mc) {
        return enabled && active && mc.player != null && mc.level == world && DungeonContext.active();
    }

    /** Item frames holding something near you, for cell(); rebuilt before each read. */
    private static void indexFrames(Minecraft mc) {
        frames.clear();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof ItemFrame frame && !frame.getItem().isEmpty() && entity.distanceToSqr(mc.player) < 32 * 32)
                frames.add(frame);
        }
    }

    /**
     * The frame on a square's face: the nearest one within a block of the face's middle,
     * preferring one whose map has arrived. Matched by distance, not by the block the
     * frame is in: frames placed during a round were missed by the exact-block match
     * (2026-11-09 clips, after the first click), and an empty frame sharing a square no
     * longer hides the one with the mark.
     */
    private static ItemFrame frameOn(ClientLevel level, BlockPos behind, TicTacToeBoard.Plane plane) {
        Vec3 face = Vec3.atCenterOf(behind).add(plane.nx * .5, plane.ny * .5, plane.nz * .5);
        ItemFrame best = null;
        double nearest = 1.0;
        boolean bestHasMap = false;
        for (ItemFrame frame : frames) {
            double distance = frame.position().distanceToSqr(face);
            if (distance >= 1.0) continue;
            boolean hasMap = MapItem.getSavedData(frame.getItem(), level) != null;
            if (hasMap && !bestHasMap || hasMap == bestHasMap && distance < nearest) {
                best = frame; nearest = distance; bestHasMap = hasMap;
            }
        }
        return best;
    }

    /**
     * One log line describing each square, when a found board stops reading for a second.
     * The board has changed under this solver twice, both times found only from clips; this
     * says what the client actually holds at each square (block, button, frame, map) so the
     * next change is read off the log instead of guessed. Written once per distinct board.
     */
    private static void describe(ClientLevel level) {
        StringBuilder out = new StringBuilder("[Endsight] Tic Tac Toe board unreadable or impossible:");
        for (int i = 0; i < 9; i++) {
            BlockPos behind = block(grid.support(i)), front = behind.offset(grid.plane().nx, grid.plane().ny, grid.plane().nz);
            out.append(" | ").append(i).append(' ').append(level.getBlockState(behind).getBlock().getDescriptionId().replace("block.minecraft.", ""))
                    .append('/').append(level.getBlockState(front).getBlock().getDescriptionId().replace("block.minecraft.", ""));
            Vec3 face = Vec3.atCenterOf(behind).add(grid.plane().nx * .5, grid.plane().ny * .5, grid.plane().nz * .5);
            for (ItemFrame frame : frames) {
                if (frame.position().distanceToSqr(face) > 4) continue;
                MapItemSavedData map = MapItem.getSavedData(frame.getItem(), level);
                int middle = map == null ? -1 : map.colors[64 + 64 * 128] & 0xFF;
                out.append(String.format(" frame@%.2f,%.2f,%.2f %s %s map=%s mark=%d mid=%d/%06X", frame.getX(), frame.getY(), frame.getZ(),
                        frame.getDirection(), frame.getItem().getItem().getDescriptionId().replace("item.minecraft.", ""),
                        map == null ? "none" : "yes", map == null ? -9 : TicTacToeBoard.mark(map.colors, TicTacToe::ink),
                        middle, middle < 0 ? 0 : MapColor.getColorFromPackedId(middle) & 0xFFFFFF));
            }
            out.append(" -> ").append(cell(level, grid.support(i), grid.plane()));
        }
        String line = out.toString();
        if (!line.equals(described)) { described = line; System.out.println(line); }
    }

    private static void tick(Minecraft mc) {
        // The board arms the solver as well as the chat line: Dragsim's new dungeon
        // generation (seen 2026-11-09) stopped saying "PUZZLE! Tic Tac Toe" on the way in.
        // Once a second is enough to catch someone walking up to it; the search is the
        // same one that runs after the chat line, over the same 25x17x25 box.
        boolean arming = !active && enabled && mc.player != null && mc.level != null && DungeonContext.active()
                && ++armTicks % 20 == 0;
        if (arming || visible(mc)) indexFrames(mc);
        // The board search reads 10,625 blocks, and ran once a second in every room of every
        // dungeon (performance audit, 2026-11-09). A board always has a map on it - the
        // machine moves first - so no map in a frame nearby means no board to look for.
        if (arming && frames.stream().noneMatch(frame -> frame.getItem().has(DataComponents.MAP_ID)
                && frame.distanceToSqr(mc.player) < 16 * 16)) arming = false;
        if (arming) {
            TicTacToeBoard.Snapshot found = TicTacToeBoard.find(buttonsNear(mc), (position, plane) -> cell(mc.level, position, plane));
            if (found != null) {
                reset();
                active = true;
                world = mc.level;
                entryOrigin = mc.player.position();
                grid = found.grid();
            }
        }
        if (!visible(mc)) { if (active) reset(); return; }
        Vec3 origin = grid == null ? entryOrigin : Vec3.atCenterOf(block(grid.support(4)));
        int maxDistance = grid == null ? 40 : 24;
        if (origin == null || mc.player.position().distanceToSqr(origin) > maxDistance * maxDistance) { reset(); return; }
        TicTacToeBoard.Probe probe = (position, plane) -> cell(mc.level, position, plane);
        TicTacToeBoard.Snapshot current;
        if (grid != null) {
            current = TicTacToeBoard.read(grid, probe);
            if (current != null) unreadable = 0;
            else if (++unreadable == 20) describe(mc.level);
        } else if (++ticks % 5 == 0) {
            current = TicTacToeBoard.find(buttonsNear(mc), probe);
            if (current != null) grid = current.grid();
        } else current = null;
        recommended = null;
        SAMPLES.observe(current);
        TicTacToeBoard.Snapshot stable = SAMPLES.stable();
        if (stable == null) {
            status = grid == null ? "Finding board" : "Reading board";
            return;
        }
        int[] values = stable.values();
        // A stable board can remain here for minutes. Minimax only changes when a
        // tile changes; sampling and packet-transition suppression still run each tick.
        if (!stable.equals(solved)) {
            solved = stable;
            solvedMove = TicTacToeLogic.bestMove(values);
        }
        int move = solvedMove;
        if (move >= 0) {
            BlockPos button = block(stable.grid().button(move));
            if (cell(mc.level, stable.grid().support(move), stable.grid().plane()) == TicTacToeLogic.EMPTY) {
                recommended = button;
                status = "Your turn";
                return;
            }
        }
        long blue = stable.cells().stream().filter(value -> value == TicTacToeLogic.BLUE).count();
        long red = stable.cells().stream().filter(value -> value == TicTacToeLogic.RED).count();
        status = TicTacToeLogic.winner(values) != TicTacToeLogic.EMPTY || blue + red == 9
                ? "Round complete" : blue == red ? "Machine turn" : "Invalid board";
        // A board that reads but cannot be real (too many of one mark) is the other way the
        // hint goes missing; it says what it read, the same as an unreadable one.
        if (status.equals("Invalid board")) describe(mc.level);
    }

    private static List<TicTacToeBoard.Seed> buttonsNear(Minecraft mc) {
        List<TicTacToeBoard.Seed> seeds = new ArrayList<>();
        BlockPos center = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-12, -8, -12), center.offset(12, 8, 12))) {
            if (!mc.level.hasChunkAt(pos)) continue;
            TicTacToeBoard.Plane plane = plane(mc.level.getBlockState(pos));
            if (plane != null) seeds.add(new TicTacToeBoard.Seed(point(pos), plane));
        }
        return seeds;
    }

    /**
     * The clicker in front of a playable tile, and which way the board faces.
     *
     * Any button material rather than STONE_BUTTON alone - that part costs nothing and
     * survives a re-skin. What has broken this twice is the TOKENS, which cell() reads:
     * the board has changed under it each time.
     */
    private static TicTacToeBoard.Plane plane(BlockState state) {
        if (!(state.getBlock() instanceof ButtonBlock)) return null;
        if (!state.hasProperty(BlockStateProperties.ATTACH_FACE)
                || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return null;
        AttachFace face = state.getValue(BlockStateProperties.ATTACH_FACE);
        if (face == AttachFace.FLOOR) return TicTacToeBoard.Plane.FLOOR;
        if (face != AttachFace.WALL) return null;
        return switch (state.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
            case NORTH -> TicTacToeBoard.Plane.NORTH;
            case EAST -> TicTacToeBoard.Plane.EAST;
            case SOUTH -> TicTacToeBoard.Plane.SOUTH;
            case WEST -> TicTacToeBoard.Plane.WEST;
            default -> null;
        };
    }

    /**
     * One square. The board in Dragsim's new dungeons (a clip of 2026-11-09) is
     * Hypixel's: a wall of blocks with a stone button on each free square, and a taken
     * square loses its button and gets an item frame holding a map with a red X or O.
     * The old board read here - iron blocks, blue and red concrete - matched none of
     * that, so the solver sat on "Finding board" while he lost three rounds.
     *
     * A taken square is read from its frame alone, found by distance to the square's
     * face (frameOn). In the 2026-11-09 clips the hint showed for the first move and then
     * never again, with every mark flush on the wall when seen side-on. That cause is not
     * pinned down yet: a closer look in a clip that the marks played during a round stood
     * further out was perspective. describe() logs what each square holds when a found
     * board stops reading. A free square still needs its button, unpressed, and a lone
     * button elsewhere still cannot seed a board.
     */
    private static int cell(ClientLevel level, TicTacToeBoard.Point support, TicTacToeBoard.Plane plane) {
        BlockPos behind = block(support);
        BlockPos front = behind.offset(plane.nx, plane.ny, plane.nz);
        if (!level.hasChunkAt(behind) || !level.hasChunkAt(front)) return TicTacToeBoard.UNKNOWN;
        BlockState tile = level.getBlockState(behind), button = level.getBlockState(front);
        if (tile.isAir()) return TicTacToeBoard.UNKNOWN;
        ItemFrame frame = frameOn(level, behind, plane);
        if (frame != null) {
            MapItemSavedData map = MapItem.getSavedData(frame.getItem(), level);
            return map == null ? TicTacToeBoard.UNKNOWN : TicTacToeBoard.mark(map.colors, TicTacToe::ink);
        }
        if (plane(button) != plane) return TicTacToeBoard.UNKNOWN;
        if (button.hasProperty(BlockStateProperties.POWERED)
                && button.getValue(BlockStateProperties.POWERED)) return TicTacToeBoard.UNKNOWN;
        return TicTacToeLogic.EMPTY;
    }

    /** A map pixel the marks are drawn in: clearly red, in whatever shade the map uses. */
    private static boolean ink(int packed) {
        int argb = MapColor.getColorFromPackedId(packed);
        int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        return argb >>> 24 != 0 && r > 90 && r > g + 40 && r > b + 40;
    }

    private static TicTacToeBoard.Point point(BlockPos position) {
        return new TicTacToeBoard.Point(position.getX(), position.getY(), position.getZ());
    }

    private static BlockPos block(TicTacToeBoard.Point position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static void drawButton() {
        Minecraft mc = Minecraft.getInstance();
        if (!highlight || recommended == null || !visible(mc) || grid == null || !mc.level.hasChunkAt(recommended)) return;
        var stable = SAMPLES.stable();
        if (stable == null || !TicTacToeBoard.matches(stable,
                (position, plane) -> cell(mc.level, position, plane))) return;
        BlockState state = mc.level.getBlockState(recommended);
        if (plane(state) != grid.plane() || state.getValue(ButtonBlock.POWERED)) return;
        BlockPos support = recommended.offset(-grid.plane().nx, -grid.plane().ny, -grid.plane().nz);
        if (mc.level.getBlockState(support).isAir()) return;
        var shape = state.getShape(mc.level, recommended);
        if (shape.isEmpty()) return;
        Gizmos.cuboid(shape.bounds().move(recommended).inflate(.015),
                GizmoStyle.strokeAndFill(0xFF55FF55, 2.5f, 0x6055FF55));
    }
}
