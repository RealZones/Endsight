package com.endsight.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A pets or wardrobe page: the swap bars, then a tile for everything you own.
 *
 * Hit testing goes through {@link Geometry} the same as drawing, so the two cannot
 * disagree. Nothing is stored between frames but what is being chosen and the hover fades.
 */
final class LoadoutView {
    private static final int TILE_H = 36, TILE_MIN_W = 186, GAP = 4, RADIUS = 6, PAD = 4, PICTURE = 28;
    private static final int CAPTION_H = 14, SECTION_GAP = 12, ARROWS_W = 22, EMPTY_H = 58;
    private static final int LOAD_W = 66, LOAD_H = 16, SLOT = 18, ADD_W = 13, REMOVE_W = 9;
    /** From here a list is long enough to say that the search box finds things in it. */
    private static final int MANY = 16;
    private static final String FIND = "Search above to find one";

    record Rect(int x, int y, int w, int h) {
        boolean contains(int px, int py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }

    /** How tall a page is. Layout asks several times a frame, and it needs no rectangles for this. */
    static int height(int w, int swaps, int count) {
        int top = CAPTION_H + swaps * (TILE_H + GAP) - GAP + SECTION_GAP + CAPTION_H;
        if (count == 0) return top + EMPTY_H + GAP;
        int columns = Math.max(1, (w + GAP) / (TILE_MIN_W + GAP));
        return top + (count + columns - 1) / columns * (TILE_H + GAP);
    }

    /** One swap's row. {@code remove} is null on the first: a page always offers a swap. */
    record Bar(Rect row, Rect first, Rect second, Rect key, Rect remove) { }

    /** Where everything goes on one page. Pure, so it can be checked without a game. */
    record Geometry(List<Bar> bars, Rect add, Rect load, Rect empty, List<Rect> tiles, int gridCaptionY, int height) {
        static Geometry of(int x, int y, int w, int swaps, int count) {
            int side = Math.max(40, (w - Keybinds.CHIP_W - 8 - ARROWS_W) / 2);
            List<Bar> bars = new ArrayList<>(swaps);
            for (int i = 0; i < swaps; i++) {
                int barY = y + CAPTION_H + i * (TILE_H + GAP);
                bars.add(new Bar(new Rect(x, barY, w, TILE_H), new Rect(x, barY, side, TILE_H),
                        new Rect(x + side + ARROWS_W, barY, side, TILE_H),
                        new Rect(x + w - Keybinds.CHIP_W, barY + (TILE_H - Keybinds.CHIP_H) / 2, Keybinds.CHIP_W, Keybinds.CHIP_H),
                        i == 0 ? null : new Rect(x + w - REMOVE_W, barY, REMOVE_W, REMOVE_W)));
            }
            // On the caption's own line, in the corner: the page looks the same to anyone who
            // never wants a second swap, and adding one pushes nothing they already use.
            Rect add = new Rect(x + w - ADD_W, y - 1, ADD_W, CAPTION_H - 2);

            int captionY = y + CAPTION_H + swaps * (TILE_H + GAP) - GAP + SECTION_GAP, gridY = captionY + CAPTION_H;
            if (count == 0) {
                Rect empty = new Rect(x, gridY, w, EMPTY_H);
                Rect load = new Rect(x + (w - LOAD_W) / 2, gridY + EMPTY_H - LOAD_H - 10, LOAD_W, LOAD_H);
                return new Geometry(bars, add, load, empty, List.of(), captionY, gridY + EMPTY_H + GAP - y);
            }
            int columns = Math.max(1, (w + GAP) / (TILE_MIN_W + GAP));
            int tileW = (w - GAP * (columns - 1)) / columns;
            List<Rect> tiles = new ArrayList<>(count);
            for (int i = 0; i < count; i++)
                tiles.add(new Rect(x + i % columns * (tileW + GAP), gridY + i / columns * (TILE_H + GAP), tileW, TILE_H));
            int rows = (count + columns - 1) / columns;
            Rect load = new Rect(x + w - LOAD_W, captionY - 3, LOAD_W, LOAD_H);
            return new Geometry(bars, add, load, null, tiles, captionY, gridY + rows * (TILE_H + GAP) - y);
        }
    }

