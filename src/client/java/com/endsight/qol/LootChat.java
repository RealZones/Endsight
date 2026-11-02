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
    // Compact was once a fourth way to see every roll. Picking it turned close calls off,
    // so a custom debug message, which has nothing to fold, lost its filter with nothing
    // else changing (Fear, November 1). Now Compact is close calls, folded.
    private static final List<String> VIEWS = List.of("Original", "Close calls", "Compact");

    public static Module module() {
        return Module.group("qol.lootChat", "Loot & Debug",
                "How the server's loot rolls appear in chat.", "Chat", List.of(
                new Setting.Section("Controls"),
                new Setting.Choice("Chat view",
                        "Every roll as sent, close calls only, or close calls folded to one line a kill.",
                        VIEWS, LootChat::view, LootChat::view),
                new Setting.Slider("Close call under",
                        "Used by Close calls and Compact; drops always show.",
                        CUTOFF.min(), CUTOFF.max(), CUTOFF.step(), CUTOFF.get(), CUTOFF.set(), CUTOFF.unit()),
                new Setting.Toggle("Both ends", BOTH_ENDS.description(), BOTH_ENDS.get(), BOTH_ENDS.set()),
                new Setting.Section("Individual keybinds"),
                new Setting.KeyAction(Keybinds.moduleId(FILTER), "Loot filter key", "Keep or change the old module key.",
                        "Toggle", FILTER::toggle),
                new Setting.KeyAction(Keybinds.moduleId(COMPACT), "Compact roll key", "Keep or change the old module key.",
                        "Toggle", COMPACT::toggle)));
    }

    /** A config saved as either old Compact view reads as the new one; the filter comes with it. */
    static String view() {
        if (COMPACT.isEnabled()) return "Compact";
        return FILTER.isEnabled() ? "Close calls" : "Original";
    }

    static void view(String value) {
        COMPACT.setEnabled().accept(value.equals("Compact"));
        FILTER.setEnabled().accept(value.equals("Close calls"));
    }
}
