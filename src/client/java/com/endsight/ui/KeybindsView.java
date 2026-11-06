package com.endsight.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Every key in the mod on one page: what is bound first, everything else a click away.
 *
 * Keys live on the page of whatever they trigger, which is the right place to set one and
 * the wrong place to ask "what did I put on G". This answers that without touching any of
 * them, and setting a key here is the same chip as anywhere else.
 */
final class KeybindsView {
    private static final int ROW_H = 30, GAP = 4, TAB_H = 18, TAB_W = 76, CAPTION = 18, MIN_W = 186, RADIUS = 6;
    /** Which list was open, kept when the menu closes: the two lists are the same page to a player. */
    private static boolean showAll;

    /** One placed line: a tile, or a category heading when {@code heading} is set. Relative to the page. */
    private record Cell(int x, int y, int w, Setting.Bind bind, String heading) { }
    private List<Cell> cells = List.of();
    private int height, bound;
    // What the cells were built from. Rows here run to eighty, so they are rebuilt on a change
    // and only those on screen are drawn: at scroll the rest would be spent on nothing.
    private List<Setting.Bind> builtFrom;
    private Object builtKeys;
    private boolean builtAll;
    private String builtQuery = "";
    private int builtWidth;

    private void build(Setting.Binds binds, int w, String query) {
        List<Setting.Bind> all = binds.binds().get();
        Object keys = Keybinds.all();
        if (all == builtFrom && keys == builtKeys && showAll == builtAll && w == builtWidth && query.equals(builtQuery)) return;
        builtFrom = all; builtKeys = keys; builtAll = showAll; builtWidth = w; builtQuery = query;

        String find = SettingsSearch.normalize(query);
        boolean grouped = showAll || !find.isEmpty();
        int columns = Math.max(1, (w + GAP) / (MIN_W + GAP));
        int tileW = (w - GAP * (columns - 1)) / columns;
        List<Cell> next = new ArrayList<>();
        int y = TAB_H + 8, column = 0;
        String group = null;
        bound = 0;
        for (Setting.Bind bind : all) {
            boolean set = Keybinds.get(bind.id()) != Keybinds.NONE;
            if (set) bound++;
            if (!find.isEmpty() ? !SettingsSearch.matches(find, bind.name() + " " + bind.where() + " " + bind.group())
                    : !showAll && !set) continue;
            if (grouped && !bind.group().equals(group)) {
                if (column != 0) { y += ROW_H + GAP; column = 0; }
                if (group != null) y += 4;
                group = bind.group();
                next.add(new Cell(0, y, w, null, group));
                y += CAPTION;
            }
            next.add(new Cell(column * (tileW + GAP), y, tileW, bind, null));
            if (++column == columns) { column = 0; y += ROW_H + GAP; }
        }
        if (column != 0) y += ROW_H + GAP;
        cells = next;
        height = next.isEmpty() ? TAB_H + 8 + 54 : y;
    }

    int height(Setting.Binds binds, int w, String query) {
        build(binds, w, query);
        return height;
    }

    /** How many keys are set, for the page header. */
    static int boundCount(List<Setting.Bind> binds) {
        int count = 0;
        for (Setting.Bind bind : binds) if (Keybinds.get(bind.id()) != Keybinds.NONE) count++;
        return count;
    }

    private void tab(GuiGraphicsExtractor g, Font font, String label, int x, int y, boolean selected, int mx, int my) {
        boolean hot = mx >= x && mx < x + TAB_W && my >= y && my < y + TAB_H;
        Draw.roundedRect(g, x, y, TAB_W, TAB_H, RADIUS - 2,
                selected ? Draw.alpha(Theme.accent(), .2f) : hot ? Theme.hover() : Theme.raised());
        Draw.textCentered(g, font, label, x + TAB_W / 2, y + 5, selected ? Theme.accent() : Theme.muted());
    }

    void draw(GuiGraphicsExtractor g, Font font, Setting.Binds binds, int x, int y, int w, String binding,
              String query, int top, int bottom, int mx, int my) {
        build(binds, w, query);
        boolean searching = !query.isBlank();
        tab(g, font, "Bound " + bound, x, y, !showAll && !searching, mx, my);
        tab(g, font, "All " + builtFrom.size(), x + TAB_W + GAP, y, showAll && !searching, mx, my);
        if (cells.isEmpty()) {
            Draw.roundedRect(g, x, y + TAB_H + 8, w, 46, RADIUS, Theme.raised());
            Draw.textCentered(g, font, searching ? "No matching keys" : "No keys bound yet", x + w / 2, y + TAB_H + 15, Theme.muted());
            Draw.textCentered(g, font, searching ? "Try another search" : "Open All to set one", x + w / 2, y + TAB_H + 29, Theme.dim());
            return;
        }
        for (Cell cell : cells) {
            int cy = y + cell.y();
            if (cy + ROW_H <= top || cy >= bottom) continue;
            if (cell.heading() != null) {
                Draw.text(g, font, cell.heading(), x, cy + 3, Theme.muted());
                continue;
            }
            int cx = x + cell.x();
            Setting.Bind bind = cell.bind();
            boolean hot = mx >= cx && mx < cx + cell.w() && my >= cy && my < cy + ROW_H && my >= top && my < bottom;
            Draw.roundedRect(g, cx, cy, cell.w(), ROW_H, RADIUS, hot ? Theme.hover() : Theme.raised());
            int chipX = cx + cell.w() - Keybinds.CHIP_W - 7, textW = chipX - cx - 13;
            if (bind.where().isEmpty()) Draw.text(g, font, Draw.fit(font, bind.name(), textW), cx + 8, cy + 11, Theme.text());
            else {
                Draw.text(g, font, Draw.fit(font, bind.name(), textW), cx + 8, cy + 5, Theme.text());
                Draw.text(g, font, Draw.fit(font, bind.where(), textW), cx + 8, cy + 17, Theme.muted());
            }
            boolean listening = bind.id().equals(binding);
            Keybinds.chip(g, font, bind.id(), chipX, cy + (ROW_H - Keybinds.CHIP_H) / 2, listening || hot ? 1f : 0f, listening, true);
        }
    }

    /** The key id a click wants set, or null. Like any chip, right-click clears. */
    String click(Setting.Binds binds, int x, int y, int w, String query, int mx, int my) {
        build(binds, w, query);
        if (my >= y && my < y + TAB_H) {
            if (mx >= x && mx < x + TAB_W) showAll = false;
            else if (mx >= x + TAB_W + GAP && mx < x + TAB_W * 2 + GAP) showAll = true;
            return null;
        }
        for (Cell cell : cells) {
            if (cell.bind() == null) continue;
            int cx = x + cell.x(), cy = y + cell.y();
            if (mx >= cx && mx < cx + cell.w() && my >= cy && my < cy + ROW_H) return cell.bind().id();
        }
        return null;
    }
}
