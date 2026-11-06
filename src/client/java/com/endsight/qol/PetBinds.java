package com.endsight.qol;

import com.endsight.profile.ProfileItems;
import com.endsight.ui.Keybinds;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.visual.EquipmentHud;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Learns only the player's visible server entries; a bind is a pet's identity, not an inventory index. */
public final class PetBinds {
    private PetBinds() { }
    private static final String ID = "qol.pets", SWAP_KEY = ID + ".swap", FILE = "pet-binds";
    private static final Pattern TITLE = Pattern.compile("(?i)^Pets(?: Menu)?(?:\\s*\\((\\d+)\\s*/\\d+\\))?$");
    private static final Pattern PET = Pattern.compile("(?i)^\\[Lvl\\s+(\\d+)]\\s+(.+)$");
    // Capitals on purpose: "a rare bonus for your pet" in an ability's text is not a rarity.
    private static final Pattern RARITY = Pattern.compile("\\b(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL)\\b.*\\bPET\\b");
    // The same three shapes the Active Pet HUD reads. Only the bare one was matched before,
    // so all seven pets saved from the live menu on 2026-11-04 came back holding nothing.
    private static final Pattern HELD = Pattern.compile("(?i)^(?:Held|Pet)\\s+Item(?:\\s*:\\s*(.*))?$");
    private static boolean initialized;
    private static String scope = "", out = "";
    private static final Map<Integer, List<Learned>> pages = new TreeMap<>();
    private static final SwapPairs swaps = new SwapPairs(SWAP_KEY, PetBinds::swap, PetBinds::write);
    private static List<Setting.Tile> tiles = List.of();
    /** Key id to what it looks for, built with the tiles: asked on every key press. */
    private static Map<String, Target> byId = Map.of();
    private static final Set<Integer> held = new HashSet<>();
    private static final MenuBindRequest.Settled settled = new MenuBindRequest.Settled();
    /** The last screen looked at, so a title is parsed once per menu and not once per tick. */
    private static AbstractContainerScreen<?> watched;
    private static boolean watchedIsPets;
    private static String hudPet = "";

    public static Module module() {
        return Module.group(ID, "Pet Binds", "A key for each pet, and one that flips between two.", "Quality of Life",
                List.of(new Setting.Loadout("Your pets", "pet", PetBinds::tiles,
                        swaps::list, swaps::add, PetBinds::refresh)));
    }
    public static void init() {
        if (initialized) return;
        initialized = true;
        MenuBindRequest.init();
        MenuBindRequest.menuKeys(PetBinds::menu, PetBinds::actions, held);
        ClientTickEvents.END_CLIENT_TICK.register(PetBinds::tick);
    }
    static int page(String title) {
        var match = TITLE.matcher(MenuBindRequest.plain(title));
        if (!match.matches()) return -1;
        return match.group(1) == null ? 1 : Integer.parseInt(match.group(1));
    }
    private static boolean menu(AbstractContainerScreen<?> screen) { return page(screen.getTitle().getString()) > 0; }

    private static List<Setting.Tile> tiles() {
        ensureScope(Minecraft.getInstance());
        return tiles;
    }
    private static Map<String, Runnable> actions() {
        Map<String, Runnable> actions = new LinkedHashMap<>();
        for (Setting.Swap swap : swaps.list()) actions.put(swap.key(), swap.run());
        // Only those with a key: this is asked every tick the menu is open.
        for (Setting.Tile tile : tiles) if (Keybinds.get(tile.id()) != Keybinds.NONE) actions.put(tile.id(), tile.run());
        return actions;
    }
    private static List<Learned> learned() { return pages.values().stream().flatMap(List::stream).toList(); }

    private static void request(String id) {
        ensureScope(Minecraft.getInstance());
        Target target = byId.get(id);
        if (target == null) { MenuBindRequest.tell("Open Loadouts and press Refresh on Pets."); return; }
        MenuBindRequest.start("Pet Binds", "pets", PetBinds::menu, 250, new PetSelection(target, null));
    }

    /** One key, two pets: whichever of them is out goes back and the other comes out. */
    private static void swap(String first, String second) {
        ensureScope(Minecraft.getInstance());
        Target a = byId.get(first), b = byId.get(second);
        if (a == null || b == null) { MenuBindRequest.tell("Choose two pets in Loadouts first."); return; }
        boolean aIsOut = a.species.equalsIgnoreCase(out);
        MenuBindRequest.start("Pet Binds", "pets", PetBinds::menu, 250, new PetSelection(aIsOut ? b : a, aIsOut ? a : b));
    }

