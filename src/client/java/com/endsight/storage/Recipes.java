package com.endsight.storage;

import com.endsight.hud.Toast;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's recipes, learned from its own recipe menu and kept: an item panel
 * beside any inventory window - a grid of results in the server's own categories,
 * paged and searchable - and a second box on the other side where the one you
 * clicked opens, with what you already hold of each ingredient, counting your bags
 * and every storage page you have ever opened.
 *
 * The recipes are not in any file the client gets - they exist only as the menu the
 * server draws: "Recipes (1/6)", pages of items, and clicking one opens "<Item>
 * Recipe", a 3x3 grid with the result beside it. A private scan pass reads each
 * category in the left column in turn, every page of it read for what belongs there,
 * each item whose grid is not yet known clicked, read, and backed out of. Everything
 * learned goes to config/endsight/recipes.txt, so a scan is a once-a-patch job.
 *
 * Reading, not guessing, the screen's layout: the left column of a list page is the
 * categories (the star is "All Recipes"), the recipes sit in columns 1-8 of rows 1-4,
 * and the last row has another category plus furniture. On a
 * recipe page the grid is rows 1-3 of columns 1-3, the result is slot 25 and the
 * arrow at 45 goes back.
 */
public final class Recipes {

    private Recipes() {
    }

    private static final Pattern LIST = Pattern.compile("^Recipes \\((\\d+)/(\\d+)\\)$");
    private static final Pattern RECIPE = Pattern.compile("^(.+?) Recipe(?: \\(\\d+/\\d+\\))?$");
    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int RESULT = 25;
    private static final int BACK = 45;
    private static final int NEXT = 53;
    /** The category column: the star (All) and the five under it. */
    private static final int[] CATEGORIES = {9, 18, 27, 36, 45, 0};

    /** One cell of a grid, or the result: what and how many. */
    record Ingredient(String name, int count) {
    }

    /** A recipe: nine cells (null for empty), the result and its count. */
    record Recipe(String name, int count, Ingredient[] grid) {
        /** Once per recipe: this is asked for every cell on screen, every frame. */
        List<Ingredient> needs() {
            return NEEDS.computeIfAbsent(this, r -> {
                Map<String, Integer> sum = new LinkedHashMap<>();
                for (Ingredient i : r.grid) if (i != null) sum.merge(i.name(), i.count(), Integer::sum);
                List<Ingredient> out = new ArrayList<>();
                sum.forEach((n, c) -> out.add(new Ingredient(n, c)));
                return List.copyOf(out);
            });
        }

        private static final Map<Recipe, List<Ingredient>> NEEDS = new java.util.IdentityHashMap<>();

        boolean uses(String item) {
            for (Ingredient i : grid) if (i != null && i.name().equals(item)) return true;
            return false;
        }
    }

    private static boolean enabled = true;
    private static boolean panel = true;
    /** The boxes as tinted glass instead of solid, for people who want the world behind them. */
    private static boolean glass = false;
    /** What "have" counts: what is on you, or that plus every storage page and the ender chest. */
    private static final String INV = "Inventory only";
    private static final String ALL = "Inventory + storage";
    private static String scope = ALL;
    /** The needs list as raw materials, every sub-recipe expanded, instead of the recipe's own cells. */
    private static boolean fullCost = false;
    /** Height of the "Counting:" chip row under a recipe's title. */
    private static final int SCOPE_H = 18;
    private static final Map<String, Recipe> RECIPES = new LinkedHashMap<>();
    /** Category name -> the recipe names the server lists under it, in its order. */
    private static final Map<String, LinkedHashSet<String>> CATEGORY = new LinkedHashMap<>();
    /**
     * A stack seen for each name, anywhere, so the panel can draw an icon instead of a
     * word. Kept on disk too - the first relaunch showed a grid of letters, because
     * the icons had only ever lived in memory and the recipes file holds names.
     */
    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static boolean iconsLoaded, iconsDirty;
    /** The ender chest, by title, the last time it was open - /ec holds things too. */
    private static final Map<String, List<ItemStack>> CHESTS = new LinkedHashMap<>();
    private static final Pattern CHEST = Pattern.compile("^Ender Chest(?: \\(\\d+/\\d+\\))?$");

