package com.endsight.visual;

import com.endsight.ui.Keybinds;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;

import java.util.List;

/** Short settings page for independent, simple appearance switches. */
public final class VisualTweaks {
    private VisualTweaks() { }

    private static final Module LIGHT = Fullbright.module();
    private static final Module GLASS = InventoryGlass.module();
    private static final Module PARTICLES = ExplosionParticles.module();
    private static final Module NODES = ShinyNodes.module();
    private static final Setting.Slider GLASS_OPACITY = (Setting.Slider) GLASS.settings().getFirst();
    private static final Setting.Color NODE_COLOUR = (Setting.Color) NODES.settings().get(0);
    private static final Setting.Slider NODE_WIDTH = (Setting.Slider) NODES.settings().get(1);

    public static Module module() {
        return Module.group("visual.tweaks", "Visual Tweaks",
                "Lighting, glass inventories, particles and visible End nodes.", "Visual", List.of(
                new Setting.Section("Controls"),
                new Setting.Toggle("Fullbright", LIGHT.description(), LIGHT.enabled(), LIGHT.setEnabled()),
                new Setting.Toggle("Glass Inventory", GLASS.description(), GLASS.enabled(), GLASS.setEnabled()),
                new Setting.Slider("Glass opacity", GLASS_OPACITY.description(),
                        GLASS_OPACITY.min(), GLASS_OPACITY.max(), GLASS_OPACITY.step(),
                        GLASS_OPACITY.get(), GLASS_OPACITY.set(), GLASS_OPACITY.unit()),
                new Setting.Toggle("Hide explosion clouds", PARTICLES.description(),
                        PARTICLES.enabled(), PARTICLES.setEnabled()),
                new Setting.Toggle("Shiny End nodes", NODES.description(), NODES.enabled(), NODES.setEnabled()),
                new Setting.Color("Node colour", NODE_COLOUR.description(),
                        NODE_COLOUR.get(), NODE_COLOUR.set()),
                new Setting.Slider("Node width", NODE_WIDTH.description(),
                        NODE_WIDTH.min(), NODE_WIDTH.max(), NODE_WIDTH.step(),
                        NODE_WIDTH.get(), NODE_WIDTH.set(), NODE_WIDTH.unit()),
                new Setting.Section("Individual keybinds"),
                new Setting.KeyAction(Keybinds.moduleId(LIGHT), "Fullbright key", "Keep or change the old module key.",
                        "Toggle", LIGHT::toggle),
                new Setting.KeyAction(Keybinds.moduleId(GLASS), "Glass key", "Keep or change the old module key.",
                        "Toggle", GLASS::toggle),
                new Setting.KeyAction(Keybinds.moduleId(PARTICLES), "Explosion key", "Keep or change the old module key.",
                        "Toggle", PARTICLES::toggle)));
    }
}
