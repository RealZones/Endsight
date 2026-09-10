package com.endsight.hud;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * World position to screen position.
 *
 * Exists because this version of Fabric API ships no WorldRenderEvents - the render
 * rewrite has not been ported - so there is no supported hook for putting geometry in
 * the world. Anything that marks a thing in 3D has to be projected to 2D and drawn on
 * the HUD, and more than one module needs to do it.
 *
 * Everything comes from Camera - position, rotation and field of view - because that is
 * the state the renderer itself projects with. An earlier version used the player's yaw
 * and pitch and the FOV slider, which disagree with the camera whenever you sprint, are
 * in third person, or have any speed effect running.
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
        Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (cam == null) return null;

        double yaw = Math.toRadians(cam.yRot());
        double pitch = Math.toRadians(cam.xRot());

        // MC yaw 0 faces +Z and positive pitch looks down - hence both minus signs.
        Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch));
        // forward x worldUp, taken from the horizontal heading so the view never rolls.
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 up = right.cross(forward);

        Vec3 d = target.subtract(cam.position());
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
     * Focal length in pixels, from the camera's ACTUAL field of view.
     *
     * The first version read Options.fov(), which is the slider value and not what the
     * renderer uses: sprinting, speed effects and the fov-effect-scale option all widen
     * the real view. Projecting with the slider value put everything systematically too
     * close to the centre of the screen, which is what made the beacon miss the golem it
     * was standing on. Camera.getFov() is the number the projection matrix is built from,
     * so it cannot disagree.
     *
     * The vertical FOV pairs with screen height. Using width here is the classic way to
     * get a projection that is subtly wrong on every aspect ratio except 16:9.
     *
     * Units are not guaranteed across versions - some releases hand this back in radians
     * - so anything too small to be a sane degree value is treated as radians rather than
     * trusted blindly. A 0.5 here would otherwise mean a focal length of roughly nothing
     * and put every marker in the corner.
     */
    private static double focal(int screenH) {
        double fov = Minecraft.getInstance().gameRenderer.getMainCamera().getFov();
        double radians = fov < 3.2 ? fov : Math.toRadians(fov);
        if (radians < 0.05) radians = Math.toRadians(70);      // nonsense: fall back
        return (screenH / 2.0) / Math.tan(radians / 2.0);
    }
}