    private final Map<String, Anim> anims = new HashMap<>();
    /** Which swap side the next tile click fills, or -1, and on which swap. */
    private int choosing = -1, choosingBar;
    private Geometry placed;
    private int placedX, placedY, placedW, placedSwaps, placedCount;
    private List<Setting.Tile> shownFrom, shown = List.of();
    private String shownQuery = "";

    /** The last placement again while nothing has moved: a list of rectangles a frame was all garbage. */
    private Geometry place(int x, int y, int w, int swaps, int count) {
        if (placed == null || x != placedX || y != placedY || w != placedW || swaps != placedSwaps || count != placedCount) {
            placed = Geometry.of(x, y, w, swaps, count);
            placedX = x; placedY = y; placedW = w; placedSwaps = swaps; placedCount = count;
        }
        return placed;
    }

    /**
     * The tiles a search leaves, for someone with more pets than a page can show. The list runs
     * to hundreds, and the search box above is already there. A search that matches none of
     * them was for the page itself, "pets" or "swap", so it leaves them all up.
     */
    List<Setting.Tile> shown(Setting.Loadout loadout, String query) {
        List<Setting.Tile> all = loadout.tiles().get();
        if (all == shownFrom && query.equals(shownQuery)) return shown;
        shownFrom = all;
        shownQuery = query;
        String find = SettingsSearch.normalize(query);
        if (find.isEmpty()) return shown = all;
        List<Setting.Tile> found = new ArrayList<>();
        for (Setting.Tile tile : all) if (SettingsSearch.matches(find, tile.name() + " " + tile.detail())) found.add(tile);
        return shown = found.isEmpty() ? all : found;
    }

    boolean choosing() { return choosing >= 0; }
    void stopChoosing() { choosing = -1; }

    private float fade(String key, boolean on) {
        return anims.computeIfAbsent(key, ignored -> new Anim(0f)).to(on ? 1f : 0f, Theme.EASE_FAST);
    }

    private static Setting.Tile find(List<Setting.Tile> tiles, String id) {
        for (Setting.Tile tile : tiles) if (tile.id().equals(id)) return tile;
        return null;
    }

