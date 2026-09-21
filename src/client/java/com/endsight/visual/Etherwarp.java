package com.endsight.visual;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The block Etherwarp would land on, boxed in the world while you sneak with the blade
 * out. No box means no warp: too far, or no room to stand there.
 *
 * Etherwarp goes to the first block on your look line within its range, and only if
 * the two blocks above it are clear, so there is room to stand. The server runs that
 * check when you click; this runs the same walk every frame so the answer is on screen
 * before the click. The fails are not always obvious - a fence, a slab under a low
 * ceiling, a block just past the range - and every one costs the mana and a second.
 * A blocked target is not drawn in a second colour: a box is a promise the warp works,
 * and the absence of one is a clear enough no.
 *
 * The range is read off the item's own lore rather than fixed, so a tuned blade or a
 * different sword with the ability gets its own number.
 *
 * The box is a gizmo, like the Voidgloom marks: the game's own 3D shape, added fresh
 * every frame, so it sits on the block with depth and thickness. The first version
 * projected the corners and drew on the HUD, and it looked stuck to the screen rather
 * than to the block.
 */
public final class Etherwarp {

    private Etherwarp() {
    }

    private static boolean enabled = true;
    private static final List<String> STYLES = List.of("Outline", "Filled", "Both");
    private static String style = "Both";
    private static int colour = Setting.Color.CHROMA;
    private static double width = 2;

