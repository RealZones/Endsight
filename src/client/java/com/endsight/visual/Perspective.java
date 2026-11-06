package com.endsight.visual;

import com.endsight.ui.Module;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Third person where the mouse moves the camera and the player stays exactly as they were.
 *
 * The mouse's turn is caught before it reaches the head (PerspectiveTurnMixin) and spent on a
 * camera angle of our own instead (PerspectiveCameraMixin). So what the camera circles is the
 * head the server has, the one everybody else sees, and looking around never turns you.
 */
public final class Perspective {
    private Perspective() { }

    private static boolean on;
    private static float yaw, pitch;
    /** The view to go back to: whatever it was, not always first person. */
    private static CameraType before = CameraType.FIRST_PERSON;

    public static Module module() {
        return new Module("visual.perspective", "Perspective Mode",
                "Third person where the mouse moves the camera, not you.", "Visual",
                () -> on, Perspective::set, List.of());
    }

    private static void set(boolean v) {
        Minecraft mc = Minecraft.getInstance();
        if (v == on) return;
        if (v) {
            // At startup the saved state arrives with no player to look at, so it starts off.
            if (mc.player == null) return;
            yaw = mc.player.getYRot();
            pitch = mc.player.getXRot();
            before = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        } else {
            mc.options.setCameraType(before);
        }
        on = v;
    }

    public static boolean active() {
        return on && Minecraft.getInstance().player != null;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    /** The mouse's turn in Entity.turn's units, which it scales by 0.15, so it feels the same. */
    public static void turn(double yRot, double xRot) {
        yaw += (float) yRot * 0.15F;
        pitch = Mth.clamp(pitch + (float) xRot * 0.15F, -90.0F, 90.0F);
    }
}
