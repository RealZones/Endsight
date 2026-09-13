package com.endsight.visual;

import com.endsight.hud.Project;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
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

    private static boolean enabled = true;
    private static double range = 128;
    private static double beamBlocks = 40;
    private static double brightness = 100;

    public static Module module() {
        return new Module("visual.beacon", "Protector Beacon",
                "Beam over the Endstone Protector so it can be found at a glance.",
                "Visual",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Slider("Range",
                                "How far away it still draws.",
                                16, 256, 8, () -> range, v -> range = v, "m"),
                        new Setting.Slider("Beam height",
                                "How far the beam rises.",
                                8, 96, 4, () -> beamBlocks, v -> beamBlocks = v, "m"),
                        new Setting.Slider("Brightness",
                                "How strongly the beam burns.",
                                30, 130, 5, () -> brightness, v -> brightness = v, "%")));
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

        // Drawn whenever the golem is in front of the camera, not only when its base is
        // on screen: the base can be below the viewport while the shaft still rises
        // through it, and that is exactly the case where a beacon earns its keep.
        double[] baseP = Project.viewToScreen(baseView, sw, sh);
        if (baseP != null) drawBeam(g, baseView, baseP, sw, sh);
    }

    private static final int CORE = 0xFFF3D6FF;      // near-white heart of the beam
    private static final int GLOW = 0xFFC24BFF;      // saturated purple around it

    /**
     * The shaft: three layers per step - a wide soft glow, a mid body, a near-white core.
     *
     * One flat column read as a stray magenta thread rather than as light. What makes
     * something look like a beam is the falloff: bright and narrow in the middle, dimmer
     * and wider outside it. Three layers is the cheapest thing that reads that way with
     * only fill to draw with.
     *
     * Stepped along whichever screen axis is LONGER, one pixel at a time. The first
     * version walked a fixed count of steps capped at 1600, which is fine at range and
     * became a ladder up close: a 40-block beam seen from a few metres away spans several
     * thousand screen pixels, so consecutive steps landed two or three pixels apart and
     * left gaps between them. Iterating the dominant axis guarantees exactly one step per
     * pixel at any distance and any angle, and costs nothing extra because rows outside
     * the viewport are skipped rather than drawn.
     */
    private static void drawBeam(GuiGraphicsExtractor g, Vec3 baseView, double[] baseP,
                                 int sw, int sh) {
        Vec3 topView = baseView.add(0, beamBlocks, 0);
        double[] topP = Project.viewToScreen(topView, sw, sh);

        double x0 = baseP[0], y0 = baseP[1];
        double x1 = topP != null ? topP[0] : x0;
        // Behind the camera means you are underneath it looking up, and the beam still
        // rises: send it off the top of the screen rather than dropping it downward.
        double y1 = topP != null ? topP[1] : -sh;

        double dx = x1 - x0, dy = y1 - y0;
        boolean vertical = Math.abs(dy) >= Math.abs(dx);
        int span = (int) Math.round(Math.abs(vertical ? dy : dx));
        if (span <= 0) return;

        double wide = Project.pixelsPerBlock(baseView.z, sh) * 0.5;
        wide = Math.max(4, Math.min(70, wide));
        float bright = (float) (brightness / 100.0);

        for (int i = 0; i <= span; i++) {
            double t = i / (double) span;
            int x = (int) Math.round(x0 + dx * t);
            int y = (int) Math.round(y0 + dy * t);
            if (x < -80 || x > sw + 80 || y < -2 || y > sh + 2) continue;

            double w = wide * (1 - 0.25 * t);
            float fade = (1 - (float) (t * t) * 0.85f) * bright;
            if (fade <= 0.01f) continue;

            if (vertical) {
                span(g, x, y, w, Draw.alpha(GLOW, Math.min(1f, 0.20f * fade)));
                span(g, x, y, w * 0.45, Draw.alpha(GLOW, Math.min(1f, 0.55f * fade)));
                span(g, x, y, Math.max(2, w * 0.16), Draw.alpha(CORE, Math.min(1f, 0.95f * fade)));
            } else {
                // Looking almost along the beam, so its thickness runs vertically.
                vspan(g, x, y, w, Draw.alpha(GLOW, Math.min(1f, 0.20f * fade)));
                vspan(g, x, y, w * 0.45, Draw.alpha(GLOW, Math.min(1f, 0.55f * fade)));
                vspan(g, x, y, Math.max(2, w * 0.16), Draw.alpha(CORE, Math.min(1f, 0.95f * fade)));
            }
        }
    }

    private static void span(GuiGraphicsExtractor g, int cx, int y, double w, int color) {
        int half = (int) Math.max(1, Math.round(w / 2));
        Draw.rect(g, cx - half, y, half * 2, 1, color);
    }

    private static void vspan(GuiGraphicsExtractor g, int x, int cy, double h, int color) {
        int half = (int) Math.max(1, Math.round(h / 2));
        Draw.rect(g, x, cy - half, 1, half * 2, color);
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
