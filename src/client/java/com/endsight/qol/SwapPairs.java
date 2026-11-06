package com.endsight.qol;

import com.endsight.ui.Keybinds;
import com.endsight.ui.Setting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The swaps on a Loadouts page: pairs of tiles, each pair with its own key.
 *
 * Here and not in the two pages because pets and outfits differ only in what a swap does.
 * Each pair keeps the key id it was made with, so taking one out of the middle cannot
 * move another's key on to the wrong pair.
 */
final class SwapPairs {
    private static final class Pair {
        final String key;
        String first = "", second = "";
        Pair(String key) { this.key = key; }
    }

    private final String key;
    private final BiConsumer<String, String> swap;
    private final Runnable save;
    private final List<Pair> pairs = new ArrayList<>();
    private List<Setting.Swap> list;

    /** @param swap told the pair's two ids, in the order they were chosen */
    SwapPairs(String key, BiConsumer<String, String> swap, Runnable save) {
        this.key = key;
        this.swap = swap;
        this.save = save;
        pairs.add(new Pair(key));
    }

    /** Built once per change: the page asks every frame. */
    List<Setting.Swap> list() {
        if (list != null) return list;
        List<Setting.Swap> out = new ArrayList<>(pairs.size());
        for (Pair pair : pairs) {
            out.add(new Setting.Swap(pair.key, () -> List.of(pair.first, pair.second), (side, id) -> {
                if (side == 0) pair.first = id; else pair.second = id;
                save.run();
            }, () -> swap.accept(pair.first, pair.second), pair == pairs.getFirst() ? null : () -> remove(pair)));
        }
        return list = List.copyOf(out);
    }

    void add() {
        if (pairs.size() >= Setting.Loadout.MAX_SWAPS) return;
        int n = 2;
        while (taken(key + "." + n)) n++;
        pairs.add(new Pair(key + "." + n));
        list = null;
        save.run();
    }

    private boolean taken(String id) {
        for (Pair pair : pairs) if (pair.key.equals(id)) return true;
        return false;
    }

    private void remove(Pair pair) {
        pairs.remove(pair);
        Keybinds.clear(pair.key);
        list = null;
        save.run();
    }

    /** The first pair stays at the top of the file, where a save from before there could be more has it. */
    void read(CompoundTag root) {
        Pair main = new Pair(key);
        main.first = root.getStringOr("first", "");
        main.second = root.getStringOr("second", "");
        pairs.clear();
        pairs.add(main);
        for (CompoundTag tag : root.getListOrEmpty("swaps").compoundStream().toList()) {
            String id = tag.getStringOr("key", "");
            if (id.isEmpty() || taken(id) || pairs.size() >= Setting.Loadout.MAX_SWAPS) continue;
            Pair pair = new Pair(id);
            pair.first = tag.getStringOr("first", "");
            pair.second = tag.getStringOr("second", "");
            pairs.add(pair);
        }
        list = null;
    }

    void write(CompoundTag root) {
        root.putString("first", pairs.getFirst().first);
        root.putString("second", pairs.getFirst().second);
        ListTag more = new ListTag();
        for (Pair pair : pairs.subList(1, pairs.size())) {
            CompoundTag tag = new CompoundTag();
            tag.putString("key", pair.key);
            tag.putString("first", pair.first);
            tag.putString("second", pair.second);
            more.add(tag);
        }
        root.put("swaps", more);
    }
}
