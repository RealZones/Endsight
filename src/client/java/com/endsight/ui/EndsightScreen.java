package com.endsight.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** A compact module list. One open module shares the settings page's real controls. */
public class EndsightScreen extends Screen {
    private final ModuleRegistry registry;
    private final String brand;
    private static String category, expandedId;
    private static String query = "";
    private static int scroll;
    private record ViewState(String category, String query, int scroll, String expanded) { }
    private static final Map<ModuleRegistry, ViewState> views = new WeakHashMap<>();
    private static ModuleRegistry activeRegistry;

    private String binding;
    private boolean suppressCharacter;
    private boolean searchFocused = true;
    private int contentHeight, navScroll, refreshTicks, resultCount;
    private SettingsScreen editor;
    private Module editingModule;
    private Map<String, List<Module>> groups;
    private final Map<String, String> matchSummaries = new HashMap<>();
    private final Map<String, Anim> anims = new HashMap<>();
    private final ThemeRow themeRow = new ThemeRow();
    private final List<Placed> rows = new ArrayList<>();
    private final List<Heading> headings = new ArrayList<>();
    private final List<Nav> nav = new ArrayList<>();
    private record Placed(Module module, ModuleRowLayout geometry, int bodyY, int bodyH) { }
    private record Heading(String title, int count, int y) { }
    private record Nav(String title, String category, int y) { }
    private static final int HEADER = 66, INSET = 12, NAV_PITCH = 26;

    public EndsightScreen(String brand, ModuleRegistry registry) {
        super(Component.literal(brand));
        this.brand = brand;
        this.registry = registry;
        if (activeRegistry != registry) {
            if (activeRegistry != null) views.put(activeRegistry, new ViewState(category, query, scroll, expandedId));
            ViewState view = views.get(registry);
            category = view == null ? null : view.category();
            query = view == null ? "" : view.query();
            scroll = view == null ? 0 : view.scroll();
            expandedId = view == null ? null : view.expanded();
            activeRegistry = registry;
        }
    }

    @Override
    protected void init() {
        if (editor != null) editor.init(width, height);
    }

    @Override
    public void tick() {
        super.tick();
        suppressCharacter = false;
        if (editor != null) editor.tick();
        // Most settings are immutable descriptors. Only live notes/command text need
        // a bounded refresh while searching; a frame must not refilter fifty modules.
        if (++refreshTicks >= 10) {
            refreshTicks = 0;
            if (!query.isBlank() && (editor == null || !editor.capturesKeyboard())) registry.invalidateSearch();
        }
    }

    @Override
    public void removed() {
        views.put(registry, new ViewState(category, query, scroll, expandedId));
        if (editor != null) editor.cancelInteractions();
        super.removed();
    }

    private int panelX() { return Theme.panelX(width); }
    private int panelY() { return Theme.panelY(height); }
    private int panelW() { return Theme.panelW(width); }
    private int panelH() { return Theme.panelH(height); }
    private int sidebarW() { return Math.min(Theme.SIDEBAR_W, Math.max(144, panelW() / 3)); }
    private int contentX() { return panelX() + sidebarW(); }
    private int contentW() { return panelW() - sidebarW(); }
    private int contentTop() { return panelY() + HEADER; }
    private int contentBottom() { return panelY() + panelH() - 10; }
    private int navTop() { return contentTop() + 20; }
    private int navBottom() { return Math.max(navTop() + 20, moveButtonY() - 8); }
    private boolean compactFooter() { return panelH() < 260; }
    private int moveButtonY() {
        if (compactFooter()) return panelY() + panelH() - 47;
        int paletteRows = (Theme.palettes().size() + 4) / 5;
        return panelY() + panelH() - (paletteRows * 25 - 7) - 78;
    }
    private int searchX() { return contentX() + INSET; }
    private int searchY() { return panelY() + 31; }
    private int searchW() { return Math.max(40, contentW() - INSET * 2); }
    private int maxScroll() { return Math.max(0, contentHeight - (contentBottom() - contentTop())); }
    private int maxNavScroll() { return Math.max(0, (registry.categories().size() + 1) * NAV_PITCH - (navBottom() - navTop())); }