    void draw(GuiGraphicsExtractor g, Font font, Setting.Loadout loadout, int x, int y, int w, String binding,
              String query, int top, int bottom, int mx, int my) {
        // The page runs on under the header and off the bottom: nothing there can be pointed at.
        if (my < top || my >= bottom) { mx = -1; my = -1; }
        List<Setting.Tile> all = loadout.tiles().get(), tiles = shown(loadout, query);
        List<Setting.Swap> swaps = loadout.swaps().get();
        Geometry at = place(x, y, w, swaps.size(), tiles.size());

        // Both sides picked looks finished, so a swap with no key got tested with the key on a
        // tile instead: that key puts one outfit on, and did nothing when it already was.
        boolean keyless = false;
        for (Setting.Swap swap : swaps) {
            List<String> chosen = swap.chosen().get();
            keyless |= !swap.key().equals(binding) && Keybinds.get(swap.key()) == Keybinds.NONE
                    && !chosen.get(0).isEmpty() && !chosen.get(1).isEmpty();
        }
        Draw.text(g, font, "Swap key", x, y + 1, Theme.text());
        Draw.text(g, font, choosing >= 0 ? "Now click a " + loadout.noun() : keyless ? "Set a key to use it" : "Flips between these two",
                x + font.width("Swap key") + 8, y + 1, choosing >= 0 || keyless ? Theme.accent() : Theme.dim());
        if (swaps.size() < Setting.Loadout.MAX_SWAPS) add(g, font, at.add(), mx, my);
        for (int i = 0; i < swaps.size(); i++) bar(g, font, at.bars().get(i), swaps.get(i), i, all, loadout, binding, mx, my);

        Draw.text(g, font, loadout.label(), x, at.gridCaptionY() + 1, Theme.text());
        if (at.empty() != null) {
            Draw.roundedRect(g, at.empty().x(), at.empty().y(), at.empty().w(), at.empty().h(), RADIUS, Theme.raised());
            Draw.textCentered(g, font, "Nothing loaded yet", x + w / 2, at.empty().y() + 11, Theme.muted());
        } else {
            boolean filtered = tiles.size() != all.size();
            String count = filtered ? tiles.size() + " of " + all.size() : Integer.toString(all.size());
            int countX = x + font.width(loadout.label()) + 6, hintX = countX + font.width(count) + 8;
            Draw.text(g, font, count, countX, at.gridCaptionY() + 1, Theme.dim());
            if (!filtered && all.size() >= MANY && hintX + font.width(FIND) < at.load().x() - 4)
                Draw.text(g, font, FIND, hintX, at.gridCaptionY() + 1, Theme.dim());
        }
        load(g, font, at, mx, my);

        int row = Integer.MIN_VALUE;
        for (int i = 0; i < tiles.size(); i++) {
            Rect r = at.tiles().get(i);
            // Only what can be seen: with hundreds of pets the rest was a megabyte of pictures
            // and text a frame, all thrown away at the edge of the page.
            if (r.y() + r.h() <= top) continue;
            if (r.y() >= bottom) break;
            // Rows never overlap. See LiveStorageView.drawPages for what that buys.
            if (r.y() != row) { row = r.y(); g.nextStratum(); }
            tile(g, font, r, tiles.get(i), binding, mx, my);
        }
    }

    /** Small and quiet on purpose: it is there for the few who want a second swap. */
    private void add(GuiGraphicsExtractor g, Font font, Rect r, int mx, int my) {
        boolean hot = r.contains(mx, my);
        if (hot) {
            Draw.roundedRect(g, r.x(), r.y(), r.w(), r.h(), 3, Theme.hover());
            g.setTooltipForNextFrame(font, Component.literal("Add another swap"), mx, my);
        }
        Draw.textCentered(g, font, "+", r.x() + r.w() / 2, r.y() + 2, hot ? Theme.accent() : Theme.muted());
    }

    private void bar(GuiGraphicsExtractor g, Font font, Bar bar, Setting.Swap swap, int index, List<Setting.Tile> all,
                     Setting.Loadout loadout, String binding, int mx, int my) {
        List<String> chosen = swap.chosen().get();
        side(g, font, bar.first(), find(all, chosen.get(0)), index, 0, loadout, mx, my);
        side(g, font, bar.second(), find(all, chosen.get(1)), index, 1, loadout, mx, my);
        int arrowX = bar.first().x() + bar.first().w() + (ARROWS_W - 12) / 2;
        MenuIcons.arrow(g, arrowX, bar.first().y() + 5, MenuIcons.Arrow.RIGHT, Theme.muted());
        MenuIcons.arrow(g, arrowX, bar.first().y() + 19, MenuIcons.Arrow.LEFT, Theme.muted());
        boolean listening = swap.key().equals(binding);
        Keybinds.chip(g, font, swap.key(), bar.key().x(), bar.key().y(),
                fade(swap.key(), listening || bar.key().contains(mx, my)), listening, true);
        // Only under the cursor, so a page of swaps is not a page of little crosses.
        if (bar.remove() != null && bar.row().contains(mx, my)) {
            Rect r = bar.remove();
            Draw.textCentered(g, font, "×", r.x() + r.w() / 2, r.y() + 1, r.contains(mx, my) ? Theme.neg() : Theme.dim());
        }
    }

