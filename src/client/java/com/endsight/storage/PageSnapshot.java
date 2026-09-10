package com.endsight.storage;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * One storage page as it looked the last time it was open.
 *
 * Stacks are copied rather than referenced. A {@link net.minecraft.world.inventory.Slot}
 * keeps pointing at a live container, and the client reuses container objects between
 * screens - hold the slot and the "snapshot" quietly becomes whatever is open now,
 * which looks like the feature working right up until you open a second page.
 *
 * @param page      which page this is, from the window title.
 * @param pageCount how many pages the title claimed, so a preview can say 3/7.
 * @param items     the container's slots in order, empties included. Empties are kept
 *                  because the grid has to draw holes where holes were - a compacted
 *                  list would show a full page that isn't.
 */
public record PageSnapshot(int page, int pageCount, List<ItemStack> items, long capturedAt) {

    public static final int COLS = 9;

    public int rows() {
        return Math.max(1, (items.size() + COLS - 1) / COLS);
    }

    public ItemStack at(int index) {
        return index >= 0 && index < items.size() ? items.get(index) : ItemStack.EMPTY;
    }

    public int filled() {
        int n = 0;
        for (ItemStack s : items) if (!s.isEmpty()) n++;
        return n;
    }

    /** Rough age, for the header. Precision past a minute is noise on a stash. */
    public String age(long now) {
        long secs = Math.max(0, (now - capturedAt) / 1000L);
        if (secs < 5) return "just now";
        if (secs < 60) return secs + "s ago";
        if (secs < 3600) return (secs / 60) + "m ago";
        return (secs / 3600) + "h ago";
    }
}