    public static Module module() {
        return new Module("storage.recipes", "Recipes",
                "Recipes and Forge crafts beside your inventory.", "Storage",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Toggle("Panel",
                                "Recipe grid beside your inventory. Click an item for its recipe, right-click for its uses.",
                                () -> panel, v -> panel = v),
                        new Setting.Toggle("Glass",
                                "See-through boxes.",
                                () -> glass, v -> glass = v),
                        new Setting.Note("Known", () -> RECIPES.size() + " recipes, "
                                + CATEGORY.size() + " categories, " + ForgeRecipes.recipeCount() + " forge crafts")));
    }

    public static void init() {
        load();
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenEvents.afterTick(screen).register(s -> tick(container));
            attachPanel(client, screen, container);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // A scan that loses its screen - Escape, a teleport - is over, not waiting.
            if (armed && mc.screen == null && System.currentTimeMillis() - busyUntil > 1_500) finish("stopped");
            // Icons need the level's registries to decode, so they wait for one.
            if (mc.level == null) return;
            if (!iconsLoaded) {
                iconsLoaded = true;
                loadIcons();
            }
            if (iconsDirty && System.currentTimeMillis() - iconsSaved > 15_000) saveIcons();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (iconsDirty) saveIcons();
            if (recentDirty) saveRecent();
        });
    }

    // ── reading the screens ───────────────────────────────────────────────────

    private static void tick(AbstractContainerScreen<?> screen) {
        if (!enabled) return;
        String title = Zealots.strip(screen.getTitle().getString()).trim();
        Matcher list = LIST.matcher(title);
        if (list.matches()) {
            List<ItemStack> slots = containerSlots(screen);
            if (slots.size() < 54) return;
            for (ItemStack s : slots) remember(s);
            if (armed) walkList(screen, slots, Integer.parseInt(list.group(1)), Integer.parseInt(list.group(2)));
            return;
        }
        Matcher recipe = RECIPE.matcher(title);
        if (recipe.matches()) {
            List<ItemStack> slots = containerSlots(screen);
            if (slots.size() < 54 || slots.get(RESULT).isEmpty()) return;      // not arrived yet
            capture(slots);
            if (armed) walkRecipe(screen);
            return;
        }
        if (CHEST.matcher(title).matches()) {
            List<ItemStack> slots = containerSlots(screen);
            boolean any = false;
            for (ItemStack s : slots) if (!s.isEmpty()) any = true;
            // Blank for a frame or two after opening; a real empty chest stays whatever
            // it was, which costs nothing.
            if (any) {
                List<ItemStack> copy = new ArrayList<>();
                for (ItemStack s : slots) copy.add(s.copy());
                CHESTS.put(title, copy);
                iconsDirty = true;
            }
        }
    }

    private static void capture(List<ItemStack> slots) {
        ItemStack result = slots.get(RESULT);
        String name = name(result);
        // A recipe page whose result slot still shows the menu's blank filler pane was
        // once learned as a recipe with no name. Every nameless filler in every menu
        // then matched it, and read "Owned".
        if (name.isBlank()) return;
        Ingredient[] grid = new Ingredient[9];
        for (int i = 0; i < 9; i++) {
            ItemStack s = slots.get(GRID[i]);
            if (!s.isEmpty()) {
                grid[i] = new Ingredient(name(s), s.getCount());
                remember(s);
            }
        }
        remember(result);
        Recipe r = new Recipe(name, result.getCount(), grid);
        Recipe old = RECIPES.put(name, r);
        if (old == null || !Arrays.equals(old.grid(), r.grid())) save();
    }

    private static void remember(ItemStack s) {
        if (s.isEmpty()) return;
        String n = name(s);
        if (ICONS.containsKey(n)) return;      // the copy is the expensive part; only make it once
        ICONS.put(n, s.copyWithCount(1));
        iconsDirty = true;
    }

    // ── icons and chests, on disk ─────────────────────────────────────────────

    private static long iconsSaved;

    private static Path iconsFile() {
        return dir().resolve("recipes-items.nbt");
    }

    /** One stack per name, and the ender chest's contents, as the storage snapshots are kept. */
    private static void saveIcons() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        iconsDirty = false;
        iconsSaved = System.currentTimeMillis();
        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess());
            ListTag icons = new ListTag();
            for (ItemStack s : ICONS.values()) ItemStack.OPTIONAL_CODEC.encodeStart(ops, s).result().ifPresent(icons::add);
            ListTag chests = new ListTag();
            CHESTS.forEach((title, items) -> {
                CompoundTag c = new CompoundTag();
                c.putString("title", title);
                ListTag list = new ListTag();
                for (ItemStack s : items) ItemStack.OPTIONAL_CODEC.encodeStart(ops, s).result().ifPresent(list::add);
                c.put("items", list);
                chests.add(c);
            });
            CompoundTag root = new CompoundTag();
            root.put("icons", icons);
            root.put("chests", chests);
            Files.createDirectories(dir());
            NbtIo.writeCompressed(root, iconsFile());
        } catch (IOException | RuntimeException e) {
            System.err.println("[Endsight] could not write recipe items: " + e);
        }
    }

    private static void loadIcons() {
        Minecraft mc = Minecraft.getInstance();
        Path f = iconsFile();
        if (mc.level == null) return;
        boolean hadFile = Files.exists(f);
        if (hadFile) readIconFile(f, true);
        boolean merged = mergeBundledIcons();
        if (!hadFile || merged) saveIcons();
    }

    private static boolean readIconFile(Path f, boolean includeChests) {
        Minecraft mc = Minecraft.getInstance();
        try {
            CompoundTag root = NbtIo.readCompressed(f, NbtAccounter.create(16L * 1024 * 1024));
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess());
            boolean changed = false;
            for (Tag tag : root.getListOrEmpty("icons")) {
                ItemStack s = ItemStack.OPTIONAL_CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
                if (!s.isEmpty() && ICONS.putIfAbsent(name(s), s) == null) changed = true;
            }
            if (includeChests) {
                for (CompoundTag c : root.getListOrEmpty("chests").compoundStream().toList()) {
                    List<ItemStack> items = new ArrayList<>();
                    for (Tag tag : c.getListOrEmpty("items")) {
                        items.add(ItemStack.OPTIONAL_CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY));
                    }
                    CHESTS.put(c.getStringOr("title", "Ender Chest"), items);
                }
            }
            return changed;
        } catch (IOException | RuntimeException e) {
            System.err.println("[Endsight] could not read recipe items: " + e);
            return false;
        }
    }

    private static boolean mergeBundledIcons() {
        try (InputStream in = Recipes.class.getResourceAsStream("/endsight-recipes-items.nbt")) {
            if (in == null) return false;
            Path temp = Files.createTempFile("endsight-recipes-items", ".nbt");
            try {
                Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return readIconFile(temp, false);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[Endsight] could not read bundled recipe items: " + e);
            return false;
        }
    }

    static String name(ItemStack s) {
        return Zealots.strip(s.getHoverName().getString()).trim();
    }

    /** A stack of this name the mod has seen, with its lore intact, or null. */
    public static ItemStack icon(String item) {
        ItemStack s = ICONS.get(item);
        if (s != null) return s;
        String key = recipeFor(item);
        return key == null ? null : ICONS.get(key);
    }

    /** A stack of this name you are holding or have in storage, or null. */
    public static ItemStack held(String item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            for (ItemStack s : mc.player.getInventory().getNonEquipmentItems()) {
                if (!s.isEmpty() && name(s).equalsIgnoreCase(item)) return s;
            }
        }
        for (PageSnapshot snap : StoragePreview.snapshots().values()) {
            for (ItemStack s : snap.items()) if (!s.isEmpty() && name(s).equalsIgnoreCase(item)) return s;
        }
        for (List<ItemStack> chest : CHESTS.values()) {
            for (ItemStack s : chest) if (!s.isEmpty() && name(s).equalsIgnoreCase(item)) return s;
        }
        return null;
    }

    /**
     * The recipe an item is, whatever the server has hung on its name.
     *
     * A helmet in your inventory reads "Fierce Superior Dragon Helmet ✪✪✪✪"; the recipe
     * is "Superior Dragon Helmet". Reforge in front, stars behind, and the first R that
     * did nothing on a piece of armour was exactly this. So the match is the longest
     * recipe name found inside the item's, on letters and digits alone - an exact hit
     * wins, and nothing shorter than the whole recipe name ever matches by accident.
     */
    private static String recipeFor(String itemName) {
        if (RECIPES.containsKey(itemName)) return itemName;
        String hay = plain(itemName);
        String best = null;
        for (String r : RECIPES.keySet()) {
            String needle = plain(r);
            if (needle.isEmpty() || !hay.contains(needle)) continue;
            if (best == null || needle.length() > plain(best).length()) best = r;
        }
        return best;
    }

    /** Letters, digits and single spaces; everything the server decorates with, gone. */
    private static String plain(String s) {
        return s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static List<ItemStack> containerSlots(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        Container playerInv = mc.player == null ? null : mc.player.getInventory();
        List<ItemStack> out = new ArrayList<>();
        try {
            for (Slot slot : screen.getMenu().slots) {
                if (slot.container != playerInv) out.add(slot.getItem());
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return out;
    }

    // ── the scan ──────────────────────────────────────────────────────────────

    private static boolean armed;
    private static long busyUntil;
    private static String expecting;
    private static final Set<String> skipped = new HashSet<>();
    private static int scanned;
    /** Which entry of CATEGORIES the scan is in, and that category's name. */
    private static int categoryAt;
    private static String category;

    private static void arm() {
        armed = true;
        scanned = 0;
        categoryAt = -1;
        category = null;
        CATEGORY.clear();
        skipped.clear();
        busyUntil = System.currentTimeMillis() + 60_000;     // a minute to open the menu
        Toast.changed("Recipes", "Open the recipe menu");
    }

    private static void finish(String why) {
        armed = false;
        save();
        Toast.changed("Recipes", why + " - " + RECIPES.size() + " known");
    }

    private static void click(AbstractContainerScreen<?> screen, int containerIndex, long wait) {
        Minecraft mc = Minecraft.getInstance();
        Container playerInv = mc.player.getInventory();
        List<Slot> slots = screen.getMenu().slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (s.container != playerInv && s.index == containerIndex) {
                mc.gameMode.handleContainerInput(screen.getMenu().containerId, i, 0, ContainerInput.PICKUP, mc.player);
                break;
            }
        }
        busyUntil = System.currentTimeMillis() + wait;
    }

    private static void walkList(AbstractContainerScreen<?> screen, List<ItemStack> slots, int page, int pages) {
        long now = System.currentTimeMillis();
        if (now < busyUntil) {
            // The list is still up after a click that should have opened a recipe: that
            // item has none. Skipped, or the scan would click it forever.
            if (expecting != null && now > busyUntil - 200) {
                skipped.add(expecting);
                expecting = null;
            }
            return;
        }
        expecting = null;

        // Not in a category yet: open the first one. The catalogue view and a category's
        // pages look alike, so the scan is always inside one it chose itself.
        if (categoryAt < 0) {
            nextCategory(screen, slots);
            return;
        }

        // Everything on this page belongs to the category, known grid or not.
        if (category != null) {
            LinkedHashSet<String> in = CATEGORY.computeIfAbsent(category, k -> new LinkedHashSet<>());
            for (int row = 1; row <= 4; row++) {
                for (int col = 1; col <= 8; col++) {
                    ItemStack s = slots.get(row * 9 + col);
                    if (!s.isEmpty()) in.add(name(s));
                }
            }
        }
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 8; col++) {
                int i = row * 9 + col;
                ItemStack s = slots.get(i);
                if (s.isEmpty()) continue;
                String n = name(s);
                if (skipped.contains(n)) continue;
                // Known, and every ingredient has a face: nothing to learn from it.
                if (RECIPES.containsKey(n) && !missingIcons(RECIPES.get(n))) continue;
                expecting = n;
                click(screen, i, 1_500);
                return;
            }
        }
        if (page < pages) click(screen, NEXT, 800);
        else nextCategory(screen, slots);
    }

    /** On to the next category in the left column; the star ("All") comes last, for strays. */
    private static void nextCategory(AbstractContainerScreen<?> screen, List<ItemStack> slots) {
        while (++categoryAt < CATEGORIES.length) {
            ItemStack s = slots.get(CATEGORIES[categoryAt]);
            if (s.isEmpty()) continue;
            String n = name(s);
            category = n.contains("All") ? null : n;
            click(screen, CATEGORIES[categoryAt], 800);
            return;
        }
        finish("Scanned " + scanned);
    }

    /** Whether any cell of the grid has no stack on record to draw it with. */
    private static boolean missingIcons(Recipe r) {
        for (Ingredient i : r.grid()) if (i != null && !ICONS.containsKey(i.name())) return true;
        return false;
    }

    private static void walkRecipe(AbstractContainerScreen<?> screen) {
        if (System.currentTimeMillis() < busyUntil) return;
        scanned++;
        expecting = null;
        click(screen, BACK, 600);
    }

    // ── the file ──────────────────────────────────────────────────────────────

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight");
    }

    /**
     * One line per recipe: result, its count, then the nine cells as "count*name",
     * tab-separated; then the categories, one line each: "category: name, name, ...".
     */
    private static void save() {
        List<String> lines = new ArrayList<>();
        lines.add("# result\tcount\tcell0..cell8 as count*name (blank for empty). Rewritten by the mod on every scan.");
        for (Recipe r : RECIPES.values()) {
            StringBuilder sb = new StringBuilder(r.name()).append('\t').append(r.count());
            for (Ingredient i : r.grid()) sb.append('\t').append(i == null ? "" : i.count() + "*" + i.name());
            lines.add(sb.toString());
        }
        lines.add("");
        lines.add("# categories, as the server's menu lists them");
        CATEGORY.forEach((c, names) -> lines.add("@" + c + "\t" + String.join("\t", names)));
        try {
            Files.createDirectories(dir());
            Files.write(dir().resolve("recipes.txt"), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Endsight] could not write recipes: " + e);
        }
    }

    private static void load() {
        Path f = dir().resolve("recipes.txt");
        boolean hadFile = Files.exists(f);
        try {
            if (hadFile) readRecipes(Files.readAllLines(f, StandardCharsets.UTF_8), false);
            boolean merged = mergeBundledRecipes();
            if (!hadFile || merged) save();
        } catch (IOException | NumberFormatException e) {
            System.err.println("[Endsight] could not read recipes: " + e);
        }
        loadRecent();
    }

    private static Path recentFile() {
        return dir().resolve("recipe-recent.txt");
    }

    private static void saveRecent() {
        recentDirty = false;
        List<String> lines = new ArrayList<>();
        lines.add("# most recent first");
        lines.addAll(recentRows());
        try {
            Files.createDirectories(dir());
            Files.write(recentFile(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Endsight] could not write recent recipes: " + e);
        }
    }

    private static void loadRecent() {
        Path f = recentFile();
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#") || !RECIPES.containsKey(line)) continue;
                RECENT.add(line);
                if (RECENT.size() >= RECENT_MAX) break;
            }
        } catch (IOException e) {
            System.err.println("[Endsight] could not read recent recipes: " + e);
        }
    }

    private static boolean mergeBundledRecipes() throws IOException {
        try (InputStream in = Recipes.class.getResourceAsStream("/endsight-recipes.txt")) {
            if (in == null) return false;
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return readRecipes(text.lines().toList(), true);
        }
    }

    private static boolean readRecipes(List<String> lines, boolean missingOnly) {
        boolean changed = false;
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\t", -1);
            if (line.startsWith("@")) {
                LinkedHashSet<String> in = CATEGORY.computeIfAbsent(p[0].substring(1), k -> new LinkedHashSet<>());
                for (int i = 1; i < p.length; i++) if (!p[i].isBlank() && in.add(p[i])) changed = true;
                continue;
            }
            if (p.length < 11 || p[0].isBlank()) continue;
            Ingredient[] grid = new Ingredient[9];
            for (int i = 0; i < 9; i++) {
                String cell = p[2 + i];
                int star = cell.indexOf('*');
                if (star > 0) grid[i] = new Ingredient(cell.substring(star + 1), Integer.parseInt(cell.substring(0, star)));
            }
            Recipe r = new Recipe(p[0], Integer.parseInt(p[1]), grid);
            if (missingOnly && RECIPES.containsKey(r.name())) continue;
            Recipe old = RECIPES.put(r.name(), r);
            if (old == null || !Arrays.equals(old.grid(), r.grid()) || old.count() != r.count()) changed = true;
        }
        return changed;
    }

    // ── what you have ─────────────────────────────────────────────────────────

    private static Map<String, Integer> holdingsCache;
    private static long holdingsAt;
    private static String holdingsScope;

    /**
     * Everything you hold, by name: your inventory, every storage page ever snapshotted,
     * and the ender chest.
     *
     * Counted ten times a second, not every frame. The panel and every tooltip on the
     * screen asked for this fresh, and each count walks every stack in every snapshot
     * and reads its name twice - at a few hundred frames a second that was most of the
     * frame, and the forge menu ran at a tenth of the speed the world did.
     */
    private static Map<String, Integer> holdings() {
        long now = System.currentTimeMillis();
        if (holdingsCache != null && now - holdingsAt < 100 && scope.equals(holdingsScope)) return holdingsCache;
        holdingsAt = now;
        holdingsScope = scope;
        return holdingsCache = countHoldings();
    }

    private static Map<String, Integer> countHoldings() {
        Map<String, Integer> have = new HashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            for (ItemStack s : mc.player.getInventory().getNonEquipmentItems()) count(have, s);
        }
        if (INV.equals(scope)) return have;
        for (PageSnapshot snap : StoragePreview.snapshots().values()) {
            for (ItemStack s : snap.items()) count(have, s);
        }
        for (List<ItemStack> chest : CHESTS.values()) {
            for (ItemStack s : chest) count(have, s);
        }
        return have;
    }

    private static void count(Map<String, Integer> have, ItemStack s) {
        if (s.isEmpty()) return;
        String n = name(s);
        if (n.isBlank()) return;      // menu filler, not a thing you own
        have.merge(n, s.getCount(), Integer::sum);
        remember(s);
    }

    /** Ingredients you have enough of, out of the ingredients there are. */
    private static int[] ready(Recipe r, Map<String, Integer> have) {
        int ok = 0;
        List<Ingredient> needs = r.needs();
        for (Ingredient i : needs) if (have.getOrDefault(i.name(), 0) >= i.count()) ok++;
        return new int[]{ok, needs.size()};
    }

    private static boolean complete(int[] ready) {
        return ready[1] > 0 && ready[0] == ready[1];
    }

    /**
     * What a recipe comes down to in raw materials, every sub-recipe expanded, less
     * what you already hold of the intermediates: a Zombie Heart on you is one you do
     * not need the viscera for. Eight Zombie Hearts and a Crystallized Heart is a
     * shopping list of two lines that says nothing about the two thousand viscera
     * under it; this is the two thousand, against what you have.
     */
    static List<Ingredient> fullCost(Recipe r, Map<String, Integer> have) {
        Map<String, Integer> raw = new LinkedHashMap<>();
        Set<String> path = new HashSet<>();
        path.add(r.name());
        for (Ingredient i : r.needs()) expand(i.name(), i.count(), raw, path);
        List<Ingredient> out = new ArrayList<>();
        raw.forEach((n, c) -> out.add(new Ingredient(n, c)));
        return out;
    }

    private static void expand(String item, int count, Map<String, Integer> raw, Set<String> path) {
        Recipe sub = RECIPES.get(item);
        // Tier materials are the useful shopping-list unit. Expand nested forge parts
        // like Fuel Tank, but keep Refined/Fine/Flawed rows readable and exact.
        if (exactTierItem(item) || sub == null || path.contains(item)) {
            raw.merge(item, count, Integer::sum);
            return;
        }
        int crafts = (count + sub.count() - 1) / sub.count();
        path.add(item);
        for (Ingredient i : sub.needs()) expand(i.name(), i.count() * crafts, raw, path);
        path.remove(item);
    }

    // ── the panel ─────────────────────────────────────────────────────────────

    /**
     * Two boxes, not one. The item grid sits on the right of the screen - pages of
     * icons, the categories down its left edge as the server's own menu has them,
     * prev / page / next above, a search box below. A recipe opens in a SEPARATE box
     * on the left, so the grid stays where it is: a craft-up needs three recipes read
     * one after another, and a viewer that replaced the grid meant back, find, click,
     * back, find, click. Now the grid never moves and the left box just changes.
     */
    private static final int CELL = 20;
    private static final int PAD = 6;
    private static final int GAP = 6;
    private static final int HEAD = 22;
    private static final int FOOT = 34;
    private static final int CATS = 22;
    private static final int RECENT_W = 84;
    private static final int RECENT_MAX = 10;
    private static final int RECENT_ROW = 18;

    private static String query = "";
    private static String cat = null;           // null = All
    /** Tucked away by the tab on its edge; the tab stays so it can come back. */
    private static boolean shown = true;
    private static final int TAB_W = 9, TAB_H = 28;
    private static int page;
    /** What the left box shows: a recipe, or an item whose uses are listed; null for closed. */
    private static String openRecipe, openUses;
    private static final List<String> trail = new ArrayList<>();
    private static final List<String> RECENT = new ArrayList<>();
    private static boolean recentDirty;

    private static EditBox box;
    private static Screen owner;

    private record Frame(int x, int y, int w, int h, int cols, int rows, int recentW) {
        int catX() { return x + PAD; }
        int recentX() { return x + PAD + CATS; }
        int gridX() { return x + PAD + CATS + (recentW > 0 ? recentW + GAP : 0); }
        int gridY() { return y + HEAD; }
        int gridW() { return cols * CELL; }
        int gridH() { return rows * CELL; }
    }

    private static Frame frame(AbstractContainerScreen<?> s) {
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int left = s.leftPos + s.imageWidth + GAP;
        // The storage window hangs its own preview off its right side; leave that room.
        if (StoragePreview.isStorageWindow(s)) left += 9 * 18 + 16;
        int avail = sw - 4 - left;
        int recent = avail >= PAD * 2 + CATS + RECENT_W + GAP + 4 * CELL ? RECENT_W : 0;
        int cols = Math.max(4, Math.min(9, (avail - PAD * 2 - CATS - (recent > 0 ? recent + GAP : 0)) / CELL));
        int w = cols * CELL + PAD * 2 + CATS + (recent > 0 ? recent + GAP : 0);
        int x = sw - 4 - w;
        int y = 4, h = sh - 8;
        int rows = Math.max(2, (h - HEAD - FOOT) / CELL);
        return new Frame(x, y, w, h, cols, rows, recent);
    }

    /** The left box: as wide as the room beside the window allows, up to a comfortable width. */
    private record Viewer(int x, int y, int w, int h) {
    }

    private static Viewer viewer(AbstractContainerScreen<?> s) {
        Minecraft mc = Minecraft.getInstance();
        int sh = mc.getWindow().getGuiScaledHeight();
        int w = Math.min(9 * CELL + PAD * 2, s.leftPos - GAP - 4);
        return new Viewer(4, 4, Math.max(120, w), sh - 8);
    }

    private static void attachPanel(Minecraft client, Screen screen, AbstractContainerScreen<?> container) {
        Frame f = frame(container);
        Font font = client.font;
        int bx = f.gridX() + 3, by = f.y + f.h - FOOT + 16;
        box = new EditBox(font, bx, by, f.gridW() - 6, 11, Component.literal("Search"));
        box.setBordered(false);
        box.setMaxLength(40);
        box.setTextColor(Theme.text());
        box.setHint(Component.literal("Search recipes…"));
        box.setValue(query);
        box.setResponder(v -> {
            query = v;
            page = 0;
        });
        owner = screen;
        Screens.getWidgets(screen).add(box);

        // As Item Search does: the box first, and the inventory key hidden from the
        // screen while it is focused, so typing "e" types an "e". Escape still closes.
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, e) -> {
            if (!enabled || !panel) return true;
            if (box != null && box.isFocused() && e.key() != 256) {
                box.keyPressed(e);
                String typed = typedText(e);
                if (typed != null) box.insertText(typed);
                return false;
            }
            // R and U over any item, in any window: its recipe, or what it goes into.
            if (e.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_R || e.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_U) {
                return !lookup(container, e.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_U);
            }
            return true;
        });
        ScreenEvents.afterExtract(screen).register((s, g, mx, my, d) -> draw(container, g, mx, my));
        ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> !click(container, click.x(), click.y(), click.button()));
        ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, hx, vy) -> !scroll(container, mx, my, vy));
        ScreenEvents.remove(screen).register(s -> {
            if (owner == s) {
                owner = null;
                box = null;
            }
        });
    }

    private static String typedText(net.minecraft.client.input.KeyEvent e) {
        int mods = e.modifiers();
        if ((mods & (org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL
                | org.lwjgl.glfw.GLFW.GLFW_MOD_ALT
                | org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER)) != 0) return null;
        boolean shift = (mods & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0;
        int key = e.key();
        if (key >= org.lwjgl.glfw.GLFW.GLFW_KEY_A && key <= org.lwjgl.glfw.GLFW.GLFW_KEY_Z) {
            char c = (char) ('a' + key - org.lwjgl.glfw.GLFW.GLFW_KEY_A);
            return String.valueOf(shift ? Character.toUpperCase(c) : c);
        }
        if (key >= org.lwjgl.glfw.GLFW.GLFW_KEY_0 && key <= org.lwjgl.glfw.GLFW.GLFW_KEY_9) {
            String plain = "0123456789";
            String shifted = ")!@#$%^&*(";
            int i = key - org.lwjgl.glfw.GLFW.GLFW_KEY_0;
            return String.valueOf((shift ? shifted : plain).charAt(i));
        }
        return switch (key) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE -> " ";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS -> shift ? "_" : "-";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL -> shift ? "+" : "=";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET -> shift ? "{" : "[";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET -> shift ? "}" : "]";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSLASH -> shift ? "|" : "\\";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_SEMICOLON -> shift ? ":" : ";";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE -> shift ? "\"" : "'";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_COMMA -> shift ? "<" : ",";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PERIOD -> shift ? ">" : ".";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH -> shift ? "?" : "/";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_GRAVE_ACCENT -> shift ? "~" : "`";
            default -> null;
        };
    }

    /**
     * The item under the mouse - a slot of the window, or a cell of our grid - opened
     * as its recipe (R) or its uses (U). The one habit everyone brings from the other
     * item lists, so it works here too, and it works on things in your own inventory
     * that the grid never shows, which is where "what is this even for" gets asked.
     */
    private static boolean lookup(AbstractContainerScreen<?> s, boolean uses) {
        Minecraft mc = Minecraft.getInstance();
        double mx = mc.mouseHandler.getScaledXPos(mc.getWindow());
        double my = mc.mouseHandler.getScaledYPos(mc.getWindow());
        String name = null;
        for (Slot slot : s.getMenu().slots) {
            int x = s.leftPos + slot.x, y = s.topPos + slot.y;
            if (slot.hasItem() && mx >= x && mx < x + 16 && my >= y && my < y + 16) {
                name = name(slot.getItem());
                break;
            }
        }
        if (name == null && shown) {
            Frame f = frame(s);
            List<String> list = visible();
            int gx = f.gridX(), gy = f.gridY();
            if (mx >= gx && mx < gx + f.gridW() && my >= gy && my < gy + f.gridH()) {
                int idx = page * f.cols * f.rows + (int) ((my - gy) / CELL) * f.cols + (int) ((mx - gx) / CELL);
                if (idx < list.size()) name = list.get(idx);
            }
        }
        if (name == null) return false;
        String known = recipeFor(name);
        if (uses) {
            String item = known != null ? known : name;
            if (usesOf(item).isEmpty()) return false;
            openUses(item);
        } else {
            if (known == null) return false;
            openRecipe(known);
        }
        shown = true;
        return true;
    }

    /** The results to show: the category, filtered by the search, in the server's own order. */
    private static List<String> visible() {
        List<String> out = new ArrayList<>();
        Iterable<String> names = cat == null ? RECIPES.keySet() : CATEGORY.getOrDefault(cat, new LinkedHashSet<>());
        String q = query.trim().toLowerCase();
        for (String n : names) {
            if (!RECIPES.containsKey(n)) continue;
            if (!q.isEmpty() && !n.toLowerCase().contains(q)) continue;
            out.add(n);
        }
        return out;
    }

    /** The category column's entries: All first, then the server's, each with its icon if one was seen. */
    private static List<String> categories() {
        List<String> out = new ArrayList<>();
        out.add(null);
        out.addAll(CATEGORY.keySet());
        return out;
    }

    private static List<String> recentRows() {
        return RECENT.stream()
                .filter(RECIPES::containsKey)
                .distinct()
                .limit(RECENT_MAX)
                .toList();
    }

    private static void touchRecent(String name) {
        if (!RECIPES.containsKey(name)) return;
        RECENT.remove(name);
        RECENT.add(0, name);
        while (RECENT.size() > RECENT_MAX) RECENT.remove(RECENT.size() - 1);
        recentDirty = true;
        saveRecent();
    }

    private static String categoryFor(String name) {
        for (Map.Entry<String, LinkedHashSet<String>> e : CATEGORY.entrySet()) {
            if (e.getValue().contains(name)) return e.getKey();
        }
        return null;
    }

    private static void focusRecipeInGrid(String name, Frame f) {
        cat = categoryFor(name);
        if (!query.isEmpty()) {
            query = "";
            if (box != null) box.setValue("");
        }
        List<String> list = visible();
        int idx = list.indexOf(name);
        page = idx < 0 ? 0 : idx / Math.max(1, f.cols() * f.rows());
    }

    private static ItemStack categoryIcon(String c) {
        if (c != null) return ICONS.get(c);
        for (Map.Entry<String, ItemStack> e : ICONS.entrySet()) {
            if (e.getKey().startsWith("All Recipes")) return e.getValue();
        }
        return null;
    }

    private static void draw(AbstractContainerScreen<?> s, GuiGraphicsExtractor g, int mx, int my) {
        if (!enabled || !panel || RECIPES.isEmpty()) {
            if (box != null) box.visible = false;
            return;
        }
        if (box != null) box.visible = shown;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        drawTab(s, g, font, mx, my);
        if (!shown) return;
        Map<String, Integer> have = holdings();
        drawGrid(s, g, font, have, mx, my);
        if (openRecipe != null || openUses != null) drawViewer(s, g, font, have, mx, my);
        drawTip(g, font);
    }

    /**
     * The tab that tucks the panel away: x, y, w, h. Over the category column while
     * the panel is out, on the screen's right edge once it is tucked.
     *
     * It used to hang off the panel's left edge, halfway down - which is where the
     * game stacks the potion-effect boxes, so with a few effects up a dark tab sat on
     * a dark box and could not be found. The top of the category column is the one
     * strip of the panel nothing else uses.
     */
    private static int[] tab(AbstractContainerScreen<?> s) {
        Frame f = frame(s);
        if (shown) {
            // Just under the recent list, against the grid; without a recent column
            // (a narrow screen) it goes under the categories instead.
            if (f.recentW() > 0) {
                int w = 34;
                return new int[]{f.recentX() + f.recentW() + 1 - w, f.gridY() - 16 + recentPaneH(f) + 4, w, 16};
            }
            return new int[]{f.catX() - 3, f.gridY() + categories().size() * CELL + 6, CATS + 1, 16};
        }
        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        return new int[]{sw - TAB_W - 1, f.y + f.h / 2 - TAB_H / 2, TAB_W, TAB_H};
    }

    private static void drawTab(AbstractContainerScreen<?> s, GuiGraphicsExtractor g, Font font, int mx, int my) {
        int[] t = tab(s);
        boolean hover = mx >= t[0] && mx < t[0] + t[2] && my >= t[1] && my < t[1] + t[3];
        // Accent, not surface: it can land on a potion-effect box, and there a dark
        // chip on a dark box is how it went missing the first time.
        if (shown) {
            Draw.roundedRect(g, t[0], t[1], t[2], t[3], 4, Draw.alpha(Theme.accent(), hover ? 1f : 0.25f));
        } else {
            Draw.roundedRect(g, t[0], t[1], t[2], t[3], 4, Draw.alpha(Theme.accent(), hover ? 1f : 0.25f), true, false, true, false);
        }
        Draw.textCentered(g, font, shown ? "›" : "‹", t[0] + t[2] / 2, t[1] + t[3] / 2 - 4, hover ? Theme.bg() : Theme.accent());
    }

    private static void drawGrid(AbstractContainerScreen<?> s, GuiGraphicsExtractor g, Font font, Map<String, Integer> have, int mx, int my) {
        Frame f = frame(s);
        if (glass) {
            // Glass goes round each thing rather than round the lot: one big pane over
            // the world is a dim window, three small ones are shelves you can see past.
            Draw.glass(g, f.catX() - 3, f.gridY() - 3, 23, categories().size() * CELL + 4, 5);
            if (f.recentW() > 0) Draw.glass(g, f.recentX() - 1, f.gridY() - 16, f.recentW() + 2, recentPaneH(f), 5);
            Draw.glass(g, f.gridX() - 4, f.y, f.gridW() + 8, f.h, Theme.RADIUS);
        } else {
            Draw.roundedRect(g, f.x, f.y, f.w, f.h, Theme.RADIUS, Theme.surface());
        }

        List<String> list = visible();
        int perPage = f.cols * f.rows;
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));

        // Header: prev, page, next, over the grid.
        int hy = f.y + 5;
        chip(g, font, "◀", f.gridX(), hy, 14, mx, my);
        chip(g, font, "▶", f.gridX() + f.gridW() - 14, hy, 14, mx, my);
        Draw.textCentered(g, font, (page + 1) + " / " + pages, f.gridX() + f.gridW() / 2, hy + 3, Theme.muted());

        // The categories, down the left edge.
        int cx = f.catX(), cy = f.gridY();
        for (String c : categories()) {
            boolean on = c == null ? cat == null : c.equals(cat);
            boolean hover = mx >= cx && mx < cx + 18 && my >= cy && my < cy + 18;
            slab(g, cx, cy, 18, 18, on ? Theme.accent() : 0, 0.3f, hover);
            ItemStack icon = categoryIcon(c);
            if (icon != null) g.fakeItem(icon, cx + 1, cy + 1);
            else Draw.textCentered(g, font, c == null ? "All" : c.substring(0, 1), cx + 9, cy + 5, on ? Theme.accent() : Theme.muted());
            if (on) Draw.rect(g, cx - 3, cy + 3, 1, 12, Theme.accent());
            if (hover) {
                pendingTip = List.of(c == null ? "All" : c);
                tipX = mx;
                tipY = my;
            }
            cy += CELL;
        }
        if (f.recentW() > 0) drawRecent(g, font, f, have, mx, my);
        // The grid.
        int gx = f.gridX(), gy = f.gridY();
        for (int i = 0; i < perPage; i++) {
            int idx = page * perPage + i;
            if (idx >= list.size()) break;
            int ix = gx + (i % f.cols) * CELL, iy = gy + (i / f.cols) * CELL;
            boolean hover = mx >= ix && mx < ix + CELL && my >= iy && my < iy + CELL;
            Recipe r = RECIPES.get(list.get(idx));
            boolean open = r.name().equals(openRecipe);
            int[] rd = ready(r, have);
            boolean done = complete(rd);
            slab(g, ix + 1, iy + 1, CELL - 2, CELL - 2, open ? Theme.accent() : done ? Theme.pos() : 0,
                    open ? 0.35f : 0.26f, hover);
            if (done && !glass) Draw.roundedOutline(g, ix + 1, iy + 1, CELL - 2, CELL - 2, 3,
                    Theme.pos(), Draw.alpha(Theme.pos(), open ? 0.35f : 0.12f));
            ItemStack icon = ICONS.get(r.name());
            if (icon != null) g.fakeItem(icon, ix + 2, iy + 2);
            else Draw.text(g, font, r.name().substring(0, 1), ix + 7, iy + 6, Theme.text());
            if (ForgeRecipes.isForge(r.name())) Draw.text(g, font, "F", ix + CELL - 8, iy + 2, Theme.accent());
            if (hover) tooltip(g, font, r, have, mx, my);
        }

        // The two keys, said once where the eye lands anyway; nobody reads a settings
        // description to learn a keybind.
        Draw.textCentered(g, font, "R: recipe   U: uses  -  over any item", f.gridX() + f.gridW() / 2,
                f.y + f.h - FOOT + 1, Theme.dim());

        // Footer: the search box. The widget draws itself with the screen, UNDER this
        // panel, so what it wrote is painted over by now - the frame, the text and the
        // caret are drawn again here on top; the widget is only kept for the typing.
        int by = f.y + f.h - FOOT + 13;
        boolean focused = box != null && box.isFocused();
        Draw.roundedOutline(g, f.gridX(), by, f.gridW(), 17, 5,
                focused || !query.isEmpty() ? Theme.accent() : Draw.alpha(Theme.line(), 0.8f), Theme.input());
        String shown = query.isEmpty() && !focused ? "Search recipes…" : query;
        int tx = f.gridX() + 5, ty = by + 5;
        Draw.text(g, font, fit(font, shown, f.gridW() - 12), tx, ty, query.isEmpty() ? Theme.dim() : Theme.text());
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            Draw.rect(g, tx + font.width(fit(font, query, f.gridW() - 12)) + 1, ty - 1, 1, 10, Theme.text());
        }
    }

    /** How many recent rows fit and exist. */
    private static int recentCount(Frame f) {
        return Math.min(recentRows().size(), Math.min(RECENT_MAX, Math.max(1, f.gridH() / RECENT_ROW)));
    }

    /** The recent list's pane: its label, its rows, or "none". */
    private static int recentPaneH(Frame f) {
        int n = recentCount(f);
        return 16 + (n == 0 ? 14 : n * RECENT_ROW) + 2;
    }

    private static void drawRecent(GuiGraphicsExtractor g, Font font, Frame f, Map<String, Integer> have, int mx, int my) {
        int x = f.recentX(), y = f.gridY();
        Draw.text(g, font, "Recent", x + 2, y - 13, Theme.muted());
        List<String> list = recentRows();
        int max = recentCount(f);
        if (max == 0) {
            Draw.text(g, font, "none", x + 2, y + 5, Theme.dim());
            return;
        }
        for (int i = 0; i < max; i++) {
            String name = list.get(i);
            Recipe r = RECIPES.get(name);
            if (r == null) continue;
            int ry = y + i * RECENT_ROW;
            boolean hover = mx >= x && mx < x + f.recentW() && my >= ry && my < ry + RECENT_ROW;
            int[] rd = ready(r, have);
            boolean done = complete(rd);
            boolean isOpen = name.equals(openRecipe);
            slab(g, x, ry, f.recentW(), RECENT_ROW - 1, isOpen ? Theme.accent() : done ? Theme.pos() : 0,
                    isOpen ? 0.32f : 0.18f, hover);
            ItemStack icon = ICONS.get(name);
            if (icon != null) g.fakeItem(icon, x + 1, ry + 1);
            Draw.text(g, font, fit(font, name, f.recentW() - 22), x + 20, ry + 5,
                    done ? Theme.pos() : Theme.text());
            if (hover) tooltip(g, font, r, have, mx, my);
        }
    }

    /**
     * The slab an item sits on. Solid: a raised block, tinted when it has something to
     * say. Glass: just a rim round the item, so what shows through is the world, not a
     * block of colour - a tint goes on the rim and, faintly, inside it.
     */
    private static void slab(GuiGraphicsExtractor g, int x, int y, int w, int h, int tint, float tintAlpha, boolean hover) {
        if (!glass) {
            Draw.roundedRect(g, x, y, w, h, 3, tint != 0 ? Draw.alpha(tint, hover ? tintAlpha + 0.15f : tintAlpha)
                    : hover ? Theme.hover() : Theme.raised());
            return;
        }
        int rim = tint != 0 ? Draw.alpha(tint, hover ? 1f : 0.85f) : Draw.alpha(Theme.text(), hover ? 0.45f : 0.16f);
        // Four strips with the corner pixels left off, not two rounded shapes: a rounded
        // outline is fourteen fills and there are a hundred-odd cells a frame.
        Draw.rect(g, x + 1, y, w - 2, 1, rim);
        Draw.rect(g, x + 1, y + h - 1, w - 2, 1, rim);
        Draw.rect(g, x, y + 1, 1, h - 2, rim);
        Draw.rect(g, x + w - 1, y + 1, 1, h - 2, rim);
        if (tint != 0 || hover) Draw.rect(g, x + 1, y + 1, w - 2, h - 2,
                tint != 0 ? Draw.alpha(tint, hover ? 0.3f : 0.16f) : Draw.alpha(Theme.text(), 0.1f));
    }

    private static int chip(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w, int mx, int my) {
        int cw = w > 0 ? w : font.width(text) + 10;
        boolean hover = mx >= x && mx < x + cw && my >= y && my < y + 14;
        Draw.roundedRect(g, x, y, cw, 14, 4, hover ? Theme.hover() : Theme.raised());
        Draw.textCentered(g, font, text, x + cw / 2, y + 3, hover ? Theme.text() : Theme.muted());
        return cw;
    }

    private static String tipFor;
    private static Map<String, Integer> tipHave;
    private static List<String> tipLines;

    /**
     * Kept while the mouse stays on the same cell and the holdings are the same count:
     * building it means the game's own tooltip plus the whole build tree, which is a
     * lot to do four hundred times a second for a box that does not change.
     */
    private static void tooltip(GuiGraphicsExtractor g, Font font, Recipe r, Map<String, Integer> have, int mx, int my) {
        if (r.name().equals(tipFor) && have == tipHave) {
            pendingTip = tipLines;
            tipX = mx;
            tipY = my;
            return;
        }
        tipFor = r.name();
        tipHave = have;
        List<String> lines = new ArrayList<>();
        // The item as the game would show it - its stats and what it does - because
        // "what does this even give" is the question a grid of icons raises first.
        ItemStack icon = ICONS.get(r.name());
        if (icon != null) {
            Minecraft mc = Minecraft.getInstance();
            for (Component c : icon.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(mc.level), mc.player,
                    net.minecraft.world.item.TooltipFlag.NORMAL)) {
                lines.add(c.getString());
            }
            lines.add("");
        } else {
            lines.add(r.name());
        }
        ForgeRecipes.Info forge = ForgeRecipes.info(r.name());
        if (forge != null) {
            lines.add("§6Forge craft: " + forge.category() + (forge.duration().isBlank() ? "" : " • " + forge.duration()));
            Work work = work(r.name(), 1, have, new HashSet<>());
            if (work.total() > forge.durationMs()) lines.add("§eFull build: " + time(work.wall()) + " on " + FORGE_SLOTS + " slots");
            List<String> missing = pooledMissingLines(fullCost(r, have), have, 4);
            if (!missing.isEmpty()) {
                lines.add("§cMissing pooled:");
                lines.addAll(missing);
            }
            if (!forge.requirement().isBlank()) lines.add("§7" + forge.requirement());
            lines.add("");
        }
        for (Ingredient i : r.needs()) {
            int got = have.getOrDefault(i.name(), 0);
            lines.add((got >= i.count() ? "§a" : "§c")
                    + ingredientCount(i.name(), got, i.count()) + " §7" + i.name());
        }
        lines.add("§8R / click: recipe   U / right-click: uses");
        tipLines = lines;
        pendingTip = lines;
        tipX = mx;
        tipY = my;
    }

    /**
     * Lines for the server's own Forge tooltip: how long the whole build takes and what
     * it comes down to in materials, against what you hold.
     *
     * No heading and no "Craft: ... (56m)" - the lore above already says the duration,
     * and a second box over the top of the lore hid it. The time counts only crafts still
     * to do: the old sum walked every sub-craft of the tree as if none were in the bag and
     * one at a time, which put a 7-day figure on an engine whose parts were mostly
     * already refined.
     */
    public static List<String> forgeBreakdown(String item, int maxRows) {
        if (item.isBlank()) return List.of();
        Recipe r = RECIPES.get(item);
        if (r == null) return List.of();
        Map<String, Integer> have = holdings();
        List<String> lines = new ArrayList<>();
        // The one you are holding is built; the materials below would be for a second one.
        if (have.getOrDefault(r.name(), 0) > 0) return List.of("§aOwned");
        ForgeRecipes.Info forge = ForgeRecipes.info(r.name());
        long own = forge == null ? 0 : forge.durationMs();
        Work work = work(r.name(), 1, have, new HashSet<>());
        if (work.total() > own) lines.add("§7Full build: §e" + time(work.wall()) + " §8on " + FORGE_SLOTS + " slots");
        List<Ingredient> cost = fullCost(r, have);
        int rows = 0;
        for (Ingredient i : cost) {
            if (rows++ >= maxRows) {
                lines.add("§8...");
                break;
            }
            lines.add(materialBreakdownLine(i, have));
        }
        return lines;
    }

    /** Forge slots that can run at once; what the build time is spread over. */
    private static final int FORGE_SLOTS = 7;

    /** Forge time for a subtree: the serial sum, and the wall time with the slots in use. */
    private record Work(long total, long wall) {
    }

    /**
     * Time to forge {@code count} of an item, counting only what is not already held.
     *
     * Whole items of the exact tier come off the count - a Refined Obsidian in the bag is
     * one fewer craft - but raw pooled below it does not: it still has to go through the
     * Forge. Sub-crafts run alongside each other, so a level of the tree takes the longer
     * of its longest chain and its total spread over the slots, and the item itself waits
     * for all of it.
     */
    private static Work work(String item, int count, Map<String, Integer> have, Set<String> path) {
        Recipe r = RECIPES.get(item);
        if (r == null || count <= 0 || !path.add(item)) return new Work(0, 0);
        ForgeRecipes.Info info = ForgeRecipes.info(item);
        long dur = info == null ? 0 : info.durationMs();
        int crafts = (count + r.count() - 1) / r.count();
        long subTotal = 0, subWall = 0;
        for (Ingredient i : r.needs()) {
            int missing = i.count() * crafts - have.getOrDefault(i.name(), 0);
            Work w = work(i.name(), missing, have, path);
            subTotal += w.total();
            subWall = Math.max(subWall, w.wall());
        }
        path.remove(item);
        long ownWall = dur * ((crafts + FORGE_SLOTS - 1) / FORGE_SLOTS);
        return new Work(dur * crafts + subTotal, ownWall + Math.max(subWall, subTotal / FORGE_SLOTS));
    }

    private static String materialBreakdownLine(Ingredient i, Map<String, Integer> have) {
        long unit = tierUnit(i.name());
        String family = materialFamily(i.name());
        if (unit <= 0 || family.isBlank()) {
            int got = have.getOrDefault(i.name(), 0);
            return (got >= i.count() ? "§a" : "§c")
                    + ingredientCount(i.name(), got, i.count()) + " §7" + ingredientName(i.name());
        }
        long needRaw = (long) i.count() * unit;
        long haveRaw = pooledRaw(family, have);
        long haveUnits = Math.min(i.count(), haveRaw / unit);
        return (haveRaw >= needRaw ? "§a" : "§c")
                + ingredientCount(i.name(), haveUnits, i.count()) + " §7" + ingredientName(i.name())
                + " §8pooled";
    }

    /** The item's own tooltip - name and lore, as the game would show it - for a cell. */
    private static void itemTip(ItemStack s, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        List<String> lines = new ArrayList<>();
        for (Component c : s.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(mc.level), mc.player,
                net.minecraft.world.item.TooltipFlag.NORMAL)) {
            lines.add(c.getString());
        }
        pendingTip = lines;
        tipX = mx;
        tipY = my;
    }

    /**
     * Tooltips are drawn by us, last, over everything. The game's own tooltip hook
     * did nothing from here: by the time this panel draws, the screen's tooltip pass
     * has been and gone, so a tooltip handed to it was a tooltip for nobody. One
     * pending tooltip per frame, whoever asked last.
     */
    private static List<String> pendingTip;
    private static int tipX, tipY;

    private static void drawTip(GuiGraphicsExtractor g, Font font) {
        if (pendingTip == null || pendingTip.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int w = 0;
        for (String l : pendingTip) w = Math.max(w, font.width(l));
        int h = pendingTip.size() * 10 + 6;
        int x = tipX + 12, y = tipY - 12;
        if (x + w + 8 > sw) x = tipX - w - 16;
        if (y + h > sh) y = sh - h;
        if (y < 0) y = 0;
        Draw.roundedRect(g, x - 1, y - 1, w + 10, h + 2, 4, Theme.accent());
        Draw.roundedRect(g, x, y, w + 8, h, 4, 0xF0100010);
        int ly = y + 4;
        for (int i = 0; i < pendingTip.size(); i++) {
            Draw.text(g, font, pendingTip.get(i), x + 4, ly, i == 0 ? Theme.text() : 0xFFAAAAAA);
            ly += 10;
        }
        pendingTip = null;
    }

    /**
     * The left box. A recipe: its grid as the server draws it, counts on the stacks,
     * the needs beside it with your count over the recipe's, then everything the
     * result itself goes into. An item's uses: a grid of the recipes that take it.
     */
    private static void drawViewer(AbstractContainerScreen<?> s, GuiGraphicsExtractor g, Font font, Map<String, Integer> have, int mx, int my) {
        Viewer v = viewer(s);
        if (glass) Draw.glass(g, v.x, v.y, v.w, v.h, Theme.RADIUS);
        else Draw.roundedRect(g, v.x, v.y, v.w, v.h, Theme.RADIUS, Theme.surface());
        int x = v.x + PAD, y = v.y + 5;
        if (!trail.isEmpty()) chip(g, font, "◀ back", x, y, 0, mx, my);
        chip(g, font, "✕", v.x + v.w - PAD - 14, y, 14, mx, my);
        if (openRecipe != null) {
            String label = craftLabel(s);
            chip(g, font, label, v.x + v.w - PAD - 14 - 4 - font.width(label) - 10, y, 0, mx, my);
        }

        if (openRecipe != null) {
            Recipe r = RECIPES.get(openRecipe);
            if (r == null) return;
            int ny = y + 20;
            ItemStack icon = ICONS.get(r.name());
            if (icon != null) g.fakeItem(icon, x, ny - 4);
            Draw.text(g, font, fit(font, r.name(), v.w - PAD * 2 - 22), x + 20, ny, Theme.text());
            if (r.count() > 1) Draw.textRight(g, font, "x" + r.count(), v.x + v.w - PAD, ny, Theme.muted());
            ForgeRecipes.Info forge = ForgeRecipes.info(r.name());
            int forgeLine = forge == null ? 0 : 10;
            if (forge != null) {
                String line = "Forge: " + forge.category() + (forge.duration().isBlank() ? "" : " • " + forge.duration());
                Draw.text(g, font, fit(font, line, v.w - PAD * 2 - 22), x + 20, ny + 10, Theme.accent());
            }
            // What the numbers count, said in words, and a click away from the other;
            // beside it, whether the list is the recipe's own cells or everything under them.
            // Two rows, not one: side by side they ran off the box, and a chip that
            // hangs outside it lights up but takes no click.
            chip(g, font, "Counting: " + scope.toLowerCase(), x, ny + 12 + forgeLine, 0, mx, my);
            chip(g, font, fullCost ? "Cost: full" : "Cost: recipe", x, ny + 12 + forgeLine + SCOPE_H, 0, mx, my);

            int gx = x, gy = ny + 14 + forgeLine + 2 * SCOPE_H;
            int lx = gx + 3 * CELL + 6, ly = gy + 2;
            List<Ingredient> list = fullCost ? fullCost(r, have) : r.needs();
            // The list's height is needed before it is drawn, for the pane under it.
            int listH = 0;
            for (Ingredient in : list) {
                listH += ingredientRowH(font, v, lx,
                        ingredientCount(in.name(), have.getOrDefault(in.name(), 0), in.count()), ingredientName(in.name()));
            }
            int uy = Math.max(ly + listH, gy + 3 * CELL) + 6;
            if (glass) {
                Draw.glassInner(g, gx - 3, gy - 3, v.w - PAD * 2 + 6, uy - gy, 5);
                Draw.glassInner(g, x - 3, uy - 3, v.w - PAD * 2 + 6, v.y + v.h - 1 - uy, 5);
            }
            for (int i = 0; i < 9; i++) {
                int cx = gx + (i % 3) * CELL, cy = gy + (i / 3) * CELL;
                Ingredient in = r.grid()[i];
                boolean hover = in != null && mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL;
                slab(g, cx + 1, cy + 1, CELL - 2, CELL - 2, 0, 0, hover);
                if (in == null) continue;
                ItemStack st = ICONS.get(in.name());
                if (st != null) {
                    g.fakeItem(st, cx + 2, cy + 2);
                    if (in.count() > 1) g.itemDecorations(font, st.copyWithCount(in.count()), cx + 2, cy + 2);
                } else {
                    Draw.text(g, font, String.valueOf(in.count()), cx + 6, cy + 6, Theme.text());
                }
                if (RECIPES.containsKey(in.name())) Draw.rect(g, cx + 2, cy + CELL - 3, CELL - 4, 1, Theme.accent());
                if (hover && st != null) itemTip(st, mx, my);
            }
            if (list.isEmpty()) Draw.text(g, font, "nothing needed", lx, ly, Theme.pos());
            for (Ingredient in : list) {
                int got = have.getOrDefault(in.name(), 0);
                String n = ingredientCount(in.name(), got, in.count());
                String displayName = ingredientName(in.name());
                int rowH = ingredientRowH(font, v, lx, n, displayName);
                Draw.text(g, font, n, lx, ly, got >= in.count() ? Theme.pos() : Theme.neg());
                if (rowH > 10) {
                    Draw.text(g, font, fit(font, displayName, v.x + v.w - PAD - lx), lx, ly + 10, Theme.text());
                } else {
                    Draw.text(g, font, fit(font, displayName, v.x + v.w - PAD - lx - font.width(n) - 4), lx + font.width(n) + 4, ly, Theme.text());
                }
                if (mx >= lx && mx < v.x + v.w - PAD && my >= ly - 1 && my < ly + rowH - 1) {
                    ItemStack st = ICONS.get(in.name());
                    if (st != null) itemTip(st, mx, my);
                    else {
                        pendingTip = List.of(in.name());
                        tipX = mx;
                        tipY = my;
                    }
                }
                ly += rowH;
            }
            usesGrid(g, font, v, r.name(), have, x, uy, mx, my, "Used in");
        } else {
            if (glass) Draw.glassInner(g, x - 3, y + 31, v.w - PAD * 2 + 6, v.y + v.h - 5 - (y + 31), 5);
            ItemStack icon = ICONS.get(openUses);
            if (icon != null) g.fakeItem(icon, x, y + 16);
            Draw.text(g, font, fit(font, openUses, v.w - PAD * 2 - 22), x + 20, y + 20, Theme.text());
            usesGrid(g, font, v, openUses, have, x, y + 34, mx, my, "Used in");
        }
    }

    /**
     * The one button that crafts. Away from a crafting table it sends /craft, which
     * opens one; at a crafting table it fills the grid from your inventory, one cell
     * at a time through the same clicks you would make - pick the stack up, put the
     * cell's count down, put the rest back - so the result appears as it would by hand.
     */
    private static String craftLabel(AbstractContainerScreen<?> s) {
        if (openRecipe != null && ForgeRecipes.isForge(openRecipe)) return "Forge craft";
        return s instanceof CraftingScreen ? "Fill grid" : "/craft";
    }

    private static void craft(AbstractContainerScreen<?> s, Recipe r) {
        if (ForgeRecipes.isForge(r.name())) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.getConnection() != null) mc.getConnection().sendCommand("forge");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        if (!(s instanceof CraftingScreen)) {
            mc.getConnection().sendCommand("craft");
            return;
        }
        List<Slot> slots = s.getMenu().slots;
        int id = s.getMenu().containerId;
        Container inv = mc.player.getInventory();
        int missing = 0;
        for (int cell = 0; cell < 9; cell++) {
            Ingredient in = r.grid()[cell];
            if (in == null) continue;
            int gridSlot = 1 + cell;                     // vanilla: 0 is the result, 1-9 the grid
            if (!slots.get(gridSlot).getItem().isEmpty()) continue;   // already placed
            int from = -1;
            for (int i = 0; i < slots.size(); i++) {
                Slot sl = slots.get(i);
                if (sl.container != inv) continue;
                ItemStack st = sl.getItem();
                if (!st.isEmpty() && name(st).equals(in.name()) && st.getCount() >= in.count()) {
                    from = i;
                    break;
                }
            }
            if (from < 0) {
                missing++;
                continue;
            }
            int have = slots.get(from).getItem().getCount();
            mc.gameMode.handleContainerInput(id, from, 0, ContainerInput.PICKUP, mc.player);
            if (have == in.count()) {
                mc.gameMode.handleContainerInput(id, gridSlot, 0, ContainerInput.PICKUP, mc.player);
            } else {
                for (int n = 0; n < in.count(); n++) {
                    mc.gameMode.handleContainerInput(id, gridSlot, 1, ContainerInput.PICKUP, mc.player);
                }
                mc.gameMode.handleContainerInput(id, from, 0, ContainerInput.PICKUP, mc.player);
            }
        }
        if (missing > 0) Toast.warn("Recipes", missing + " ingredient" + (missing == 1 ? "" : "s") + " not in your inventory");
    }

    private static List<Recipe> usesOf(String item) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : RECIPES.values()) if (r.uses(item)) out.add(r);
        return out;
    }

    private static int usesCols(Viewer v) {
        return Math.max(3, (v.w - PAD * 2) / CELL);
    }

    private static void usesGrid(GuiGraphicsExtractor g, Font font, Viewer v, String item, Map<String, Integer> have,
                                 int x, int y, int mx, int my, String label) {
        List<Recipe> uses = usesOf(item);
        Draw.text(g, font, label + (uses.isEmpty() ? ": nothing known" : ""), x, y, Theme.muted());
        int cols = usesCols(v), gy = y + 11, i = 0;
        for (Recipe r : uses) {
            int cx = x + (i % cols) * CELL, cy = gy + (i / cols) * CELL;
            if (cy + CELL > v.y + v.h) break;
            boolean hover = mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL;
            slab(g, cx + 1, cy + 1, CELL - 2, CELL - 2, 0, 0, hover);
            ItemStack st = ICONS.get(r.name());
            if (st != null) g.fakeItem(st, cx + 2, cy + 2);
            if (hover) tooltip(g, font, r, have, mx, my);
            i++;
        }
    }

    private static String fit(Font font, String s, int w) {
        if (font.width(s) <= w) return s;
        while (s.length() > 1 && font.width(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static int ingredientRowH(Font font, Viewer v, int lx, String count, String name) {
        int avail = v.x + v.w - PAD - lx;
        return font.width(count + " " + name) <= avail ? 10 : 20;
    }

    private static String ingredientCount(String item, long got, long need) {
        if (exactTierItem(item)) return got + "/" + need;
        String a = MiningSession.formatItemCount(item, got);
        String b = MiningSession.formatItemCount(item, need);
        String au = unit(a), bu = unit(b);
        if (!au.isEmpty() && au.equals(bu)) {
            return stripUnit(a, au) + "/" + stripUnit(b, bu) + " " + au;
        }
        return a + "/" + b;
    }

    private static List<String> pooledMissingLines(List<Ingredient> needs, Map<String, Integer> have, int max) {
        List<String> out = new ArrayList<>();
        for (Ingredient i : needs) {
            long unit = tierUnit(i.name());
            String family = materialFamily(i.name());
            if (unit <= 0 || family.isBlank()) {
                int got = have.getOrDefault(i.name(), 0);
                if (got < i.count()) out.add("§c" + (i.count() - got) + "x §7" + ingredientName(i.name()));
            } else {
                long needRaw = (long) i.count() * unit;
                long haveRaw = pooledRaw(family, have);
                if (haveRaw < needRaw) {
                    long missing = (long) Math.ceil((needRaw - haveRaw) / (double) unit);
                    out.add("§c" + missing + "x §7" + ingredientName(i.name()));
                }
            }
            if (out.size() >= max) {
                out.add("§8...");
                break;
            }
        }
        return out;
    }

    private static long pooledRaw(String family, Map<String, Integer> have) {
        long raw = 0;
        for (Map.Entry<String, Integer> e : have.entrySet()) {
            if (materialFamily(e.getKey()).equals(family)) raw += (long) e.getValue() * Math.max(1, tierUnit(e.getKey()));
        }
        return raw;
    }

    /**
     * Only the material itself and its Enchanted/Refined forms pool. Matching any name
     * with "obsidian" in it made an Obsidian Drill OD-355 count as 1/160 of an Enchanted
     * Obsidian - "0/0.01 Ench Obsidian Drill OD-355" on a recipe.
     */
    private static String materialFamily(String item) {
        String lower = item.toLowerCase(Locale.ROOT).replace("enchanted ", "").replace("refined ", "").trim();
        switch (lower) {
            case "crying obsidian": return "crying obsidian";
            case "obsidian": return "obsidian";
            case "end stone": return "end stone";
            case "rough amethyst", "flawed amethyst", "fine amethyst", "flawless amethyst", "perfect amethyst", "amethyst": return "amethyst";
            default: return "";
        }
    }

    private static long tierUnit(String item) {
        String lower = item.toLowerCase(Locale.ROOT);
        if (lower.contains("perfect ")) return 80L * 80L * 80L * 5L;
        if (lower.contains("flawless ")) return 80L * 80L * 80L;
        if (lower.contains("fine ")) return 80L * 80L;
        if (lower.contains("flawed ")) return 80L;
        if (lower.contains("refined ")) return 160L * 16L;
        if (lower.contains("enchanted ")) return 160L;
        return materialFamily(item).isBlank() ? 0 : 1;
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86_400; s %= 86_400;
        long h = s / 3_600; s %= 3_600;
        long m = s / 60; s %= 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    private static boolean exactTierItem(String item) {
        String lower = item.toLowerCase(Locale.ROOT);
        return lower.contains("refined ") || lower.contains("enchanted ")
                || lower.contains("rough ") || lower.contains("flawed ")
                || lower.contains("fine ") || lower.contains("flawless ")
                || lower.contains("perfect ");
    }

    private static String ingredientName(String name) {
        return name.replace("Refined ", "Ref. ")
                .replace("Enchanted ", "Ench. ")
                .replace("Crying Obsidian", "Crying Obs.");
    }

    private static String unit(String s) {
        int i = s.lastIndexOf(' ');
        if (i < 0 || i == s.length() - 1) return "";
        String u = s.substring(i + 1);
        for (int c = 0; c < u.length(); c++) if (!Character.isLetter(u.charAt(c))) return "";
        return u;
    }

    private static String stripUnit(String s, String unit) {
        return s.endsWith(" " + unit) ? s.substring(0, s.length() - unit.length() - 1) : s;
    }

    // ── input ─────────────────────────────────────────────────────────────────

    private static boolean click(AbstractContainerScreen<?> s, double mx, double my, int button) {
        if (!enabled || !panel || RECIPES.isEmpty()) return false;
        int[] tb = tab(s);
        if (mx >= tb[0] && mx < tb[0] + tb[2] && my >= tb[1] && my < tb[1] + tb[3]) {
            shown = !shown;
            if (box != null) box.setFocused(false);
            return true;
        }
        if (!shown) return false;
        Frame f = frame(s);
        boolean inGrid = mx >= f.x && mx < f.x + f.w && my >= f.y && my < f.y + f.h;
        Viewer v = viewer(s);
        boolean inViewer = (openRecipe != null || openUses != null)
                && mx >= v.x && mx < v.x + v.w && my >= v.y && my < v.y + v.h;
        if (!inGrid && !inViewer) {
            if (box != null) box.setFocused(false);
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        if (inViewer) return clickViewer(s, v, font, mx, my, button);

        // Header arrows.
        int hy = f.y + 5;
        if (my >= hy && my < hy + 14) {
            if (mx >= f.gridX() && mx < f.gridX() + 14) page--;
            else if (mx >= f.gridX() + f.gridW() - 14 && mx < f.gridX() + f.gridW()) page++;
            return true;
        }
        // Search box: NOT consumed. The screen's own click is what focuses a widget
        // in its eyes, and only a widget it sees as focused gets the typed characters
        // - focusing the box from here made the caret blink and nothing else.
        int by = f.y + f.h - FOOT + 13;
        if (my >= by && my < by + 17 && mx >= f.gridX() && mx < f.gridX() + f.gridW()) {
            if (box != null) box.setFocused(true);
            return true;
        }
        if (box != null) box.setFocused(false);
        // Most recent recipe shortcuts.
        if (f.recentW() > 0 && mx >= f.recentX() && mx < f.recentX() + f.recentW()
                && my >= f.gridY() && my < f.gridY() + f.gridH()) {
            int i = (int) ((my - f.gridY()) / RECENT_ROW);
            List<String> recent = recentRows();
            int max = Math.min(recent.size(), Math.min(RECENT_MAX, Math.max(1, f.gridH() / RECENT_ROW)));
            if (i >= 0 && i < max) {
                String name = recent.get(i);
                focusRecipeInGrid(name, f);
                openRecipe(name);
            }
            return true;
        }
        // Categories.
        if (mx >= f.catX() && mx < f.catX() + 18) {
            int i = (int) ((my - f.gridY()) / CELL);
            List<String> cats = categories();
            if (my >= f.gridY() && i >= 0 && i < cats.size()) {
                cat = cats.get(i);
                page = 0;
            }
            return true;
        }
        // The grid.
        List<String> list = visible();
        int perPage = f.cols * f.rows;
        int gx = f.gridX(), gy = f.gridY();
        if (mx >= gx && mx < gx + f.gridW() && my >= gy && my < gy + f.gridH()) {
            int i = (int) ((my - gy) / CELL) * f.cols + (int) ((mx - gx) / CELL);
            int idx = page * perPage + i;
            if (idx < list.size()) {
                if (button == 1) openUses(list.get(idx));
                else openRecipe(list.get(idx));
                return true;
            }
            return false;
        }
        return false;
    }

    private static boolean clickViewer(AbstractContainerScreen<?> s, Viewer v, Font font, double mx, double my, int button) {
        int x = v.x + PAD, y = v.y + 5;
        if (my >= y && my < y + 14) {
            if (mx >= v.x + v.w - PAD - 14) {
                close();
                return true;
            }
            if (!trail.isEmpty() && mx < x + font.width("◀ back") + 10) {
                back();
                return true;
            }
            if (openRecipe != null) {
                String label = craftLabel(s);
                int cx = v.x + v.w - PAD - 14 - 4 - font.width(label) - 10;
                if (mx >= cx && mx < cx + font.width(label) + 10) {
                    Recipe r = RECIPES.get(openRecipe);
                    if (r != null) craft(s, r);
                    return true;
                }
            }
        }
        if (openRecipe != null) {
            Recipe r = RECIPES.get(openRecipe);
            if (r == null) {
                close();
                return true;
            }
            int forgeLine = ForgeRecipes.isForge(r.name()) ? 10 : 0;
            int cy0 = y + 20 + 12 + forgeLine;
            int cw = font.width("Counting: " + scope.toLowerCase()) + 10;
            if (my >= cy0 && my < cy0 + 14 && mx < x + cw) {
                scope = ALL.equals(scope) ? INV : ALL;
                return true;
            }
            int cy1 = cy0 + SCOPE_H;
            int fw = font.width(fullCost ? "Cost: full" : "Cost: recipe") + 10;
            if (my >= cy1 && my < cy1 + 14 && mx < x + fw) {
                fullCost = !fullCost;
                return true;
            }
            int gx = x, gy = y + 20 + 14 + forgeLine + 2 * SCOPE_H;
            for (int i = 0; i < 9; i++) {
                int cx = gx + (i % 3) * CELL, cy = gy + (i / 3) * CELL;
                Ingredient in = r.grid()[i];
                if (in != null && mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL) {
                    if (button == 1) openUses(in.name());
                    else if (RECIPES.containsKey(in.name())) openRecipe(in.name());
                    return true;
                }
            }
            Map<String, Integer> have = holdings();
            List<Ingredient> list = fullCost ? fullCost(r, have) : r.needs();
            int ly = gy + 2;
            for (Ingredient in : list) {
                int got = have.getOrDefault(in.name(), 0);
                ly += ingredientRowH(font, v, x + 3 * CELL + 6,
                        ingredientCount(in.name(), got, in.count()), in.name());
            }
            int uy = Math.max(ly, gy + 3 * CELL) + 6 + 11;
            clickUses(v, r.name(), x, uy, mx, my);
        } else {
            clickUses(v, openUses, x, y + 34 + 11, mx, my);
        }
        return true;
    }

    private static void clickUses(Viewer v, String item, int x, int gy, double mx, double my) {
        int cols = usesCols(v), i = 0;
        for (Recipe r : usesOf(item)) {
            int cx = x + (i % cols) * CELL, cy = gy + (i / cols) * CELL;
            if (mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL) {
                openRecipe(r.name());
                return;
            }
            i++;
        }
    }

    private static void openRecipe(String name) {
        touchRecent(name);
        if (name.equals(openRecipe)) return;
        if (openRecipe != null) trail.add("r:" + openRecipe);
        else if (openUses != null) trail.add("u:" + openUses);
        openRecipe = name;
        openUses = null;
    }

    private static void openUses(String name) {
        if (name.equals(openUses)) return;
        if (openRecipe != null) trail.add("r:" + openRecipe);
        else if (openUses != null) trail.add("u:" + openUses);
        openUses = name;
        openRecipe = null;
    }

    /** Back up the trail of what was opened, so a chain of craft-ups unwinds one step at a time. */
    private static void back() {
        openRecipe = null;
        openUses = null;
        if (trail.isEmpty()) return;
        String last = trail.remove(trail.size() - 1);
        if (last.startsWith("r:")) openRecipe = last.substring(2);
        else openUses = last.substring(2);
    }

    private static void close() {
        openRecipe = null;
        openUses = null;
        trail.clear();
    }

    private static boolean scroll(AbstractContainerScreen<?> s, double mx, double my, double dy) {
        if (!enabled || !panel || !shown) return false;
        Frame f = frame(s);
        if (mx < f.x || mx >= f.x + f.w || my < f.y || my >= f.y + f.h) return false;
        page -= (int) Math.signum(dy);
        return true;
    }
}