    /** A verb in the accent colour, the same language as a settings row's action button. */
    private void load(GuiGraphicsExtractor g, Font font, Geometry at, int mx, int my) {
        Rect r = at.load();
        float a = fade("load", r.contains(mx, my));
        Draw.roundedRect(g, r.x(), r.y(), r.w(), r.h(), RADIUS - 2, Draw.lerp(Draw.alpha(Theme.accent(), 0.18f), Theme.accent(), a));
        Draw.textCentered(g, font, at.empty() != null ? "Load" : "Refresh", r.x() + r.w() / 2, r.y() + 4, Draw.lerp(Theme.accent(), Theme.bg(), a));
    }

    private void side(GuiGraphicsExtractor g, Font font, Rect r, Setting.Tile tile, int bar, int side,
                      Setting.Loadout loadout, int mx, int my) {
        boolean armed = choosing == side && choosingBar == bar, hot = r.contains(mx, my);
        float a = fade((side == 0 ? "first" : "second") + bar, hot);
        if (tile == null) {
            Draw.roundedOutline(g, r.x(), r.y(), r.w(), r.h(), RADIUS, armed ? Theme.accent() : Draw.lerp(Theme.line(), Theme.muted(), a), Theme.surface());
            Draw.textCentered(g, font, armed ? "Choosing" : "Choose a " + loadout.noun(), r.x() + r.w() / 2, r.y() + TILE_H / 2 - 4,
                    armed ? Theme.accent() : Draw.lerp(Theme.dim(), Theme.text(), a));
            return;
        }
        if (armed) Draw.roundedOutline(g, r.x(), r.y(), r.w(), r.h(), RADIUS, Theme.accent(), Theme.raised());
        else Draw.roundedRect(g, r.x(), r.y(), r.w(), r.h(), RADIUS, Draw.lerp(Theme.raised(), Theme.hover(), a));
        int textX = picture(g, tile, r.x() + PAD, r.y() + PAD);
        words(g, font, tile, textX, r.y(), r.x() + r.w() - PAD - textX);
    }

    private void tile(GuiGraphicsExtractor g, Font font, Rect r, Setting.Tile tile, String binding, int mx, int my) {
        boolean hot = r.contains(mx, my), picking = choosing >= 0;
        float a = fade(tile.id(), hot);
        if (picking && hot) Draw.roundedOutline(g, r.x(), r.y(), r.w(), r.h(), RADIUS, Theme.accent(), Theme.hover());
        else Draw.roundedRect(g, r.x(), r.y(), r.w(), r.h(), RADIUS, Draw.lerp(Theme.raised(), Theme.hover(), a));
        int textX = picture(g, tile, r.x() + PAD, r.y() + PAD);
        int chipX = r.x() + r.w() - Keybinds.CHIP_W - PAD - 2;
        words(g, font, tile, textX, r.y(), chipX - 4 - textX);
        boolean listening = tile.id().equals(binding);
        Keybinds.chip(g, font, tile.id(), chipX, r.y() + (TILE_H - Keybinds.CHIP_H) / 2, listening || hot ? 1f : 0f, listening, true);
    }

    /** The name and, under it, one line of detail; none at all where only an ellipsis would fit. */
    private static void words(GuiGraphicsExtractor g, Font font, Setting.Tile tile, int x, int y, int width) {
        if (width < 24) return;
        int name = tile.color() == 0 ? Theme.text() : 0xFF000000 | tile.color();
        boolean single = tile.detail().isEmpty();
        Draw.text(g, font, Draw.fit(font, tile.name(), width), x, y + (single ? 14 : 8), name);
        if (!single) Draw.text(g, font, Draw.fit(font, tile.detail(), width), x, y + 20, tile.current() ? Theme.pos() : Theme.muted());
    }

