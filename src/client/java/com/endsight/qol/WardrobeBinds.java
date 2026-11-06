package com.endsight.qol;

import com.endsight.ui.Keybinds;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Manual outfit keys target the server's labelled selectors, never the armour above them. */
public final class WardrobeBinds {
    private WardrobeBinds() { }
    private static final String ID = "qol.wardrobe", SWAP_KEY = ID + ".swap", FILE = "wardrobe-binds";
    private static final int OUTFITS = 9;
    private static final EquipmentSlot[] WORN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Pattern SLOT = Pattern.compile("(?i)^Slot\\s+(\\d{1,2})$");
    private static boolean initialized;
    private static String scope = "";
    private static final SwapPairs swaps = new SwapPairs(SWAP_KEY, WardrobeBinds::swap, WardrobeBinds::write);
    private static final Outfit[] outfits = new Outfit[OUTFITS];
    private static List<Setting.Tile> tiles = List.of();
    private static final Set<Integer> held = new HashSet<>();
    private static final MenuBindRequest.Settled settled = new MenuBindRequest.Settled();
    /** The last screen looked at, so a title is parsed once per menu and not once per tick. */
    private static AbstractContainerScreen<?> watched;
    private static boolean watchedIsWardrobe;

    /** The pieces one wardrobe slot holds, top to bottom, as the server last showed them. */
    record Outfit(List<ItemStack> pieces, State state) { }

    public static Module module() {
        return Module.group(ID, "Wardrobe Binds", "A key for each outfit, and one that flips between two.", "Quality of Life",
                List.of(new Setting.Loadout("Your outfits", "outfit", WardrobeBinds::tiles,
                        swaps::list, swaps::add, WardrobeBinds::refresh)));
    }
    public static void init() {
        if (initialized) return;
        initialized = true;
        MenuBindRequest.init();
        rebuild();
        MenuBindRequest.menuKeys(WardrobeBinds::menu, WardrobeBinds::actions, held);
        ClientTickEvents.END_CLIENT_TICK.register(WardrobeBinds::tick);
    }
    private static String key(int outfit) { return ID + ".slot." + outfit; }
    private static int outfit(String id) {
        for (int i = 1; i <= OUTFITS; i++) if (key(i).equals(id)) return i;
        return 0;
    }
    static boolean wardrobeTitle(String title) { return MenuBindRequest.plain(title).matches("(?i)^Wardrobe(?:\\s*\\(\\d+\\s*/\\d+\\))?$"); }
    private static boolean menu(AbstractContainerScreen<?> screen) { return wardrobeTitle(screen.getTitle().getString()); }

    private static List<Setting.Tile> tiles() {
        ensureScope(Minecraft.getInstance());
        return tiles;
    }
    private static Map<String, Runnable> actions() {
        Map<String, Runnable> actions = new LinkedHashMap<>();
        for (Setting.Swap swap : swaps.list()) actions.put(swap.key(), swap.run());
        for (Setting.Tile tile : tiles) if (Keybinds.get(tile.id()) != Keybinds.NONE) actions.put(tile.id(), tile.run());
        return actions;
    }

    public static void request(int outfit) {
        if (outfit < 1 || outfit > OUTFITS) return;
        MenuBindRequest.start("Wardrobe Binds", "wardrobe", WardrobeBinds::menu, 1_100, (screen, sent) -> {
            MenuBindRequest.Decision decision = finish(screen, entries(screen), outfit, sent);
            // Clicking it would take it off. Saying nothing made a press on the outfit you
            // are wearing look like a swap that failed: the menu just opened and went away.
            if (decision.kind() == MenuBindRequest.Kind.DONE && !sent) MenuBindRequest.tell("Slot " + outfit + " is already on.");
            return decision;
        });
    }

    /** One key, two outfits. The menu says which is on, so a swap survives a manual change. */
    private static void swap(String first, String second) {
        int a = outfit(first), b = outfit(second);
        if (a == 0 || b == 0) { MenuBindRequest.tell("Choose two outfits in Loadouts first."); return; }
        int[] target = {0};
        MenuBindRequest.start("Wardrobe Binds", "wardrobe", WardrobeBinds::menu, 1_100, (screen, sent) -> {
            List<OutfitEntry> entries = entries(screen);
            // Decided once: after the click the other one reads as equipped, and asking
            // again would click straight back.
            if (target[0] == 0) target[0] = swapTarget(entries, a, b);
            return target[0] == 0 ? MenuBindRequest.Decision.waitForItems() : finish(screen, entries, target[0], sent);
        });
    }

