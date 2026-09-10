package com.endsight.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * World position to screen position.
 *
 * Exists because this version of Fabric API ships no WorldRenderEvents - the render
 * rewrite has not been ported - so there is no supported hook for putting geometry in
 * the world. Anything that marks a thing in 3D has to be projected to 2D and drawn on
 * the HUD, and more than one module needs to do it.
 *
 * Built from the player's own yaw and pitch rather than the camera quaternion, because
 * the two disagree in third person and the yaw/pitch form is the one whose signs can be
 * reasoned about on paper.
 */
public final class Project {

    private Project() {
    }

    /**
     * View-space coordinates: right, up, and forward depth, all in blocks.
     *
     * Returned rather than screen pixels because the depth and the sign of forward are
     * both needed by callers - depth to size things that should shrink with distance,
     * and the sign to know a target is BEHIND you, which is the case an edge marker
     * exists to handle. Collapsing straight to pixels throws both away.
     */
    public static Vec3 toView(Vec3 target) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return null;

        double yaw = Math.toRadians(player.getYRot());
        double pitch = Math.toRadians(player.getXRot());

        // MC yaw 0 faces +Z and positive pitch looks down - hence both minus signs.
        Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch));
        // forward x worldUp, taken from the horizontal heading so the view never rolls.
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 up = right.cross(forward);

        Vec3 d = target.subtract(player.getEyePosition(1f));
        return new Vec3(d.dot(right), d.dot(up), d.dot(forward));
    }

    /** Pixels per block at a given depth, for sizing anything that shrinks with range. */
    public static double pixelsPerBlock(double depth, int screenH) {
        if (depth <= 0.05) return 0;
        return focal(screenH) / depth;
    }

    /** Screen point for an already-computed view-space vector, or null if behind. */
    public static double[] viewToScreen(Vec3 view, int screenW, int screenH) {
        if (view == null || view.z < 0.05) return null;
        double f = focal(screenH);
        return new double[]{
                screenW / 2.0 + (view.x / view.z) * f,
                screenH / 2.0 - (view.y / view.z) * f
        };
    }

    /** Convenience: world point straight to screen point, or null. */
    public static double[] toScreen(Vec3 target, int screenW, int screenH) {
        return viewToScreen(toView(target), screenW, screenH);
    }

    /**
     * Minecraft's fov option is the VERTICAL field of view, so the focal length is
     * derived from screen height. Using width here is the classic way to get a
     * projection that is subtly wrong on every aspect ratio but 16:9.
     */
    private static double focal(int screenH) {
        double fov = Minecraft.getInstance().options.fov().get();
        return (screenH / 2.0) / Math.tan(Math.toRadians(fov) / 2.0);
    }
}