    private static void refresh() {
        ensureScope(Minecraft.getInstance());
        MenuBindRequest.refresh("Pet Binds", "pets", PetBinds::menu, new Refresh());
    }

    /**
     * Walks every page once, so a pet that moved or left does not linger from an old visit.
     *
     * From the first page, whichever one the menu opened on, or every pet before it would be
     * dropped. Pages are counted here because only the arrows prove there is another, and a
     * title without a number used to read every page as page one and stop at the second.
     */
    private static final class Refresh implements MenuBindRequest.Selection {
        final Map<Integer, List<Learned>> found = new TreeMap<>();
        boolean atStart;
        int walked;
        @Override public MenuBindRequest.Decision decide(AbstractContainerScreen<?> screen, boolean sent) {
            int back = arrow(screen, "Previous Page");
            if (!atStart && back >= 0) return MenuBindRequest.Decision.page(back);
            atStart = true;
            found.put(++walked, read(screen, walked));
            int next = arrow(screen, "Next Page");
            if (next >= 0 && walked < 50) return MenuBindRequest.Decision.page(next);
            pages.clear();
            pages.putAll(found);
            rebuild();
            write();
            return MenuBindRequest.Decision.done();
        }
    }

    private static final class PetSelection implements MenuBindRequest.Selection {
        Target target;
        /** The swap's other pet, until the menu has shown which of the two is really out. */
        Target other;
        final Set<Integer> visited = new HashSet<>();
        /** Where the arrows have taken us, for a title with no number to say so. */
        int walkedTo;
        PetSelection(Target target, Target other) { this.target = target; this.other = other; }
        @Override public MenuBindRequest.Decision decide(AbstractContainerScreen<?> screen, boolean sent) {
            int current = Math.max(page(screen.getTitle().getString()), walkedTo);
            Minecraft mc = Minecraft.getInstance();
            List<PetEntry> onPage = new ArrayList<>();
            for (var slot : screen.getMenu().slots) {
                if (!MenuBindRequest.serverSlot(mc, slot)) continue;
                PetEntry pet = entry(slot.getItem(), slot.index);
                if (pet != null) onPage.add(pet);
            }
            for (PetEntry pet : onPage) if (pet.active && !pet.species.equalsIgnoreCase(out)) { out = pet.species; rebuild(); }
            if (other != null) for (PetEntry pet : onPage) {
                if (!pet.active) continue;
                // The menu beats what we remembered: the pet to summon is already out,
                // so a press that would have done nothing flips to the other one.
                if (matches(pet, target)) { Target swap = target; target = other; other = swap; visited.clear(); }
                other = null;
                break;
            }
            List<PetEntry> matches = onPage.stream().filter(pet -> matches(pet, target)).toList();
            if (!matches.isEmpty()) {
                // Copies are interchangeable, so the first will do, unless one is already out:
                // clicking the other would be a swap nobody asked for.
                PetEntry pet = matches.stream().filter(PetEntry::active).findFirst().orElse(matches.getFirst());
                if (pet.active) {
                    if (!sent) MenuBindRequest.tell(pet.species + " is already out.");
                    return MenuBindRequest.Decision.done();
                }
                other = null;
                return sent ? MenuBindRequest.Decision.waitForItems() : MenuBindRequest.Decision.click(pet.slot);
            }
            if (sent) return MenuBindRequest.Decision.waitForItems();
            visited.add(current);
            boolean back = current > target.page;
            int step = arrow(screen, back ? "Previous Page" : "Next Page");
            if (step < 0 || visited.contains(back ? current - 1 : current + 1)) {
                back = !back;
                step = arrow(screen, back ? "Previous Page" : "Next Page");
                if (step < 0 || visited.contains(back ? current - 1 : current + 1))
                    return MenuBindRequest.Decision.fail("That pet is gone. Press Refresh on Pets in Loadouts.");
            }
            walkedTo = back ? current - 1 : current + 1;
            return MenuBindRequest.Decision.page(step);
        }
    }
    private static int arrow(AbstractContainerScreen<?> screen, String name) {
        Minecraft mc = Minecraft.getInstance();
        for (var slot : screen.getMenu().slots) {
            if (!MenuBindRequest.serverSlot(mc, slot)) continue;
            ItemStack stack = slot.getItem();
            if (stack.is(Items.ARROW) && MenuBindRequest.plain(stack.getHoverName().getString()).equalsIgnoreCase(name)) return slot.index;
        }
        return -1;
    }