    private static void refresh() {
        ensureScope(Minecraft.getInstance());
        MenuBindRequest.refresh("Wardrobe Binds", "wardrobe", WardrobeBinds::menu, (screen, sent) -> {
            learn(screen);
            return MenuBindRequest.Decision.done();
        });
    }

    /** The menu closes on a finished request, too soon to settle, so "Equipped" stayed on the old outfit. */
    private static MenuBindRequest.Decision finish(AbstractContainerScreen<?> screen, List<OutfitEntry> entries, int outfit, boolean sent) {
        MenuBindRequest.Decision decision = choose(entries, outfit, sent);
        if (decision.kind() == MenuBindRequest.Kind.DONE) learn(screen);
        return decision;
    }

    private static List<OutfitEntry> entries(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        List<OutfitEntry> entries = new ArrayList<>();
        for (var slot : screen.getMenu().slots) {
            if (!MenuBindRequest.serverSlot(mc, slot)) continue;
            Selector entry = selector(slot.getItem().getHoverName().getString(), MenuBindRequest.lore(slot.getItem()));
            if (entry != null) entries.add(new OutfitEntry(slot.index, entry));
        }
        return entries;
    }
    record OutfitEntry(int slot, Selector selector) { }
    static MenuBindRequest.Decision choose(List<OutfitEntry> entries, int outfit, boolean sent) {
        int target = -1;
        State state = State.UNKNOWN;
        for (OutfitEntry entry : entries) {
            if (entry.selector.outfit != outfit) continue;
            if (target != -1) return MenuBindRequest.Decision.fail("Wardrobe slot labels are ambiguous; no outfit was clicked.");
            target = entry.slot;
            state = entry.selector.state;
        }
        if (state == State.EQUIPPED) return MenuBindRequest.Decision.done();
        if (sent || target == -1 || state == State.UNKNOWN) return MenuBindRequest.Decision.waitForItems();
        if (state == State.UNAVAILABLE) return MenuBindRequest.Decision.fail("That wardrobe slot is empty or locked.");
        return MenuBindRequest.Decision.click(target);
    }
    private static State state(List<OutfitEntry> entries, int outfit) {
        for (OutfitEntry entry : entries) if (entry.selector.outfit == outfit) return entry.selector.state;
        return State.UNKNOWN;
    }
    /** The outfit a swap should put on, or 0 while the menu has not said what either one is. */
    static int swapTarget(List<OutfitEntry> entries, int a, int b) {
        State first = state(entries, a), second = state(entries, b);
        if (first == State.UNKNOWN || second == State.UNKNOWN) return 0;
        return first == State.EQUIPPED ? b : a;
    }
    enum State { EQUIP, EQUIPPED, UNAVAILABLE, UNKNOWN }
    record Selector(int outfit, State state) { }
    static Selector selector(String title, List<String> lore) {
        var matcher = SLOT.matcher(MenuBindRequest.plain(title));
        if (!matcher.matches()) return null;
        int n = Integer.parseInt(matcher.group(1));
        if (n < 1 || n > 18) return null;
        boolean equip = false, equipped = false, unavailable = false;
        for (String raw : lore) {
            String line = MenuBindRequest.plain(raw).toLowerCase(java.util.Locale.ROOT);
            if (line.matches("click to (?:un-?equip|remove)(?:[!.])?") || line.matches("(?:currently )?equipped[!.]?")) equipped = true;
            else if (line.matches("click to equip(?:[!.])?")) equip = true;
            else if (line.contains("locked") || line.contains("empty")) unavailable = true;
        }
        return new Selector(n, equipped ? State.EQUIPPED : unavailable ? State.UNAVAILABLE : equip ? State.EQUIP : State.UNKNOWN);
    }