    private Anim anim(String key) { return anims.computeIfAbsent(key, ignored -> new Anim(0)); }

    private void results() {
        Map<String, List<Module>> next = registry.grouped(category, query);
        if (groups == next) return;
        groups = next;
        resultCount = 0;
        matchSummaries.clear();
        for (List<Module> group : groups.values()) for (Module module : group) {
            resultCount++;
            List<String> labels = SettingsSearch.matchingLabels(module, query);
            if (!labels.isEmpty()) {
                String text = String.join(", ", labels.subList(0, Math.min(2, labels.size())));
                matchSummaries.put(module.id(), "Matches: " + text + (labels.size() > 2 ? " +" + (labels.size() - 2) : ""));
            }
        }
    }

    private Module visibleModule(String id) {
        if (id == null) return null;
        for (List<Module> group : groups.values()) for (Module module : group)
            if (id.equals(module.id())) return module;
        return null;
    }

    private void ensureEditor(Module module) {
        if (editingModule == module && editor != null) return;
        if (editor != null) editor.cancelInteractions();
        editingModule = module;
        editor = module == null || !module.hasSettings() ? null : SettingsScreen.embedded(this, brand, module);
        if (editor != null) editor.init(width, height);
    }

    private void layout() {
        results();
        if (category != null && !registry.categories().contains(category)) {
            category = null;
            results();
        }
        ensureEditor(visibleModule(expandedId));
        navScroll = Math.max(0, Math.min(navScroll, maxNavScroll()));
        nav.clear();
        int ny = navTop() - navScroll;
        nav.add(new Nav("All modules", null, ny));
        for (String name : registry.categories()) {
            ny += NAV_PITCH;
            nav.add(new Nav(name, name, ny));
        }
        themeRow.layout(panelX(), panelY(), panelH());
        placeRows();
        int clamped = Math.max(0, Math.min(scroll, maxScroll()));
        if (clamped != scroll) { scroll = clamped; placeRows(); }
    }

