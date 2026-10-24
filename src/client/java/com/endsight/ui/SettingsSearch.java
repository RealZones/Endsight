package com.endsight.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Search the words a person sees, including values that are otherwise hidden in a closed module. */
final class SettingsSearch {
    private SettingsSearch() { }

    static String normalize(String query) {
        return query == null ? "" : query.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static boolean matches(String query, String text) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) return true;
        String haystack = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String word : normalized.split(" ")) if (!haystack.contains(word)) return false;
        return true;
    }

    static String text(Setting setting) {
        String extra = switch (setting) {
            case Setting.Choice choice -> String.join(" ", choice.options());
            case Setting.Action action -> action.button();
            case Setting.KeyAction action -> action.button();
            case Setting.Command command -> command.command();
            default -> "";
        };
        return setting.label() + " " + setting.description() + " " + extra;
    }

    static boolean matches(Setting setting, String query) {
        return matches(query, text(setting));
    }

    /**
     * Whether the module browser's search looks at this setting. Not keybind rows or headings:
     * nearly every page ends in a "Keybinds" heading and an "X key" row, so typing "key" for
     * Key Alerts listed thirty-two pages, each "Matches: Keybinds, X key". They are the same on
     * every page and say nothing about what it does. A page's own search still finds them.
     */
    static boolean browsable(Setting setting) {
        return !(setting instanceof Setting.KeyAction) && !(setting instanceof Setting.Section);
    }

    static List<String> matchingLabels(Module module, String query) {
        if (normalize(query).isEmpty() || module.settings() == null) return List.of();
        List<String> labels = new ArrayList<>();
        for (Setting setting : module.settings()) {
            if (browsable(setting) && matches(setting, query)) labels.add(setting instanceof Setting.Command command
                    ? command.command() : setting.label());
        }
        return List.copyOf(labels);
    }
}
