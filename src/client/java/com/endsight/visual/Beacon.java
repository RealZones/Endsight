package com.endsight.visual;

import com.endsight.hud.Project;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A beacon over the Endstone Protector, so it can be found without hunting for it.
 *
 * The point of this module is finding, not decorating, and that distinction decides the
 * whole design. A beam drawn only when the golem is already on screen is decoration -
 * by the time you can see the beam you can see the golem. What actually saves time is
 * the case where it is NOT on screen: behind you, above you, or past the edge. So the
 * beam is half the feature and the edge marker is the other half.
 *
 * Both are drawn on the HUD because this Fabric API has no WorldRenderEvents, which
 * also means the beam cannot be occluded by terrain. That is a real limitation and it
 * happens to suit a beacon: something you are meant to find through a wall should be
 * visible through the wall.
 */
public final class Beacon {

    private Beacon() {
    }

    private static final String MATCH = "Endstone Protector";
    /** The server's own colour for the Protector, matching its chat announcements. */
    private static final int PURPLE = 0xFFAA00AA;

    private static boolean enabled = true;
    private static double range = 128;
    private static double beamBlocks = 40;
    private static boolean edgeMarker = true;

    public static Module module() {
        return new Module("visual.beacon", "Protector Beacon",
                "Beam over the Endstone Protector, and an arrow when it is off screen.",
                "Visual",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Slider("Range",
                                "How far away it still draws.",
                                16, 256, 8, () -> range, v -> range = v, "m"),
                        new Setting.Slider("Beam height",
                                "How far the beam rises.",
                                8, 96, 4, () -> beamBlocks, v -> beamBlocks = v, "m"),
                        new Setting.Toggle("Off-screen arrow",
                                "Point to it when it is not in view.",
                                () -> edgeMarker, v -> edgeMarker = v)));
    }

    public static void init() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "beacon"),
                (g, delta) -> draw(g));
    }

    private static void draw(GuiGraphicsExtractor g) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui) return;

        Entity target = find(player);
        if (target == null) return;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        AABB bb = target.getBoundingBox();
        Vec3 base = new Vec3((bb.minX + bb.maxX) / 2, bb.maxY, (bb.minZ + bb.maxZ) / 2);
        Vec3 baseView = Project.toView(base);
        if (baseView == null) return;

        g.nextStratum();

        double[] baseP = Project.viewToScreen(baseView, sw, sh);
        boolean onScreen = baseP != null
                && baseP[0] > -40 && baseP[0] < sw + 40
                && baseP[1] > -40 && baseP[1] < sh + 40;

        if (onScreen) {
            drawBeam(g, baseView, baseP, sw, sh);
        } else if (edgeMarker) {
            drawEdgeArrow(g, mc.font, baseView, sw, sh,
                    (int) Math.round(player.position().distanceTo(target.position())));
        }
    }

    /**
     * The shaft: a column of one-pixel rows, tapering and fading upward.
     *
     * Drawn row by row because there is no quad primitive here, only fill - the same
     * answer the rounded corners and the tracer arrived at. A few hundred fills is
     * nothing next to a frame, and it survives the render API changing under it.
     *
     * Width comes from the projection rather than a constant, so the beam is thick when
     * the golem is on top of you and a thread when it is across the arena. A fixed width
     * reads as a UI element pasted over the world; one that shrinks with distance reads
     * as something standing in it.
     */
    private static void drawBeam(GuiGraphicsExtractor g, Vec3 baseView, double[] baseP,
                                 int sw, int sh) {
        Vec3 topView = baseView.add(0, beamBlocks, 0);
        double[] topP = Project.viewToScreen(topView, sw, sh);

        double topX = topP != null ? topP[0] : baseP[0];
        // Behind the camera means you are underneath it looking up, and the beam still
        // rises: send it straight off the top of the screen rather than dropping it.
        double topY = topP != null ? topP[1] : -60;

        double wide = Project.pixelsPerBlock(baseView.z, sh) * 0.55;
        wide = Math.max(1.5, Math.min(90, wide));

        int steps = (int) Math.min(1600, Math.abs(baseP[1] - topY));
        if (steps <= 0) return;

        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            int x = (int) Math.round(baseP[0] + (topX - baseP[0]) * t);
            int y = (int) Math.round(baseP[1] + (topY - baseP[1]) * t);
            double w = wide * (1 - 0.65 * t);
            float a = 0.75f * (1 - t) * (1 - t);          // fades fast, so the top is a wisp
            if (a < 0.02f) continue;

            int half = (int) Math.max(1, Math.round(w / 2));
            Draw.rect(g, x - half, y, half * 2, 1, Draw.alpha(PURPLE, a));
            // A brighter core keeps the beam readable once the outer width fades out.
            Draw.rect(g, x - 1, y, 2, 1, Draw.alpha(0xFFE9B3FF, a * 0.9f));
        }
    }

    /**
     * An arrow pinned to the screen edge, pointing at something you cannot see.
     *
     * The direction is taken in view space, not from a projected point: a target behind
     * the camera projects to a mirrored position on the wrong side of the screen, so an
     * arrow built from that points confidently in exactly the wrong direction. Negating
     * the view vector when depth is negative is what fixes it, and it is the one piece
     * of this that is easy to get subtly and unnoticeably wrong.
     */
    private static void drawEdgeArrow(GuiGraphicsExtractor g, Font font, Vec3 view,
                                      int sw, int sh, int distance) {
        double vx = view.x;
        double vy = view.y;
        if (view.z < 0) {                 // behind: mirror it back to the correct side
            vx = -vx;
            vy = -vy;
        }
        double len = Math.hypot(vx, vy);
        if (len < 1e-4) return;

        double dirX = vx / len;
        double dirY = -vy / len;          // screen y grows downward

        int cx = sw / 2, cy = sh / 2;
        int marginX = sw / 2 - 30, marginY = sh / 2 - 30;
        // Push out along the direction until it meets whichever edge comes first.
        double scale = Math.min(
                Math.abs(dirX) < 1e-4 ? Double.MAX_VALUE : marginX / Math.abs(dirX),
                Math.abs(dirY) < 1e-4 ? Double.MAX_VALUE : marginY / Math.abs(dirY));
        int ax = (int) Math.round(cx + dirX * scale);
        int ay = (int) Math.round(cy + dirY * scale);

        // A triangle from stacked rows, pointing along the direction.
        int size = 7;
        for (int i = 0; i < size; i++) {
            int w = size - i;
            int px = (int) Math.round(ax + dirX * i);
            int py = (int) Math.round(ay + dirY * i);
            Draw.rect(g, px - w / 2, py - w / 2, Math.max(1, w), Math.max(1, w),
                    Draw.alpha(PURPLE, 0.85f));
        }

        String label = distance + "m";
        Draw.textCentered(g, font, label,
                (int) Math.round(ax - dirX * 12), (int) Math.round(ay - dirY * 12) - 4,
                Draw.alpha(0xFFE9B3FF, 0.9f));
    }

    /** Nearest Endstone Protector in range, or null. */
    private static Entity find(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        Entity best = null;
        double bestDist = range * range;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == player) continue;
            String name = plainName(e);
            if (name == null || !name.contains(MATCH)) continue;

            double d = e.position().distanceToSqr(player.position());
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private static String plainName(Entity e) {
        Component name = e.getCustomName();
        if (name == null) name = e.getDisplayName();
        if (name == null) return null;
        return name.getString().replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "");
    }
}
