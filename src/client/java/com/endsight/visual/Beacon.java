package com.endsight.visual;

import com.endsight.dragons.Protector;
import com.endsight.hud.Project;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import com.endsight.zealots.Zealots;
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

    private static final String PROTECTOR = "Endstone Protector";
    private static final String WARDEN = "Warden";

    private static boolean enabled = true;
    private static boolean protector = true, warden = true;
    private static double range = 128;
    private static double beamBlocks = 40;
    private static double brightness = 100;
    private static ClientLevel targetWorld;
    private static double targetRange;
    private static final Entity[] targets = new Entity[2];
    private static final long[] targetReadAt = new long[2];

    public static Module module() {
        return new Module("visual.beacon", "Boss Beacon",
                "Beam over the Protector and the Warden.",
                "Visual",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Section("Bosses"),
                        new Setting.Toggle("Protector", "A beam over the Endstone Protector.", () -> protector, v -> protector = v),
                        new Setting.Toggle("Warden", "A beam over the Warden.", () -> warden, v -> warden = v),
                        new Setting.Section("Appearance"),
                        new Setting.Slider("Range",
                                "How far away it still draws.",
                                16, 256, 8, () -> range, v -> range = v, "m"),
                        new Setting.Slider("Beam height",
                                "How far the beam rises.",
                                8, 96, 4, () -> beamBlocks, v -> beamBlocks = v, "m"),
                        new Setting.Slider("Brightness",
                                "How bright the beam is.",
                                30, 130, 5, () -> brightness, v -> brightness = v, "%")));
    }

    public static void init() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "beacon"),
                (g, delta) -> draw(g));
    }

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (targetWorld != mc.level || targetRange != range) {
            targetWorld = mc.level; targetRange = range;
            targets[0] = targets[1] = null; targetReadAt[0] = targetReadAt[1] = 0;
        }
        if (!enabled) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui) return;
        if (!com.endsight.hud.Area.end()) return;

        // One beam each; both when both are up, which is exactly when you want them.
        if (protector && Protector.protectorActive()) beam(g, mc, player, target(player, PROTECTOR, 0), sw(mc), sh(mc));
        if (warden && Protector.wardenUp()) beam(g, mc, player, target(player, WARDEN, 1), sw(mc), sh(mc));
    }

    private static int sw(Minecraft mc) {
        return mc.getWindow().getGuiScaledWidth();
    }

    private static int sh(Minecraft mc) {
        return mc.getWindow().getGuiScaledHeight();
    }

    private static void beam(GuiGraphicsExtractor g, Minecraft mc, LocalPlayer player, Entity target, int sw, int sh) {
        if (target == null) return;

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
     * Native transformed rectangles replace the former three fills per screen pixel.
     * Clipping comes first: a near-camera projection can be millions of pixels long,
     * and skipping invisible pixels inside that old loop still visited every one.
     * Twenty-four small gradient sections retain the taper and glow with at most 72
     * submitted rectangles, independent of resolution and projected beam length.
     */
    private static void drawBeam(GuiGraphicsExtractor g, Vec3 baseView, double[] baseP, int sw, int sh) {
        double[] topP = Project.viewToScreen(baseView.add(0, beamBlocks, 0), sw, sh);
        double x1 = topP == null ? baseP[0] : topP[0];
        double y1 = topP == null ? -sh : topP[1];
        BeamGeometry.Visible visible = BeamGeometry.clip(baseP[0], baseP[1], x1, y1, sw, sh);
        if (visible == null) return;
        double wide = Math.clamp(Project.pixelsPerBlock(baseView.z, sh) * .5, 4, 70);
        double length = visible.length();
        int sections = visible.segments();
        float bright = (float) (brightness / 100.0);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) visible.x(), (float) visible.y());
        pose.rotate((float) (Math.atan2(visible.dy(), visible.dx()) - Math.PI / 2));
        try {
            for (int i = 0; i < sections; i++) {
                double start = visible.from() + (visible.to() - visible.from()) * i / sections;
                double end = visible.from() + (visible.to() - visible.from()) * (i + 1) / sections;
                double width = wide * (1 - .25 * (start + end) / 2);
                float alpha0 = (float) (1 - .85 * start * start) * bright;
                float alpha1 = (float) (1 - .85 * end * end) * bright;
                int y0 = (int) Math.round(length * i / sections);
                int y2 = (int) Math.round(length * (i + 1) / sections);
                if (y2 <= y0) continue;
                gradient(g, width, y0, y2, GLOW, alpha0 * .20f, alpha1 * .20f);
                gradient(g, width * .45, y0, y2, GLOW, alpha0 * .55f, alpha1 * .55f);
                gradient(g, Math.max(2, width * .16), y0, y2, CORE, alpha0 * .95f, alpha1 * .95f);
            }
        } finally { pose.popMatrix(); }
    }

    private static void gradient(GuiGraphicsExtractor g, double width, int y0, int y1, int colour, float start, float end) {
        int half = Math.max(1, (int) Math.round(width / 2));
        g.fillGradient(-half, y0, half, y1,
                Draw.alpha(colour, Math.min(1, start)), Draw.alpha(colour, Math.min(1, end)));
    }

    private static Entity target(LocalPlayer player, String match, int index) {
        long now = System.currentTimeMillis();
        Entity known = targets[index];
        if (now < targetReadAt[index] || now - targetReadAt[index] >= 100
                || known != null && known.isRemoved()) {
            targets[index] = known = find(player, match);
            targetReadAt[index] = now;
        }
        return known != null && known.distanceToSqr(player) <= range * range ? known : null;
    }

    /**
     * The entity a masked mob would be, when its name cannot say.
     *
     * The Rift renames every mob "Dinnerbone", so a name that has to contain "Endstone
     * Protector" matches nothing there and the beam never appears. Type is the one thing
     * the mask does not take away.
     */
    private static boolean isType(Entity e, String match) {
        return PROTECTOR.equals(match) ? e instanceof net.minecraft.world.entity.animal.golem.IronGolem
                : WARDEN.equals(match) && e instanceof net.minecraft.world.entity.monster.warden.Warden;
    }

    private static Entity find(LocalPlayer player, String match) {
        Minecraft mc = Minecraft.getInstance();
        Entity best = null;
        double bestDist = range * range;

        Entity typed = null;
        double bestTyped = range * range;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == player || e instanceof net.minecraft.world.entity.player.Player) continue;
            String name = plainName(e);
            double d = e.position().distanceToSqr(player.position());
            // The name wins wherever there is one to read, so nothing changes in the End.
            if (name != null && name.contains(match) && !petName(name)) {
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            } else if ("Dinnerbone".equals(name) && isType(e, match) && d < bestTyped) {
                bestTyped = d;
                typed = e;
            }
        }
        // Only if no name matched at all: in the Rift every candidate is masked.
        return best != null ? best : typed;
    }

    private static boolean petName(String name) {
        String s = name.toLowerCase(java.util.Locale.ROOT);
        // "[Lvl 1]" is a pet; "[Lv100] Endstone Protector" is the boss itself, which is what
        // the earlier "[lv" check was throwing away - no beam for a whole session.
        return s.contains("[lvl") || s.contains(" pet")
                || s.contains("'s warden") || s.contains("'s endstone protector");
    }

    private static String plainName(Entity e) {
        Component name = e.getCustomName();
        if (name == null) name = e.getDisplayName();
        if (name == null) return null;
        return Zealots.strip(name.getString());
    }
}
