package com.endsight.qol;

import com.endsight.ui.Keybinds;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;

import java.util.List;

/** A single browser page for the server debug feed and its chat presentation. */
public final class LootChat {
    private LootChat() { }

    // These are the real modules: their old ids still own config values and keybinds.
    // Debug on join is gone: Dragsim has debug and detailed debug in its own settings
    // now, so the mod no longer sends /debug after each join.
    private static final Module FILTER = LootFilter.module();
    private static final Module COMPACT = LootRoll.module();
    private static final Setting.Slider CUTOFF = (Setting.Slider) FILTER.settings().get(0);
    private static final Setting.Toggle BOTH_ENDS = (Setting.Toggle) FILTER.settings().get(1);
    private static final List<String> VIEWS = List.of("Original", "Close calls", "Compact", "Compact + close calls");

    public static Module module() {
        return Module.group("qol.lootChat", "Loot & Debug",
                "How the server's loot rolls appear in chat.", "Chat", List.of(
                new Setting.Section("Controls"),
                new Setting.Choice("Chat view",
                        "Original messages, close calls, compact detailed rolls, or both together.",
                        VIEWS, LootChat::view, LootChat::view),
                new Setting.Slider("Close call under",
                        "Only used by close-call views; real drops always pass.",
                        CUTOFF.min(), CUTOFF.max(), CUTOFF.step(), CUTOFF.get(), CUTOFF.set(), CUTOFF.unit()),
                new Setting.Toggle("Both ends", BOTH_ENDS.description(), BOTH_ENDS.get(), BOTH_ENDS.set()),
                new Setting.Section("Individual keybinds"),
                new Setting.KeyAction(Keybinds.moduleId(FILTER), "Loot filter key", "Keep or change the old module key.",
                        "Toggle", FILTER::toggle),
                new Setting.KeyAction(Keybinds.moduleId(COMPACT), "Compact roll key", "Keep or change the old module key.",
                        "Toggle", COMPACT::toggle)));
    }

    private static String view() {
        if (COMPACT.isEnabled()) return FILTER.isEnabled() ? "Compact + close calls" : "Compact";
        return FILTER.isEnabled() ? "Close calls" : "Original";
    }

    private static void view(String value) {
        COMPACT.setEnabled().accept(value.equals("Compact") || value.equals("Compact + close calls"));
        FILTER.setEnabled().accept(value.equals("Close calls") || value.equals("Compact + close calls"));
    }
}
