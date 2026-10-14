package com.endsight.dungeon;

import com.endsight.dragons.DragonTimer;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
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
    private static boolean enabled = true, highlight = true, throughWalls = true, active;
    private static ClientLevel world;
    private static Vec3 entryOrigin;
    private static TicTacToeBoard.Grid grid;
    private static TicTacToeBoard.Snapshot solved;
    private static int solvedMove = -1;
    private static BlockPos recommended;
    private static String status = "Finding board";
    private static int ticks;

    private TicTacToe() { }

    public static Module module() {
        return new Module(ID, "Tic Tac Toe", "Mark the best red move after the machine plays blue.", "Dungeons",
                () -> enabled, value -> { enabled = value; if (!value) reset(); }, List.of(
                new Setting.Toggle("Highlight button", "Outline the recommended button without clicking it.",
                        () -> highlight, value -> highlight = value),
                new Setting.Toggle("Through walls", "Keep the nearby button outline visible through blocks.",
                        () -> throughWalls, value -> throughWalls = value)));
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
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> drawButton());
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

    private static void tick(Minecraft mc) {
        if (!visible(mc)) { if (active) reset(); return; }
        Vec3 origin = grid == null ? entryOrigin : Vec3.atCenterOf(block(grid.support(4)));
        int maxDistance = grid == null ? 40 : 24;
        if (origin == null || mc.player.position().distanceToSqr(origin) > maxDistance * maxDistance) { reset(); return; }
        TicTacToeBoard.Probe probe = (position, plane) -> cell(mc.level, position, plane);
        TicTacToeBoard.Snapshot current;
        if (grid != null) current = TicTacToeBoard.read(grid, probe);
        else if (++ticks % 5 == 0) {
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
     * survives a re-skin. The thing that actually broke this was the TOKENS: the first
     * version looked for lapis and redstone blocks, which is what the other game uses,
     * while this server lays the board in blue and red concrete. Four iron blocks and
     * their buttons were found, two of nine cells read as tokens, the grid never
     * completed, and it sat on "Finding board" with no hint as to why. Guessing the ids
     * off a screenshot failed twice; a /record of the room gave them in one line.
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

    private static int cell(ClientLevel level, TicTacToeBoard.Point support, TicTacToeBoard.Plane plane) {
        BlockPos behind = block(support);
        BlockPos front = behind.offset(plane.nx, plane.ny, plane.nz);
        if (!level.hasChunkAt(behind) || !level.hasChunkAt(front)) return TicTacToeBoard.UNKNOWN;
        BlockState tile = level.getBlockState(behind), button = level.getBlockState(front);
        // A token replaces the free tile and removes its clicker. Reject the
        // intermediate server packet combination instead of retaining a stale hint.
        if (tile.is(Blocks.BLUE_CONCRETE)) return button.isAir() ? TicTacToeLogic.BLUE : TicTacToeBoard.UNKNOWN;
        if (tile.is(Blocks.RED_CONCRETE)) return button.isAir() ? TicTacToeLogic.RED : TicTacToeBoard.UNKNOWN;
        // A free square is an iron block that still has its button. Both halves are
        // checked: a recording of a real board shows exactly four iron blocks each with
        // a stone button in front and nothing in front of the tokens, so this is the
        // shape rather than a guess, and keeping it tight stops a lone lever elsewhere
        // in the room seeding a board that is not there.
        if (!tile.is(Blocks.IRON_BLOCK)) return TicTacToeBoard.UNKNOWN;
        if (plane(button) != plane) return TicTacToeBoard.UNKNOWN;
        if (button.hasProperty(BlockStateProperties.POWERED)
                && button.getValue(BlockStateProperties.POWERED)) return TicTacToeBoard.UNKNOWN;
        return TicTacToeLogic.EMPTY;
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
        if (!mc.level.getBlockState(support).is(Blocks.IRON_BLOCK)) return;
        var shape = state.getShape(mc.level, recommended);
        if (shape.isEmpty()) return;
        var outline = Gizmos.cuboid(shape.bounds().move(recommended).inflate(.015),
                GizmoStyle.strokeAndFill(0xFF55FF55, 2.5f, 0x6055FF55));
        if (throughWalls) outline.setAlwaysOnTop();
    }
}
