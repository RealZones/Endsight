package com.endsight.qol;

import com.endsight.ui.Keybinds;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;

import java.util.List;

/** A single browser page for the server debug feed and its chat presentation. */
public final class LootChat {
    private LootChat() { }

    // These are the real modules: their old ids still own config values and keybinds.
    private static final Module DEBUG = DebugOnJoin.module();
    private static final Module FILTER = LootFilter.module();
    private static final Module COMPACT = LootRoll.module();
    private static final Setting.Choice DETAIL = (Setting.Choice) DEBUG.settings().getFirst();
    private static final Setting.Slider CUTOFF = (Setting.Slider) FILTER.settings().get(0);
    private static final Setting.Toggle BOTH_ENDS = (Setting.Toggle) FILTER.settings().get(1);
    private static final List<String> VIEWS = List.of("Original", "Close calls", "Compact", "Compact + close calls");

    public static Module module() {
        return Module.group("qol.lootChat", "Loot & Debug",
                "Server debug on join and how its loot rolls appear in chat.", "Chat", List.of(
                new Setting.Section("Controls"),
                new Setting.Toggle("Debug on join",
                        "Request debug after each server join; changing this does not toggle the current server.",
                        DEBUG.enabled(), DEBUG.setEnabled()),
                new Setting.Choice("Server detail on join",
                        "Normal shows loot numbers. Detailed adds rolls and is needed by Mob Coins.",
                        DETAIL.options(), DETAIL.get(), DETAIL.set()),
                new Setting.Choice("Chat view",
                        "Original messages, close calls, compact detailed rolls, or both together.",
                        VIEWS, LootChat::view, LootChat::view),
                new Setting.Slider("Close call under",
                        "Only used by close-call views; real drops always pass.",
                        CUTOFF.min(), CUTOFF.max(), CUTOFF.step(), CUTOFF.get(), CUTOFF.set(), CUTOFF.unit()),
                new Setting.Toggle("Both ends", BOTH_ENDS.description(), BOTH_ENDS.get(), BOTH_ENDS.set()),
                new Setting.Note("Mob Coins", () -> "Mob Coins needs Detailed debug from the server; the chat view only changes what you see."),
                new Setting.Section("Individual keybinds"),
                new Setting.KeyAction(Keybinds.moduleId(DEBUG), "Debug on join key", "Keep or change the old module key.",
                        "Toggle", DEBUG::toggle),
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
