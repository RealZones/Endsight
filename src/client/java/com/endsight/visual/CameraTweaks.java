package com.endsight.visual;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Third person on a key, and how third person looks: how far back and how high it sits, its
 * resting tilt, whether walls push it in, which views F5 goes through, and whether the camera
 * slides out or appears. The settings shape every third-person view, the Camera key's and
 * F5's alike; at their defaults each one is vanilla.
 *
 * The switch is the camera itself: on puts you in third person, in the view F5 views allows
 * (front when the back view is skipped), off puts back the view you had. It used to be a
 * "Custom camera" master switch, with the key on Free Look; Fear's word was that the key still
 * turned on Free Look and did not go to the F5 view he had set up. His Free Look key moves here
 * once (Config.migrate).
 *
 * Height and Tilt move only the camera. The player's own rotation, which is what you aim
 * with and what the server sees, is untouched, so neither can change where a click lands.
 *
 * Free Look is Perspective, a separate switch on the same page with its own key: the mouse
 * turns the camera and not you. It goes to the same view the Camera switch would.
 *
 * There were two FOV controls here, a steady FOV and a third person FOV, and Smoothing also
 * eased the camera's turn. Fear had them all taken out: the camera's angle is always the
 * head's, at once, and the FOV is the game's own.
 */
public final class CameraTweaks {
    private CameraTweaks() { }

    private static boolean on, through;
    /** The view to go back to: whatever it was, not always first person. */
    private static CameraType before = CameraType.FIRST_PERSON;
    private static double distance = 4, height, tilt, smoothing;
    private static String views = "All";
    private static final List<String> VIEWS = List.of("All", "Skip front", "Skip back");

    public static Module module() {
        return new Module("visual.camera", "Camera", "Third person with the settings below; they shape F5 too.", "Visual",
                () -> on, CameraTweaks::set, List.of(
                new Setting.Slider("Distance", "Blocks between you and the third person camera.",
                        1, 50, 0.5, () -> distance, v -> distance = v, ""),
                new Setting.Slider("Height", "Raises the third person camera, or lowers it below zero.",
                        -2, 3, 0.1, () -> height, v -> height = v, ""),
                new Setting.Slider("Tilt", "Angles the third person camera down, or up below zero.",
                        -45, 45, 1, () -> tilt, v -> tilt = v, "°"),
                new Setting.Toggle("Through blocks", "Walls do not push the camera in.", () -> through, v -> through = v),
                new Setting.Choice("F5 views", "Which views F5 goes through.", VIEWS, () -> views, v -> views = v),
                new Setting.Slider("Smoothing", "Slides the camera out instead of jumping; zero is instant.",
                        0, 100, 5, () -> smoothing, v -> smoothing = v, "%")));
    }

    private static void set(boolean v) {
        Minecraft mc = Minecraft.getInstance();
        if (v == on) return;
        if (v) {
            // At startup the saved state arrives with no player to look at, so it starts off.
            if (mc.player == null) return;
            before = mc.options.getCameraType().isFirstPerson() ? mc.options.getCameraType() : CameraType.FIRST_PERSON;
            mc.options.setCameraType(thirdPerson());
        } else {
            // Free Look turns the camera without turning you; left on in first person, the
            // view would look one way and you the other.
            if (Perspective.active()) Perspective.module().setEnabled().accept(false);
            mc.options.setCameraType(before);
        }
        on = v;
    }

    /** The third-person view F5 views allows: front only when the back view is skipped. */
    public static CameraType thirdPerson() {
        return views.equals("Skip back") ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK;
    }

    // ── distance ────────────────────────────────────────────────────────────────

    /**
     * What to ask getMaxZoom for. Vanilla asks for its scale times the camera_distance
     * attribute, which is 4 unless a server changes it, so the slider scales that rather
     * than replacing it: at 4 nothing changes, and a server's own distance still counts.
     */
    public static float distance(float vanilla) {
        return vanilla * (float) distance / 4f;
    }

    public static boolean through() {
        return through;
    }

    /** Blocks to lift the camera's starting point above the eyes, before it backs out. */
    public static double height() {
        return height;
    }

    /**
     * The pitch the third person camera looks along. Added before alignWithEntity backs the
     * camera out along it, so a downward tilt also lifts the camera behind you, which is what
     * makes it a tilt and not a lens turned in place. First person is left alone: there the
     * camera is where you aim, and a tilt would put the crosshair off your real look.
     */
    public static float tilt(float pitch) {
        if (tilt == 0 || Minecraft.getInstance().options.getCameraType().isFirstPerson()) return pitch;
        return Math.clamp(pitch + (float) tilt, -90f, 90f);
    }

    private static float shown;
    private static long ns;
    private static boolean fresh = true;

    /**
     * Called every frame. In first person the next third person view starts at the head, and
     * the switch reads off: F5 back to first person ends the camera as the key would.
     */
    public static void frame() {
        if (!Minecraft.getInstance().options.getCameraType().isFirstPerson()) return;
        fresh = true;
        on = false;
    }

    /**
     * The distance actually used this frame. It eases out but never in: a wall that comes
     * between you and the camera has to win at once, or the camera spends a few frames
     * inside it showing whatever is behind.
     *
     * nanoTime, not Anim: Anim ticks in whole milliseconds, and at 300 fps a frame is 3 ms,
     * so its step jitters by a third.
     */
    public static float zoom(float target) {
        if (smoothing <= 0) return target;
        long now = System.nanoTime();
        if (fresh) {
            shown = 0;                          // out of first person: slide out from the head
            ns = now;
            fresh = false;
        }
        float dt = Math.min((now - ns) / 1e9f, 0.1f);
        ns = now;
        if (target < shown) return shown = target;
        shown += (target - shown) * Math.min(1f, dt * speed());
        return shown;
    }

    /**
     * Per second. 5% is about 35 ms to settle, 50% about a tenth of a second, 100% half a
     * second; squared so the low end, where most people will sit, is the finest.
     */
    private static float speed() {
        float rest = 1f - (float) smoothing / 100f;
        return 2f + 28f * rest * rest;
    }

    // ── views ───────────────────────────────────────────────────────────────────

    /** Whether F5 should step past this view. First person is never skipped. */
    public static boolean skips(CameraType type) {
        return type == CameraType.THIRD_PERSON_FRONT && views.equals("Skip front")
                || type == CameraType.THIRD_PERSON_BACK && views.equals("Skip back");
    }
}