    record PetEntry(String species, String rarity, String item, int level, int slot, boolean active) { }
    record Learned(String species, String rarity, String item, int level, int page, ItemStack icon) { }
    /** What a press looks for. A null item or level 0 means any: most pets are told apart by neither. */
    record Target(String species, String rarity, String item, int level, int page) { }
    /** One tile: a pet, or copies of it that nothing can tell apart. */
    record Entry(Learned pet, String id, Target target, int copies) { }

    static PetEntry parse(String title, List<String> lore, int slot) {
        Matcher name = PET.matcher(MenuBindRequest.plain(title));
        if (!name.matches()) return null;
        boolean active = false, inactive = false;
        String rarity = "", item = "";
        for (int i = 0; i < lore.size(); i++) {
            String line = MenuBindRequest.plain(lore.get(i)), lower = line.toLowerCase(Locale.ROOT);
            if (lower.matches("click to (?:despawn|dismiss|deactivate)(?:[!.])?")) active = true;
            if (lower.matches("click to (?:summon|activate)(?:[!.])?")) inactive = true;
            Matcher badge = RARITY.matcher(line);
            if (badge.find()) rarity = badge.group(1);
            Matcher held = HELD.matcher(line);
            if (held.matches()) {
                String named = held.group(1) == null ? "" : held.group(1).trim();
                if (named.isEmpty() && i + 1 < lore.size()) named = MenuBindRequest.plain(lore.get(i + 1));
                item = named.equalsIgnoreCase("None") || named.equalsIgnoreCase("No item") ? "" : named;
            }
        }
        if (active == inactive) return null;
        return new PetEntry(name.group(2), rarity, item, Integer.parseInt(name.group(1)), slot, active);
    }
    private static PetEntry entry(ItemStack stack, int slot) {
        return parse(stack.getHoverName().getString(), MenuBindRequest.lore(stack), slot);
    }