    private void placeRows() {
        rows.clear(); headings.clear();
        int x = contentX() + INSET, w = Math.max(80, contentW() - INSET * 2);
        int y = contentTop() + 8 - scroll;
        for (Map.Entry<String, List<Module>> group : groups.entrySet()) {
            if (category == null) {
                headings.add(new Heading(group.getKey(), group.getValue().size(), y));
                y += 24;
            }
            for (Module module : group.getValue()) {
                ModuleRowLayout geometry = ModuleRowLayout.of(x, y, w, module.togglable(), module.hasSettings());
                int bodyY = y + geometry.row().h();
                int bodyH = module == editingModule && editor != null ? editor.embeddedHeight(w - 8, query) : 0;
                rows.add(new Placed(module, geometry, bodyY, bodyH));
                if (bodyH > 0) {
                    editor.embeddedBounds(x + 4, bodyY + 3, w - 8, contentTop(), contentBottom(), query);
                    editor.embeddedPopupBounds(panelX() + 4, contentTop(), panelX() + panelW() - 4, contentBottom());
                }
                y = bodyY + bodyH + (bodyH > 0 ? 10 : 4);
            }
            y += 5;
        }
        contentHeight = y + scroll - contentTop() + 8;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        layout();
        g.fill(0, 0, width, height, Draw.alpha(Theme.scrim(), .78f));
        Draw.roundedOutline(g, panelX() - 1, panelY() - 1, panelW() + 2, panelH() + 2,
                Theme.RADIUS_LG + 1, Theme.line(), Theme.bg());
        drawSidebar(g, mouseX, mouseY);
        drawContent(g, mouseX, mouseY);
        drawHeader(g);
        if (editor != null) editor.renderEmbeddedOverlay(g, mouseX, mouseY);
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
        int x = panelX(), y = panelY(), w = sidebarW();
        Draw.roundedRect(g, x, y, w, panelH(), Theme.RADIUS_LG, Theme.surface(), true, false, true, false);
        Draw.rect(g, x + w - 1, y + 8, 1, panelH() - 16, Theme.line());
        Draw.roundedRect(g, x + 15, y + 18, 5, 5, 2, Theme.accent());
        Draw.text(g, font, Draw.fit(font, brand, w - 42), x + 27, y + 16, Theme.text());
        Draw.text(g, font, "MODULES", x + 15, contentTop() + 5, Theme.dim());
        g.enableScissor(x + 1, navTop(), x + w - 1, navBottom());
        try {
            for (Nav item : nav) {
                if (item.y() + 24 <= navTop() || item.y() >= navBottom()) continue;
                boolean selected = java.util.Objects.equals(category, item.category());
                boolean hot = contains(x + 8, item.y(), w - 16, 23, mx, my) && my >= navTop() && my < navBottom();
                float a = anim("nav:" + item.title()).to(selected ? 1 : hot ? .55f : 0, Theme.EASE_FAST);
                if (a > .01f) Draw.roundedRect(g, x + 8, item.y(), w - 16, 23, 5, Draw.alpha(Theme.accent(), .14f * a));
                if (selected) Draw.rect(g, x + 8, item.y() + 6, 2, 11, Theme.accent());
                MenuIcons.draw(g, item.category() == null ? "" : item.category(), x + 19, item.y() + 6,
                        selected ? Theme.accent() : Theme.muted());
                Draw.text(g, font, Draw.fit(font, item.title(), w - 58), x + 38, item.y() + 7,
                        selected ? Theme.accent() : Draw.lerp(Theme.muted(), Theme.text(), a));
            }
        } finally { g.disableScissor(); }
        if (maxNavScroll() > 0) {
            if (navScroll > 0) MenuIcons.arrow(g, x + w - 19, navTop(), MenuIcons.Arrow.UP, Theme.muted());
            if (navScroll < maxNavScroll())
                MenuIcons.arrow(g, x + w - 19, navBottom() - 12, MenuIcons.Arrow.DOWN, Theme.muted());
        }
        int moveY = moveButtonY();
        boolean moveHot = contains(x + 10, moveY, w - 20, 18, mx, my);
        Draw.roundedRect(g, x + 10, moveY, w - 20, 18, 5, moveHot ? Theme.hover() : Theme.raised());
        Draw.textCentered(g, font, "Move readouts", x + w / 2, moveY + 5, moveHot ? Theme.text() : Theme.muted());
        if (compactFooter()) {
            int themeY = panelY() + panelH() - 25;
            boolean themeHot = contains(x + 10, themeY, w - 20, 18, mx, my);
            Draw.roundedRect(g, x + 10, themeY, w - 20, 18, 5, themeHot ? Theme.hover() : Theme.raised());
            Draw.text(g, font, Draw.fit(font, "Theme: " + Theme.palette().name(), w - 34), x + 17, themeY + 5, Theme.muted());
        } else {
            // The shared palette row uses the desktop sidebar width. Clip its
            // separator on narrow windows; all five swatches still fit.
            g.enableScissor(x, y, x + w, y + panelH());
            try { themeRow.draw(g, font, x, mx, my); }
            finally { g.disableScissor(); }
        }
    }

    private void drawHeader(GuiGraphicsExtractor g) {
        Draw.roundedRect(g, contentX(), panelY(), contentW(), HEADER, Theme.RADIUS_LG,
                Theme.bg(), false, true, false, false);
        String title = category == null ? "All modules" : category;
        Draw.text(g, font, Draw.fit(font, title, Math.max(30, contentW() - 94)), contentX() + INSET, panelY() + 12, Theme.text());
        Draw.textRight(g, font, resultCount + (resultCount == 1 ? " module" : " modules"),
                panelX() + panelW() - INSET, panelY() + 12, Theme.dim());
        Draw.roundedOutline(g, searchX(), searchY(), searchW(), 23, 5,
                searchFocused || !query.isEmpty() ? Theme.accent() : Theme.line(), Theme.input());
        String shown = query.isEmpty() ? "Search modules and settings..." : query;
        MenuIcons.search(g, searchX() + 8, searchY() + 5,
                searchFocused || !query.isEmpty() ? Theme.accent() : Theme.muted());
        Draw.text(g, font, Draw.fit(font, shown, searchW() - 49), searchX() + 25, searchY() + 7,
                query.isEmpty() ? Theme.dim() : Theme.text());
        if (!query.isEmpty()) Draw.text(g, font, "x", searchX() + searchW() - 14, searchY() + 7, Theme.muted());
        Draw.rect(g, contentX(), contentTop() - 1, contentW(), 1, Theme.line());
    }