    private static void tick(Minecraft mc) {
        ensureScope(mc);
        if (mc.player == null || !(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            if (watched != null) { watched = null; settled.reset(); held.clear(); }
            return;
        }
        if (screen != watched) { watched = screen; watchedIsWardrobe = menu(screen); settled.reset(); held.clear(); }
        if (!watchedIsWardrobe) return;
        MenuBindRequest.heldKeys(mc, actions().keySet(), held);
        if (settled.after(screen, 200, System.currentTimeMillis())) learn(screen);
    }

    /** Reads each column upward from its selector; four rows of armour sit above the labelled row. */
    private static void learn(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        var slots = screen.getMenu().slots;
        boolean changed = false;
        for (OutfitEntry entry : entries(screen)) {
            int outfit = entry.selector.outfit;
            if (outfit > OUTFITS || entry.selector.state == State.UNKNOWN || entry.slot < 9 * WORN.length) continue;
            List<ItemStack> pieces = new ArrayList<>();
            for (int row = WORN.length; row >= 1; row--) {
                ItemStack stack = slots.get(entry.slot - 9 * row).getItem();
                pieces.add(armour(stack) ? LoadoutStore.picture(stack) : ItemStack.EMPTY);
            }
            // The server may show the outfit being worn as empty panes: it is on the player.
            if (entry.selector.state == State.EQUIPPED && pieces.stream().allMatch(ItemStack::isEmpty) && mc.player != null) {
                pieces.clear();
                for (EquipmentSlot worn : WORN) pieces.add(LoadoutStore.picture(mc.player.getItemBySlot(worn)));
            }
            Outfit next = new Outfit(pieces, entry.selector.state);
            if (!same(next, outfits[outfit - 1])) { outfits[outfit - 1] = next; changed = true; }
        }
        if (!changed) return;
        rebuild();
        write();
    }
    private static boolean armour(ItemStack stack) {
        return !stack.isEmpty() && !BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("glass_pane");
    }
    private static boolean same(Outfit a, Outfit b) {
        if (b == null || a.state != b.state) return false;
        for (int i = 0; i < a.pieces.size(); i++)
            if (!ItemStack.isSameItemSameComponents(a.pieces.get(i), b.pieces.get(i))) return false;
        return true;
    }
    private static void rebuild() {
        List<Setting.Tile> next = new ArrayList<>();
        for (int i = 1; i <= OUTFITS; i++) {
            int number = i;
            Outfit outfit = outfits[i - 1];
            State state = outfit == null ? State.UNKNOWN : outfit.state;
            String detail = switch (state) {
                case EQUIPPED -> "Equipped";
                case UNAVAILABLE -> "Empty";
                default -> "";
            };
            List<ItemStack> pieces = outfit == null ? List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY) : outfit.pieces;
            next.add(new Setting.Tile(key(i), "Slot " + i, 0, detail, pieces,
                    state == State.EQUIPPED, () -> request(number)));
        }
        tiles = List.copyOf(next);
    }
    private static void ensureScope(Minecraft mc) {
        String current = LoadoutStore.scope(mc);
        if (current == null || current.equals(scope)) return;
        scope = current;
        settled.reset();
        java.util.Arrays.fill(outfits, null);
        CompoundTag root = LoadoutStore.read(FILE, scope);
        swaps.read(root);
        for (CompoundTag tag : root.getListOrEmpty("outfits").compoundStream().toList()) {
            int outfit = tag.getIntOr("outfit", 0);
            if (outfit < 1 || outfit > OUTFITS) continue;
            List<ItemStack> pieces = new ArrayList<>();
            for (int i = 0; i < WORN.length; i++) pieces.add(LoadoutStore.get(tag, "piece" + i));
            State state;
            try { state = State.valueOf(tag.getStringOr("state", "UNKNOWN")); }
            catch (IllegalArgumentException stale) { state = State.UNKNOWN; }
            outfits[outfit - 1] = new Outfit(pieces, state);
        }
        rebuild();
    }
    private static void write() {
        if (scope.isEmpty()) return;
        CompoundTag root = new CompoundTag();
        swaps.write(root);
        ListTag list = new ListTag();
        for (int i = 0; i < OUTFITS; i++) {
            if (outfits[i] == null) continue;
            CompoundTag tag = new CompoundTag();
            tag.putInt("outfit", i + 1);
            tag.putString("state", outfits[i].state.name());
            for (int piece = 0; piece < outfits[i].pieces.size(); piece++) LoadoutStore.put(tag, "piece" + piece, outfits[i].pieces.get(piece));
            list.add(tag);
        }
        root.put("outfits", list);
        LoadoutStore.write(FILE, scope, root);
    }
}