    /**
     * One head large in a well tinted by its rarity, or a row of armour slots.
     *
     * The head is scaled rather than drawn at 16: item models are rendered at whatever
     * size they end up, so this stays sharp, and at 16 a skin was too small to tell apart.
     * Returns where the text starts.
     */
    private static int picture(GuiGraphicsExtractor g, Setting.Tile tile, int x, int y) {
        List<ItemStack> icons = tile.icons();
        if (icons.size() == 1) {
            Draw.roundedRect(g, x, y, PICTURE, PICTURE, RADIUS - 1,
                    Draw.alpha(tile.color() == 0 ? Theme.muted() : 0xFF000000 | tile.color(), tile.current() ? .32f : .16f));
            ItemStack head = icons.getFirst();
            if (!head.isEmpty()) {
                var pose = g.pose();
                pose.pushMatrix();
                pose.translate(x + 2, y + 2);
                pose.scale(1.5f);
                g.item(head, 0, 0);
                pose.popMatrix();
            }
            return x + PICTURE + 7;
        }
        int slotY = y + (PICTURE - SLOT) / 2;
        for (int i = 0; i < icons.size(); i++) {
            int slotX = x + i * (SLOT + 1);
            Draw.roundedRect(g, slotX, slotY, SLOT, SLOT, RADIUS - 2, tile.current() ? Draw.alpha(Theme.pos(), .16f) : Theme.bg());
            if (!icons.get(i).isEmpty()) g.item(icons.get(i), slotX + 1, slotY + 1);
        }
        return x + icons.size() * (SLOT + 1) + 6;
    }

    /**
     * Acts on a click, or returns the key id the click wants bound.
     *
     * A tile is one target, not a chip inside a card: the whole thing means this pet, and
     * right-click clears as it does on every chip in the mod.
     */
    String click(Setting.Loadout loadout, int x, int y, int w, String query, int mx, int my, int button) {
        List<Setting.Tile> tiles = shown(loadout, query);
        List<Setting.Swap> swaps = loadout.swaps().get();
        Geometry at = place(x, y, w, swaps.size(), tiles.size());

        for (int i = 0; i < swaps.size(); i++) {
            Bar bar = at.bars().get(i);
            Setting.Swap swap = swaps.get(i);
            if (bar.remove() != null && bar.remove().contains(mx, my)) {
                stopChoosing();
                swap.remove().run();
                return null;
            }
            for (int side = 0; side < 2; side++) {
                if (!(side == 0 ? bar.first() : bar.second()).contains(mx, my)) continue;
                if (button == 1) { swap.choose().accept(side, ""); stopChoosing(); }
                else {
                    choosing = choosing == side && choosingBar == i ? -1 : side;
                    choosingBar = i;
                }
                return null;
            }
            if (bar.key().contains(mx, my)) { stopChoosing(); return swap.key(); }
        }
        if (swaps.size() < Setting.Loadout.MAX_SWAPS && at.add().contains(mx, my)) {
            loadout.addSwap().run();
            // A new swap has one thing to do next, so the page is already doing it.
            choosing = 0;
            choosingBar = swaps.size();
            return null;
        }
        if (at.load().contains(mx, my)) { stopChoosing(); loadout.load().run(); return null; }
        for (int i = 0; i < tiles.size(); i++) {
            Setting.Tile tile = tiles.get(i);
            if (!at.tiles().get(i).contains(mx, my)) continue;
            if (choosing < 0) return tile.id();
            pick(swaps.get(choosingBar), tile.id());
            return null;
        }
        stopChoosing();
        return null;
    }

    /** Filling the first side moves straight on to an empty second: a swap always needs both. */
    private void pick(Setting.Swap swap, String id) {
        int other = 1 - choosing;
        List<String> chosen = swap.chosen().get();
        if (id.equals(chosen.get(other))) swap.choose().accept(other, "");
        swap.choose().accept(choosing, id);
        boolean advance = choosing == 0 && swap.chosen().get().get(1).isEmpty();
        choosing = advance ? 1 : -1;
    }
}