    private void drawContent(GuiGraphicsExtractor g, int mx, int my) {
        g.enableScissor(contentX(), contentTop(), contentX() + contentW(), contentBottom());
        try {
            for (Heading heading : headings) {
                if (heading.y() + 24 <= contentTop() || heading.y() >= contentBottom()) continue;
                Draw.text(g, font, heading.title(), contentX() + INSET + 2, heading.y() + 8, Theme.muted());
                Draw.textRight(g, font, Integer.toString(heading.count()), panelX() + panelW() - INSET - 7,
                        heading.y() + 8, Theme.dim());
            }
            for (Placed placed : rows) {
                ModuleRowLayout.Rect row = placed.geometry().row();
                if (row.y() + row.h() + placed.bodyH() <= contentTop() || row.y() >= contentBottom()) continue;
                drawModule(g, placed, mx, my);
                if (placed.bodyH() > 0 && editor != null) {
                    Draw.roundedRect(g, row.x(), placed.bodyY(), row.w(), placed.bodyH() + 3, 5, Theme.surface());
                    Draw.rect(g, row.x(), placed.bodyY(), 2, placed.bodyH(), Draw.alpha(Theme.accent(), .45f));
                    editor.renderEmbedded(g, mx, my);
                }
            }
            if (resultCount == 0) {
                Draw.textCentered(g, font, "No matching modules or settings", contentX() + contentW() / 2, contentTop() + 42, Theme.muted());
                Draw.textCentered(g, font, "Clear the search or choose another category", contentX() + contentW() / 2, contentTop() + 57, Theme.dim());
            }
        } finally { g.disableScissor(); }
        drawScrollbar(g);
    }