    private static boolean twin(Learned a, Learned b) {
        return a.species.equalsIgnoreCase(b.species) && a.rarity.equals(b.rarity);
    }
    /**
     * What a key on each pet looks for: species and rarity, then the held item and the level
     * only where it takes that to tell two copies apart. Levels and held items change in
     * ordinary play and neither should cost a pet its key, so the level goes in as a place
     * among the copies and not as the number, and the one being trained keeps its key.
     *
     * Copies alike in all of that become one entry. Nothing says which is which, and nothing
     * needs to: a key summons whichever the menu shows first. They used to get no key at all,
     * and so did copies that were not alike, because the level was not looked at. A player
     * with two Golems could bind neither.
     */
    static List<Entry> entries(List<Learned> all) {
        // Counted first: asking per pet was every pet against every pet, on each swap.
        Map<String, Integer> kinds = new HashMap<>(), holding = new HashMap<>(), exact = new HashMap<>();
        Map<String, TreeSet<Integer>> levels = new HashMap<>();
        for (Learned pet : all) {
            kinds.merge(kindKey(pet), 1, Integer::sum);
            holding.merge(heldKey(pet), 1, Integer::sum);
            exact.merge(exactKey(pet), 1, Integer::sum);
            levels.computeIfAbsent(heldKey(pet), ignored -> new TreeSet<>(Comparator.reverseOrder())).add(pet.level);
        }
        List<Entry> entries = new ArrayList<>(exact.size());
        Set<String> done = new HashSet<>();
        for (Learned pet : all) {
            if (!done.add(exactKey(pet))) continue;
            boolean byItem = kinds.get(kindKey(pet)) > holding.get(heldKey(pet));
            TreeSet<Integer> ranks = levels.get(heldKey(pet));
            boolean byLevel = ranks.size() > 1;
            String id = ID + ".pet." + slug(pet.species) + "." + slug(pet.rarity) + (byItem ? "." + slug(pet.item) : "")
                    + (byLevel ? ".rank" + (ranks.headSet(pet.level).size() + 1) : "");
            entries.add(new Entry(pet, id, new Target(pet.species, pet.rarity, byItem ? pet.item : null, byLevel ? pet.level : 0, pet.page),
                    exact.get(exactKey(pet))));
        }
        return entries;
    }
    private static String kindKey(Learned pet) { return pet.species.toLowerCase(Locale.ROOT) + "|" + pet.rarity; }
    private static String heldKey(Learned pet) { return kindKey(pet) + "|" + pet.item.toLowerCase(Locale.ROOT); }
    private static String exactKey(Learned pet) { return heldKey(pet) + "|" + pet.level; }
    private static String slug(String text) { return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_"); }
    static boolean matches(PetEntry pet, Target target) {
        return pet.species.equalsIgnoreCase(target.species) && pet.rarity.equals(target.rarity)
                && (target.item == null || pet.item.equalsIgnoreCase(target.item))
                && (target.level == 0 || pet.level == target.level);
    }

    private static void tick(Minecraft mc) {
        ensureScope(mc);
        // The HUD reads the sidebar and tab, which is fresher than our last menu visit.
        String live = EquipmentHud.activePetName();
        if (!live.equals(hudPet)) {
            hudPet = live;
            String name = MenuBindRequest.plain(live);
            if (!name.isEmpty() && !name.equalsIgnoreCase(out)) { out = name; rebuild(); }
        }
        if (mc.player == null || !(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            if (watched != null) { watched = null; settled.reset(); held.clear(); }
            return;
        }
        if (screen != watched) { watched = screen; watchedIsPets = menu(screen); settled.reset(); held.clear(); }
        if (!watchedIsPets) return;
        MenuBindRequest.heldKeys(mc, actions().keySet(), held);
        if (!settled.after(screen, 200, System.currentTimeMillis())) return;
        int page = page(screen.getTitle().getString());
        List<Learned> seen = read(screen, page);
        if (seen.isEmpty() || same(seen, pages.get(page))) return;
        pages.put(page, seen);
        rebuild();
        write();
    }
    private static boolean same(List<Learned> seen, List<Learned> known) {
        if (known == null || known.size() != seen.size()) return false;
        for (int i = 0; i < seen.size(); i++) {
            Learned a = seen.get(i), b = known.get(i);
            if (!twin(a, b) || a.level != b.level || !a.item.equals(b.item) || !ItemStack.isSameItemSameComponents(a.icon, b.icon)) return false;
        }
        return true;
    }
    private static List<Learned> read(AbstractContainerScreen<?> screen, int page) {
        Minecraft mc = Minecraft.getInstance();
        List<Learned> seen = new ArrayList<>();
        for (var slot : screen.getMenu().slots) {
            if (!MenuBindRequest.serverSlot(mc, slot)) continue;
            PetEntry pet = entry(slot.getItem(), slot.index);
            if (pet == null) continue;
            seen.add(new Learned(pet.species, pet.rarity, pet.item, pet.level, page, LoadoutStore.picture(slot.getItem())));
            if (pet.active && !pet.species.equalsIgnoreCase(out)) out = pet.species;
        }
        return seen;
    }
    private static void rebuild() {
        List<Setting.Tile> next = new ArrayList<>();
        Map<String, Target> ids = new HashMap<>();
        for (Entry entry : entries(learned())) {
            Learned pet = entry.pet;
            String id = entry.id;
            boolean active = pet.species.equalsIgnoreCase(out);
            // The count before the item: a long item name is what runs off the tile.
            String detail = (active ? "Active · " : "") + "Lvl " + pet.level + (entry.copies > 1 ? " · " + entry.copies + " copies" : "")
                    + (pet.item.isBlank() ? "" : " · " + pet.item);
            next.add(new Setting.Tile(id, pet.species, ProfileItems.color(pet.rarity), detail, List.of(pet.icon), active, () -> request(id)));
            ids.put(id, entry.target);
        }
        tiles = List.copyOf(next);
        byId = ids;
    }
    private static void ensureScope(Minecraft mc) {
        String current = LoadoutStore.scope(mc);
        if (current == null || current.equals(scope)) return;
        scope = current;
        pages.clear();
        out = "";
        settled.reset();
        CompoundTag root = LoadoutStore.read(FILE, scope);
        swaps.read(root);
        for (CompoundTag tag : root.getListOrEmpty("pets").compoundStream().toList()) {
            int page = tag.getIntOr("page", 1);
            pages.computeIfAbsent(page, ignored -> new ArrayList<>()).add(new Learned(tag.getStringOr("species", ""),
                    tag.getStringOr("rarity", ""), tag.getStringOr("item", ""), tag.getIntOr("level", 1), page, LoadoutStore.get(tag, "icon")));
        }
        rebuild();
    }
    private static void write() {
        if (scope.isEmpty()) return;
        CompoundTag root = new CompoundTag();
        swaps.write(root);
        ListTag pets = new ListTag();
        for (Learned pet : learned()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("species", pet.species);
            tag.putString("rarity", pet.rarity);
            tag.putString("item", pet.item);
            tag.putInt("level", pet.level);
            tag.putInt("page", pet.page);
            LoadoutStore.put(tag, "icon", pet.icon);
            pets.add(tag);
        }
        root.put("pets", pets);
        LoadoutStore.write(FILE, scope, root);
    }
}