    public static Module module() {
        return new Module("visual.etherwarp", "Etherwarp Outline",
                "Boxes the block Etherwarp will take you to.",
                "Visual",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Choice("Style", "Edges, a filled box, or both.", STYLES, () -> style, v -> style = v),
                        new Setting.Color("Colour", "The box.", () -> colour, v -> colour = v),
                        new Setting.Slider("Width", "How thick the edges are.", 1, 5, 0.5, () -> width, v -> width = v, "px")));
    }

    public static void init() {
        // A gizmo lives one frame, so the box is added again every frame, just before
        // the game draws them.
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> draw());
    }

    private static void draw() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || mc.screen != null) return;
        if (!player.isShiftKeyDown()) return;
        int range = range(player.getMainHandItem());
        if (range <= 0) return;

        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Target t = target(level, player.getEyePosition(partial), player.getViewVector(partial), range);
        if (t == null || !t.clear) return;

        int raw = colour;
        int c = Setting.Color.live(raw);
        int fill = (c & 0x00FFFFFF) | 0x40000000;
        float w = (float) width;
        boolean stroke = !"Filled".equals(style), filled = !"Outline".equals(style);
        // On top so the far edges show through the block: a box, not three edges.
        if (raw == Setting.Color.CHROMA) {
            // A cuboid takes one colour, and chroma is the point where one is not enough:
            // the edges are drawn as their own lines so the rainbow can run along them.
            if (filled) Gizmos.cuboid(t.box, GizmoStyle.fill(fill)).setAlwaysOnTop();
            if (stroke) chromaEdges(t.box, w);
        } else {
            GizmoStyle s = filled && stroke ? GizmoStyle.strokeAndFill(c, w, fill)
                    : filled ? GizmoStyle.fill(fill) : GizmoStyle.stroke(c, w);
            Gizmos.cuboid(t.box, s).setAlwaysOnTop();
        }
    }

    /** Corner index bits: 1 = max x, 2 = max y, 4 = max z. */
    private static final int[][] EDGES = {
            {0, 1}, {2, 3}, {4, 5}, {6, 7},
            {0, 2}, {1, 3}, {4, 6}, {5, 7},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}};
    private static final int SEGMENTS = 6;

    /**
     * The twelve edges in six pieces each, every piece its own colour: the hue runs
     * diagonally across the box, corner to far corner, and slides along it with time.
     * Six pieces is where the steps stop showing at the distances a warp is made from.
     */
    private static void chromaEdges(AABB box, float w) {
        double phase = (System.currentTimeMillis() % 3000) / 3000.0;
        double span = (box.maxX - box.minX) + (box.maxY - box.minY) + (box.maxZ - box.minZ);
        for (int[] e : EDGES) {
            Vec3 a = corner(box, e[0]), b = corner(box, e[1]);
            for (int k = 0; k < SEGMENTS; k++) {
                Vec3 p = a.lerp(b, (double) k / SEGMENTS), q = a.lerp(b, (double) (k + 1) / SEGMENTS);
                Vec3 m = p.add(q).scale(0.5);
                double along = ((m.x - box.minX) + (m.y - box.minY) + (m.z - box.minZ)) / span;
                float hue = (float) ((phase + along * 0.75) % 1.0);
                Gizmos.line(p, q, 0xFF000000 | (Mth.hsvToRgb(hue, 0.85f, 1f) & 0xFFFFFF), w).setAlwaysOnTop();
            }
        }
    }

    private static Vec3 corner(AABB box, int i) {
        return new Vec3((i & 1) == 0 ? box.minX : box.maxX,
                (i & 2) == 0 ? box.minY : box.maxY,
                (i & 4) == 0 ? box.minZ : box.maxZ);
    }

    // ---- what the warp would do ----

    private record Target(AABB box, boolean clear) {
    }

    private static final Pattern RANGE = Pattern.compile("up to (\\d+) blocks");
    private static ItemStack lastStack;
    private static int lastRange;

    /**
     * The Etherwarp range in the held item's lore, or 0 if it has no Etherwarp. Cached
     * on the stack itself: the hotbar hands back the same object every frame until the
     * server replaces it, so the lore is only read when the item actually changes.
     */
    private static int range(ItemStack s) {
        if (s == lastStack) return lastRange;
        lastStack = s;
        lastRange = 0;
        ItemLore lore = s.get(DataComponents.LORE);
        if (lore == null) return 0;
        boolean ether = false;
        for (Component c : lore.lines()) {
            // The server writes its lore as legacy codes in the string - "up to §a57
            // blocks§7 away" - which the first version matched against and never found.
            String line = Zealots.strip(c.getString());
            // Only after the ability's own header, so Instant Transmission's "12 blocks
            // ahead" on the same blade can never be taken for the range.
            if (line.contains("Etherwarp")) ether = true;
            if (!ether) continue;
            Matcher m = RANGE.matcher(line);
            if (m.find()) {
                lastRange = Integer.parseInt(m.group(1));
                break;
            }
        }
        return lastRange;
    }

    /**
     * Walks the look line one block boundary at a time to the first block with a
     * collision box - anything you cannot walk through, which is what the warp stops
     * at - and asks whether the two above it are clear. Fluids, grass, torches and the
     * like have no collision, so the line passes through them and you may land in them,
     * both as the server has it.
     */
    private static Target target(Level level, Vec3 eye, Vec3 dir, int range) {
        int x = Mth.floor(eye.x), y = Mth.floor(eye.y), z = Mth.floor(eye.z);
        int sx = dir.x > 0 ? 1 : -1, sy = dir.y > 0 ? 1 : -1, sz = dir.z > 0 ? 1 : -1;
        double tx = next(eye.x, dir.x), ty = next(eye.y, dir.y), tz = next(eye.z, dir.z);
        double dx = span(dir.x), dy = span(dir.y), dz = span(dir.z);
        for (double t = 0; t <= range; ) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!clear(level, pos)) {
                VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
                AABB box = (shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds()).move(pos);
                return new Target(box, clear(level, pos.above()) && clear(level, pos.above(2)));
            }
            if (tx < ty && tx < tz) {
                x += sx;
                t = tx;
                tx += dx;
            } else if (ty < tz) {
                y += sy;
                t = ty;
                ty += dy;
            } else {
                z += sz;
                t = tz;
                tz += dz;
            }
        }
        return null;
    }

    /** Distance along the line to the next block boundary on this axis. */
    private static double next(double p, double d) {
        if (d == 0) return Double.POSITIVE_INFINITY;
        double f = p - Math.floor(p);
        return (d > 0 ? 1 - f : f) / Math.abs(d);
    }

    /** Distance along the line between boundaries on this axis. */
    private static double span(double d) {
        return d == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(d);
    }

    private static boolean clear(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }
}