    private void drawModule(GuiGraphicsExtractor g, Placed placed, int mx, int my) {
        Module module = placed.module();
        ModuleRowLayout geometry = placed.geometry();
        ModuleRowLayout.Rect row = geometry.row(), label = geometry.label();
        boolean open = module == editingModule && editor != null;
        boolean hot = row.contains(mx, my) && my >= contentTop() && my < contentBottom();
        float a = anim("row:" + module.id()).to(hot ? 1 : 0, Theme.EASE_FAST);
        Draw.roundedRect(g, row.x(), row.y(), row.w(), row.h(), 5,
                open ? Theme.surface() : Draw.lerp(Theme.bg(), Theme.hover(), a));
        int iconY = row.y() + (geometry.stacked() ? 10 : 15);
        int iconColor = open ? Theme.accent() : module.implemented() ? Theme.muted() : Theme.dim();
        Draw.roundedRect(g, row.x() + 9, iconY - 4, 18, 18, 4,
                open ? Draw.alpha(Theme.accent(), .16f) : Theme.raised());
        MenuIcons.feature(g, module, row.x() + 9, iconY - 4, iconColor);
        Draw.text(g, font, Draw.fit(font, module.title(), label.w()), label.x(), label.y(),
                module.implemented() ? Theme.text() : Theme.dim());
        String description = matchSummaries.getOrDefault(module.id(), module.description());
        Draw.text(g, font, Draw.fit(font, description, label.w()), label.x(), label.y() + 13,
                matchSummaries.containsKey(module.id()) ? Theme.accent() : Theme.muted());
        if (!open) Draw.rect(g, row.x() + 8, row.y() + row.h() - 1, row.w() - 16, 1, Theme.hair());
        if (module.togglable()) {
            ModuleRowLayout.Rect key = geometry.key(), toggle = geometry.toggle();
            boolean keyHot = key.contains(mx, my) && my >= contentTop() && my < contentBottom();
            boolean listening = Keybinds.moduleId(module).equals(binding);
            Keybinds.chip(g, font, Keybinds.moduleId(module), key.x(), key.y(), keyHot || listening ? 1 : 0, listening);
            float on = anim("toggle:" + module.id()).to(module.isEnabled() ? 1 : 0, Theme.EASE_FAST);
            int tx = toggle.x() + 5, ty = toggle.y() + 5;
            Draw.roundedRect(g, tx, ty, 20, 10, 5, Draw.lerp(Theme.line(), Theme.pos(), on));
            Draw.roundedRect(g, tx + 1 + Math.round(10 * on), ty + 1, 8, 8, 4, Theme.text());
        }
        if (module.hasSettings()) {
            ModuleRowLayout.Rect expand = geometry.expand();
            if (expand.contains(mx, my)) Draw.roundedRect(g, expand.x(), expand.y(), expand.w(), expand.h(), 4, Theme.hover());
            Draw.gearIcon(g, font, expand.x() + 10, expand.y() + 10,
                    open ? Theme.accent() : Theme.muted());
            MenuIcons.arrow(g, expand.x() + 19, expand.y() + 4,
                    open ? MenuIcons.Arrow.DOWN : MenuIcons.Arrow.RIGHT,
                    open ? Theme.accent() : Theme.muted());
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor g) {
        int max = maxScroll();
        if (max <= 0) return;
        int track = Math.max(1, contentBottom() - contentTop() - 8);
        int thumb = Math.min(track, Math.max(20, track * track / (track + max)));
        int y = contentTop() + 4 + Math.round((track - thumb) * (scroll / (float) max));
        Draw.roundedRect(g, panelX() + panelW() - 5, contentTop() + 4, 3, track, 1, Theme.hair());
        Draw.roundedRect(g, panelX() + panelW() - 5, y, 3, thumb, 1, Theme.line());
    }

    private void expand(Module module) {
        int anchor = contentTop() + 8;
        for (Placed placed : rows) if (placed.module() == module) anchor = placed.geometry().row().y();
        binding = null;
        expandedId = module.id().equals(expandedId) ? null : module.id();
        ensureEditor(visibleModule(expandedId));
        layout();
        for (Placed placed : rows) if (placed.module() == module) {
            scroll += placed.geometry().row().y() - anchor;
            break;
        }
        layout();
        if (editor != null) for (Placed placed : rows) if (placed.module() == editingModule) {
            int need = placed.bodyY() + Math.min(75, placed.bodyH()) - contentBottom();
            if (need > 0) { scroll += need; layout(); }
            break;
        }
    }

    private void search(String value) {
        query = value; scroll = 0; searchFocused = true;
        if (editor != null) editor.cancelInteractions();
        results();
        if (resultCount == 1) {
            Module only = groups.values().iterator().next().getFirst();
            if (only.hasSettings() && !SettingsSearch.matchingLabels(only, query).isEmpty()) expandedId = only.id();
        }
        layout();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (binding != null && event.button() >= 2) {
            Keybinds.set(binding, Keybinds.mouse(event.button())); binding = null; return true;
        }
        layout();
        int mx = (int) event.x(), my = (int) event.y();
        int previousSettings = editingModule == null || editingModule.settings() == null ? 0 : editingModule.settings().size();
        if (editor != null && editor.mouseClicked(event, doubleClick)) {
            binding = null;
            // New command rows need to be editable immediately even if the search
            // that found their module would not match a freshly empty command.
            if (editingModule.settings().size() > previousSettings) query = "";
            registry.invalidateSearch();
            return true;
        }
        if (contains(searchX(), searchY(), searchW(), 23, mx, my)) {
            binding = null; searchFocused = true;
            if (mx >= searchX() + searchW() - 22) search("");
            return true;
        }
        searchFocused = false;
        if (compactFooter()) {
            if (contains(panelX() + 10, panelY() + panelH() - 25, sidebarW() - 20, 18, mx, my)) {
                List<Palette> palettes = Theme.palettes();
                Theme.set(palettes.get(Math.floorMod(palettes.indexOf(Theme.palette()) + (event.button() == 1 ? -1 : 1), palettes.size())));
                return true;
            }
        } else if (themeRow.click(mx, my)) return true;
        if (contains(panelX() + 10, moveButtonY(), sidebarW() - 20, 18, mx, my)) {
            com.endsight.hud.HudPlacementScreen.open(); return true;
        }
        if (contains(panelX(), navTop(), sidebarW(), navBottom() - navTop(), mx, my)) for (Nav item : nav) {
            if (!contains(panelX() + 8, item.y(), sidebarW() - 16, 23, mx, my)) continue;
            category = item.category(); scroll = 0; binding = null;
            if (editor != null) editor.cancelInteractions();
            layout(); return true;
        }
        if (!contains(contentX(), contentTop(), contentW(), contentBottom() - contentTop(), mx, my))
            return super.mouseClicked(event, doubleClick);
        for (Placed placed : rows) {
            ModuleRowLayout.Target target = placed.geometry().target(mx, my);
            if (target == ModuleRowLayout.Target.NONE) continue;
            Module module = placed.module();
            if (editor != null) editor.cancelInteractions();
            switch (target) {
                case KEY -> {
                    String id = Keybinds.moduleId(module);
                    if (event.button() == 1) { Keybinds.clear(id); binding = null; }
                    else binding = id.equals(binding) ? null : id;
                }
                case TOGGLE -> { binding = null; module.toggle(); }
                case EXPAND, ROW -> { if (module.hasSettings()) expand(module); }
                default -> { }
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (suppressCharacter) { suppressCharacter = false; return true; }
        if (binding != null) return true;
        if (editor != null && editor.capturesKeyboard() && editor.charTyped(event)) return true;
        if (event.isAllowedChatCharacter() && query.length() < 120) { search(query + event.codepointAsString()); return true; }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (binding != null) {
            if (event.key() == 256) Keybinds.clear(binding); else Keybinds.set(binding, event.key());
            binding = null; suppressCharacter = true; return true;
        }
        if (event.key() == 70 && (event.modifiers() & 2) != 0) {
            searchFocused = true;
            if (editor != null) editor.cancelInteractions();
            return true;
        }
        if (editor != null && editor.keyPressed(event)) {
            if (!editor.capturesKeyboard()) registry.invalidateSearch();
            return true;
        }
        if (event.key() == 259 && !query.isEmpty()) {
            search(query.substring(0, query.offsetByCodePoints(query.length(), -1))); return true;
        }
        if (event.key() == 256) {
            if (!query.isEmpty()) { search(""); return true; }
            if (editor != null) { expandedId = null; ensureEditor(null); layout(); return true; }
        }
        if (event.key() == 258) { searchFocused = true; return true; }
        if ((event.key() == 257 || event.key() == 335) && resultCount > 0 && !query.isEmpty()) {
            Module first = groups.values().iterator().next().getFirst();
            if (first.hasSettings() && !first.id().equals(expandedId)) expand(first);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return editor != null && editor.mouseDragged(event, dx, dy) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return editor != null && editor.mouseReleased(event) || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        layout();
        if (contains(panelX(), navTop(), sidebarW(), navBottom() - navTop(), (int) mouseX, (int) mouseY)) {
            navScroll = Math.max(0, Math.min(navScroll + (int) (-scrollY * NAV_PITCH), maxNavScroll()));
        } else scrollBy((int) (-scrollY * 28));
        return true;
    }

    public void scrollBy(int amount) {
        scroll = Math.max(0, Math.min(scroll + amount, maxScroll()));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private static boolean contains(int x, int y, int w, int h, int px, int py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
