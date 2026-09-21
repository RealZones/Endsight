package com.endsight.visual;

import com.endsight.ui.Module;

import java.util.List;

/**
 * The whole world lit as if it were noon. The work is in {@code LightmapMixin}: it
 * feeds the lightmap a full-strength white night vision, which the shader takes as
 * the floor every texel is built from. Nothing else changes - no gamma, no shader
 * of our own - so it costs nothing and cannot disagree with the renderer.
 */
public final class Fullbright {

    private Fullbright() {
    }

    private static boolean enabled = false;

    public static boolean on() {
        return enabled;
    }

    public static Module module() {
        return new Module("visual.fullbright", "Fullbright",
                "Lights the whole world.",
                "Visual",
                () -> enabled, v -> enabled = v,
                List.of());
    }
}
