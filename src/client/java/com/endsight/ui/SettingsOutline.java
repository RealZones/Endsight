package com.endsight.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The visible settings are independent of scrolling, fonts and control hit boxes. */
final class SettingsOutline {
    private SettingsOutline() { }

    record Entry(Setting setting, int index, boolean expanded, boolean matching, int children) { }
    private record Group(Setting.Section heading, int index, List<Entry> entries) { }

    static Set<Integer> initiallyCollapsed(List<Setting> settings) {
        Set<Integer> collapsed = new HashSet<>();
        boolean first = true;
        for (int i = 0; i < settings.size(); i++) {
            if (!(settings.get(i) instanceof Setting.Section)) continue;
            if (!first) collapsed.add(i);
            first = false;
        }
        return collapsed;
    }

    static List<Entry> visible(List<Setting> settings, String query, Set<Integer> collapsed) {
        List<Group> groups = new ArrayList<>();
        List<Entry> children = new ArrayList<>();
        Setting.Section heading = null;
        int section = -1;
        for (int i = 0; i < settings.size(); i++) {
            Setting setting = settings.get(i);
            if (setting instanceof Setting.Section next) {
                groups.add(new Group(heading, section, children));
                heading = next;
                section = i;
                children = new ArrayList<>();
            } else children.add(new Entry(setting, i, false,
                    !SettingsSearch.normalize(query).isEmpty() && SettingsSearch.matches(setting, query), 0));
        }
        groups.add(new Group(heading, section, children));
        boolean searching = !SettingsSearch.normalize(query).isEmpty();
        boolean filter = searching && groups.stream().anyMatch(group ->
                group.heading() != null && SettingsSearch.matches(group.heading(), query)
                        || group.entries().stream().anyMatch(Entry::matching));
        List<Entry> result = new ArrayList<>();
        for (Group group : groups) {
            boolean sectionMatch = searching && group.heading() != null && SettingsSearch.matches(group.heading(), query);
            List<Entry> shown = !filter || sectionMatch ? group.entries()
                    : group.entries().stream().filter(Entry::matching).toList();
            if (filter && !sectionMatch && shown.isEmpty()) continue;
            boolean expanded = filter || !collapsed.contains(group.index());
            if (group.heading() != null)
                result.add(new Entry(group.heading(), group.index(), expanded, sectionMatch, group.entries().size()));
            if (expanded) result.addAll(shown);
        }
        return List.copyOf(result);
    }
}
