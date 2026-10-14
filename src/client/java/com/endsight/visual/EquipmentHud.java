package com.endsight.visual;

import com.endsight.hud.Area;
import com.endsight.hud.HudLayout;
import com.endsight.hud.HudPlacementScreen;
import com.endsight.storage.Recipes;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Current worn armor and the server's selected pet, as two independently movable HUDs. */
public final class EquipmentHud {
    private static final String ARMOR_ID = "hud.armor", PET_ID = "hud.activePet";
    private static final int PAD = 2, CELL = 20;
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Pattern PET_LINE = Pattern.compile(
            "^(?:Pet\\s*:\\s*|Active Pet\\s*:?\\s*)?\\[Lvl\\s*(\\d{1,4})\\]\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_PET = Pattern.compile("(?i)^(?:Selected\\s+)?Pet\\s*:\\s*None$");
    private static final Pattern HELD_ITEM = Pattern.compile("(?i)^(?:Held|Pet)\\s+Item\\s*:\\s*(.*)$");
    /**
     * "Equipped Combat XP Boost on [Lvl 199] Golden Dragon!"
     *
     * The held item was only ever learned from the Pets menu's lore, so equipping one
     * any other way left the icon showing the previous item until that menu was opened
     * again. The server announces the swap in chat; this reads it.
     */
    private static final Pattern EQUIPPED = Pattern.compile(
            "(?i)^Equipped\\s+(.+?)\\s+on\\s+\\[Lvl\\s*(\\d{1,4})\\]\\s+(.+?)!?$");
    private static final Pattern ACTIVATED = Pattern.compile(
            "(?i)^Activated\\s+(\\[Lvl\\s*\\d{1,4}\\]\\s+.+?)!?$");
    private static final String SELECTED = "Selected Pet:", DEACTIVATE = "Click to deactivate!";
    private static boolean armorEnabled = true, petEnabled = true;
    private static double armorOpacity, petOpacity = 25;
    private static ClientLevel petWorld;
    private static Pet activePet;
    private static long lastPetRead;
    private static AbstractContainerScreen<?> scannedPetMenu;
    private static List<ItemStack> scannedPetStacks = List.of();
    private static HeldItem iconItem;
    private static ItemStack cachedHeldIcon;
    private static ClientLevel iconWorld;
    private static long iconReadAt;
    /**
     * The pet last seen anywhere, kept on disk. Crypts show no Pet line on the sidebar
     * or in tab, so without this the HUD went blank there with a pet still out. The
     * held item comes from the Pets menu's lore: the sidebar and the main menu's Pets
     * button name the pet but never identify the Shelmet, Clover or XP Boost it wears.
     */
    private static Pet lastPet;
    private static HeldItem lastHeldItem;
    private static final PetItems petItems = new PetItems();
    private static boolean lastLoaded;

    record Pet(int level, String name, int colour) { }
    record HeldItem(String name, int colour) { }
    record MenuPet(Pet pet, HeldItem item, boolean selected) { }

    /** Level is deliberately absent: levelling a pet must not erase its known item. */
    static String petKey(Pet pet) {
        return pet.colour() + "|" + pet.name().trim().toLowerCase(Locale.ROOT);
    }

    static final class PetItems {
        private final Map<String, HeldItem> items = new HashMap<>();
        private final Set<String> ambiguous = new HashSet<>();

        boolean remember(Pet pet, HeldItem item) {
            String key = petKey(pet);
            boolean changed = !items.containsKey(key) || !Objects.equals(items.get(key), item);
            items.put(key, item);
            return changed;
        }

        boolean readMenu(List<MenuPet> pets) {
            Set<String> seen = new HashSet<>();
            Map<String, MenuPet> unique = new HashMap<>();
            boolean changed = false;
            for (MenuPet entry : pets) {
                String key = petKey(entry.pet());
                // Sidebar and activation chat expose no pet UUID. Two same-name,
                // same-rarity pets therefore cannot safely share a cached held item.
                if (!seen.add(key)) changed |= ambiguous.add(key);
                unique.putIfAbsent(key, entry);
            }
            for (var entry : unique.entrySet()) {
                // Do not alternate between two duplicate entries and rewrite the
                // cache every poll. The selected entry is applied separately.
                if (ambiguous.contains(entry.getKey())) {
                    if (!items.containsKey(entry.getKey())) {
                        items.put(entry.getKey(), null);
                        changed = true;
                    }
                } else changed |= remember(entry.getValue().pet(), entry.getValue().item());
            }
            return changed;
        }

        HeldItem get(Pet pet) { return ambiguous.contains(petKey(pet)) ? null : items.get(petKey(pet)); }

        HeldItem retain(Pet previous, HeldItem currentItem, Pet current) {
            return previous != null && petKey(previous).equals(petKey(current))
                    ? currentItem : get(current);
        }

        void load(CompoundTag root) {
            for (CompoundTag entry : root.getListOrEmpty("pets").compoundStream().toList()) {
                String key = entry.getStringOr("key", "");
                if (key.isBlank() || key.length() > 120) continue;
                String name = entry.getStringOr("held_item", "");
                if (name.length() > 100) continue;
                items.put(key, name.isBlank() ? null : new HeldItem(name, entry.getIntOr("held_colour", 0xFFFFFFFF)));
                if (entry.getBooleanOr("ambiguous", false)) ambiguous.add(key);
            }
        }

        void save(CompoundTag root) {
            ListTag pets = new ListTag();
            for (String key : items.keySet().stream().sorted().toList()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("key", key);
                HeldItem item = items.get(key);
                if (item != null) {
                    entry.putString("held_item", item.name());
                    entry.putInt("held_colour", item.colour());
                }
                if (ambiguous.contains(key)) entry.putBoolean("ambiguous", true);
                pets.add(entry);
            }
            root.put("pets", pets);
        }
    }

    private static final class Samples {
        static final ItemStack[] ARMOR = {new ItemStack(Items.DIAMOND_HELMET),
                new ItemStack(Items.DIAMOND_CHESTPLATE), new ItemStack(Items.DIAMOND_LEGGINGS),
                new ItemStack(Items.DIAMOND_BOOTS)};
        static final Pet PET = new Pet(200, "Golden Dragon", 0xFFFFAA00);
        static final HeldItem HELD_ITEM = new HeldItem("Lucky Clover", 0xFFAA00AA);
        static final ItemStack HELD_ICON = new ItemStack(Items.LILY_PAD);
    }

    private EquipmentHud() { }

    public static Module armorModule() {
        return new Module(ARMOR_ID, "Armor Display", "Your equipped helmet, chestplate, leggings and boots.", "Visual",
                () -> armorEnabled, value -> armorEnabled = value, List.of(
                new Setting.Slider("Background opacity", "Darkness behind the armor icons.",
                        0, 100, 5, () -> armorOpacity, value -> armorOpacity = value, "%"),
                new Setting.Action("Position", "Move or resize the armor display.", "Move", HudPlacementScreen::open)));
    }

    public static Module petModule() {
        return new Module(PET_ID, "Active Pet", "Your current pet and its held item, remembered where the sidebar hides them.", "Visual",
                () -> petEnabled, value -> petEnabled = value, List.of(
                new Setting.Slider("Background opacity", "Darkness behind the active pet.",
                        0, 100, 5, () -> petOpacity, value -> petOpacity = value, "%"),
                new Setting.Action("Position", "Move or resize the pet display.", "Move", HudPlacementScreen::open)));
    }

    public static void init() {
        HudLayout.register(ARMOR_ID, "Armor Display", .29f, .92f, EquipmentHud::drawArmor);
        HudLayout.register(PET_ID, "Active Pet", .02f, .82f, EquipmentHud::drawPet);
        ClientTickEvents.END_CLIENT_TICK.register(EquipmentHud::readPet);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            ensureLastLoaded(Minecraft.getInstance());
            // Authoritative: the server has just said what the pet holds, which is the
            // same standing as a full read of the Pets menu and more recent.
            Equip swap = equipped(legacy(message));
            if (swap != null) remember(swap.pet(), swap.item(), true);
            Pet activated = activated(legacy(message));
            if (activated != null) {
                // A switch can happen where neither tab nor the sidebar lists pets.
                // Resolve the newly activated pet, never the preceding pet's item.
                lastPet = activated;
                lastHeldItem = petItems.get(activated);
                activePet = activated;
                saveLast();
            }
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "equipment"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null || mc.options.hideGui) return;
            if (armorEnabled) HudLayout.draw(ARMOR_ID, g, mc.font, false);
            if (petEnabled && activePet != null) HudLayout.draw(PET_ID, g, mc.font, false);
        });
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            // HudLayout supplies bounds only while the armor is actually visible.
            // The placement screen owns samples, whose helmets must not open a real tooltip.
            if (screen instanceof HudPlacementScreen) return;
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> {
                armorTooltip(g, mx, my);
                petItemTooltip(g, mx, my);
            });
        });
    }

    private static void readPet(Minecraft mc) {
        if (!petEnabled || mc.player == null || mc.level == null) {
            activePet = null;
            petWorld = mc.level;
            lastPetRead = 0;
            return;
        }
        if (petWorld != mc.level) {
            activePet = null;
            petWorld = mc.level;
            lastPetRead = 0;
        }
        long now = System.currentTimeMillis();
        if (now - lastPetRead < 250) return;
        lastPetRead = now;
        ensureLastLoaded(mc);
        readMenu(mc);
        String sidebar = Area.sidebarLine(mc, "Pet:");
        // An explicit "Pet: None" is authoritative, even if tab has not updated yet.
        if (sidebar != null) {
            Pet live = petLine(sidebar);
            if (live != null) remember(live, null);
            else if (NO_PET.matcher(Zealots.strip(sidebar).trim()).matches()) forget();
            // A half-updated line ("Pet: Golden Dragon") is neither; keep what was known.
            activePet = live != null ? live : lastPet;
            return;
        }
        List<String> tabLines = tabLines(mc);
        if (tabNoPet(tabLines)) {
            forget();
            activePet = null;
            return;
        }
        Pet tab = tabPet(tabLines);
        if (tab != null) remember(tab, null);
        activePet = tab != null ? tab : lastPet;
    }

    private static void ensureLastLoaded(Minecraft mc) {
        if (lastLoaded || mc.level == null) return;
        // A server activation can arrive before the first readPet tick. Load first
        // so that its save cannot overwrite the remembered items from the last run.
        loadLast(mc);
        lastLoaded = true;
    }

    /**
     * The Pets menu marks the summoned pet "Click to deactivate!". That stack is the
     * pet itself: rendering it showed a dragon head where the equipped pet item belonged.
     * The held item is a separate name in its lore; its icon comes from the item catalogue.
     */
    private static void readMenu(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen) || mc.player == null) {
            scannedPetMenu = null;
            scannedPetStacks = List.of();
            return;
        }
        List<ItemStack> current = new ArrayList<>();
        for (var slot : screen.getMenu().slots)
            if (slot.container != mc.player.getInventory()) current.add(slot.getItem());
        boolean unchanged = screen == scannedPetMenu && current.size() == scannedPetStacks.size();
        for (int i = 0; unchanged && i < current.size(); i++) {
            ItemStack now = current.get(i), before = scannedPetStacks.get(i);
            unchanged = now.getCount() == before.getCount() && ItemStack.isSameItemSameComponents(now, before);
        }
        if (unchanged) return;
        scannedPetMenu = screen;
        scannedPetStacks = current.stream().map(ItemStack::copy).toList();
        boolean petsMenu = Zealots.strip(screen.getTitle().getString()).trim()
                .matches("(?i)^Pets(?: Menu)?(?:\\s*\\(\\d+\\s*/\\d+\\))?$");
        List<MenuPet> entries = new ArrayList<>();
        Pet selected = null;
        MenuPet selectedEntry = null;
        boolean none = false;
        for (var slot : screen.getMenu().slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            List<String> lines = lore.lines().stream().map(EquipmentHud::legacy).toList();
            MenuPet entry = menuPet(legacy(stack.getHoverName()), lines);
            if (entry != null && (petsMenu || entry.selected())) {
                entries.add(entry);
                if (entry.selected()) selectedEntry = entry;
            }
            for (Component line : lore.lines()) {
                String plain = Zealots.strip(line.getString()).trim();
                if (plain.startsWith(SELECTED)) {
                    if (NO_PET.matcher(plain).matches()) none = true;
                    Pet pet = selectedPet(legacy(line));
                    if (pet != null) selected = pet;
                }
            }
        }
        boolean changed = petItems.readMenu(entries);
        // Scan all entries before applying selection: the active pet may be the first
        // slot, but items on the later pets must already be known when we switch.
        if (selectedEntry != null) remember(selectedEntry.pet(), selectedEntry.item(), true);
        else if (none) forget();
        else if (selected != null) remember(selected, null);
        if (changed) saveLast();
    }

    static MenuPet menuPet(String name, List<String> lore) {
        Pet pet = petLine(name);
        if (pet == null) return null;
        boolean selected = lore.stream().map(Zealots::strip).map(String::trim)
                .anyMatch(line -> line.equalsIgnoreCase(DEACTIVATE));
        return new MenuPet(pet, heldItem(lore), selected);
    }

    static Pet activated(String raw) {
        if (raw == null) return null;
        String plain = Zealots.strip(raw).trim();
        Matcher match = ACTIVATED.matcher(plain);
        if (!match.matches()) return null;
        int start = raw.indexOf('['), end = raw.lastIndexOf('!');
        return petLine(raw.substring(start, end < start ? raw.length() : end));
    }

    /**
     * Take the swap announced in chat as the truth, item and pet together.
     *
     * Authoritative, with the same standing as a full read of the Pets menu and newer:
     * the server has just said what the pet is holding. Returns the item so the checks
     * can assert on it; updating the HUD is the side effect.
     */
    /** A swap the server announced: what is now held, and what is holding it. */
    record Equip(Pet pet, HeldItem item) { }

    /**
     * Read "Equipped Combat XP Boost on [Lvl 199] Golden Dragon!".
     *
     * Pure: it parses and returns, and the caller decides what to do with it. The first
     * version updated the HUD from in here, which made it untestable outside a running
     * game - the checks went straight through to saveLast() and died on a null client.
     */
    static Equip equipped(String raw) {
        if (raw == null) return null;
        String plain = Zealots.strip(raw), trimmed = plain.trim();
        Matcher match = EQUIPPED.matcher(trimmed);
        if (!match.matches()) return null;
        String item = match.group(1).trim(), petName = match.group(3).trim();
        if (item.isEmpty() || item.length() > 100 || petName.isEmpty() || petName.length() > 100) return null;
        int level;
        try {
            level = Integer.parseInt(match.group(2));
        } catch (NumberFormatException notANumber) {
            return null;
        }
        if (level <= 0) return null;
        int offset = plain.indexOf(trimmed);
        return new Equip(new Pet(level, petName, colourAt(raw, offset + match.start(3))),
                new HeldItem(item, colourAt(raw, offset + match.start(1))));
    }

    /** "Selected Pet: [Lvl 196] §6Golden Dragon" as a pet; the name keeps its colour. */
    static Pet selectedPet(String raw) {
        int at = raw.indexOf("[Lvl");
        return at < 0 || !Zealots.strip(raw.substring(0, at)).trim().equals(SELECTED) ? null : petLine(raw.substring(at));
    }

    /** Sidebar/tab names do not overwrite the held item learned from the Pets menu. */
    private static void remember(Pet pet, HeldItem item) { remember(pet, item, false); }

    private static void remember(Pet pet, HeldItem item, boolean authoritative) {
        boolean changed = authoritative && petItems.remember(pet, item);
        HeldItem keep = authoritative ? item : petItems.retain(lastPet, lastHeldItem, pet);
        if (pet.equals(lastPet) && Objects.equals(keep, lastHeldItem) && !changed) return;
        lastPet = pet;
        lastHeldItem = keep;
        saveLast();
    }

    static HeldItem retainedHeldItem(Pet previous, HeldItem known, Pet current, HeldItem reported, boolean authoritative) {
        if (authoritative) return reported; // a full menu without a held item also clears an old one
        return previous != null && petKey(previous).equals(petKey(current)) ? known : null;
    }

    static HeldItem heldItem(List<String> lore) {
        for (int i = 0; i < lore.size(); i++) {
            String raw = lore.get(i), plain = Zealots.strip(raw).trim();
            Matcher match = HELD_ITEM.matcher(plain);
            if (!match.matches()) continue;
            String name = match.group(1).trim();
            if (name.isEmpty() && i + 1 < lore.size()) {
                raw = lore.get(++i);
                plain = Zealots.strip(raw).trim();
                name = plain;
            }
            if (name.isBlank() || name.equalsIgnoreCase("None") || name.equalsIgnoreCase("No item")
                    || name.length() > 100 || name.contains("\n")) return null;
            int nameStart = Zealots.strip(raw).lastIndexOf(name);
            return new HeldItem(name, colourAt(raw, Math.max(0, nameStart)));
        }
        return null;
    }

    private static void forget() {
        if (lastPet == null && lastHeldItem == null) return;
        lastPet = null;
        lastHeldItem = null;
        saveLast();
    }

    private static Path petFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("active-pet.nbt");
    }

    private static void loadLast(Minecraft mc) {
        Path path = petFile();
        if (!Files.exists(path)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.create(1L << 20));
            petItems.load(root);
            String name = root.getStringOr("name", "");
            int level = root.getIntOr("level", 0);
            if (name.isEmpty() || level <= 0) return;
            lastPet = new Pet(level, name, root.getIntOr("colour", 0xFFFFFFFF));
            String heldName = root.getStringOr("held_item", "");
            if (!heldName.isBlank() && heldName.length() <= 100)
                lastHeldItem = new HeldItem(heldName, root.getIntOr("held_colour", 0xFFFFFFFF));
            // The former icon was the pet itself. Its lore can recover a held item,
            // but the stack must never be reused as that item's display icon.
            Tag icon = root.get("icon");
            if (lastHeldItem == null && icon != null) ItemStack.OPTIONAL_CODEC
                    .parse(RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess()), icon)
                    .result().filter(stack -> !stack.isEmpty()).ifPresent(stack -> {
                        var lore = stack.get(DataComponents.LORE);
                        if (lore != null) lastHeldItem = heldItem(lore.lines().stream().map(EquipmentHud::legacy).toList());
                    });
            // Keep the original single-pet file readable and migrate its real lore.
            // Recipe catalogue pet stacks can be old, so they are not held-item truth.
            if (!petItems.items.containsKey(petKey(lastPet))) petItems.remember(lastPet, lastHeldItem);
        } catch (IOException | RuntimeException e) {
            // A convenience file: a bad one costs the remembered pet, nothing else.
            System.err.println("[Endsight] could not read the remembered pet: " + e);
        }
    }

    private static void saveLast() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        try {
            CompoundTag root = new CompoundTag();
            if (lastPet != null) {
                root.putString("name", lastPet.name());
                root.putInt("level", lastPet.level());
                root.putInt("colour", lastPet.colour());
            }
            if (lastHeldItem != null) {
                root.putString("held_item", lastHeldItem.name());
                root.putInt("held_colour", lastHeldItem.colour());
            }
            petItems.save(root);
            Path path = petFile();
            Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(root, path);
        } catch (IOException | RuntimeException e) {
            System.err.println("[Endsight] could not save the remembered pet: " + e);
        }
    }

    private static List<String> tabLines(Minecraft mc) {
        if (mc.getConnection() == null) return List.of();
        List<PlayerInfo> infos = new ArrayList<>(mc.getConnection().getListedOnlinePlayers());
        infos.sort(Comparator.comparingInt(PlayerInfo::getTabListOrder).reversed()
                .thenComparing(info -> info.getProfile().name(), String.CASE_INSENSITIVE_ORDER));
        return infos.stream().map(PlayerInfo::getTabListDisplayName)
                .filter(c -> c != null).map(EquipmentHud::legacy).toList();
    }

    /** Missing tab information preserves memory; an explicit unequip must clear it. */
    static boolean tabNoPet(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String plain = Zealots.strip(lines.get(i)).trim();
            if (plain.matches("(?i)^(?:Pet\\s*:|Active Pet\\s*:?)\\s*None$")) return true;
            if (plain.matches("(?i)^Active Pet\\s*:?$") && i + 1 < lines.size()
                    && Zealots.strip(lines.get(i + 1)).trim().equalsIgnoreCase("None")) return true;
        }
        return false;
    }

    static Pet tabPet(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String plain = Zealots.strip(lines.get(i)).trim();
            if (plain.matches("(?i)^Active Pet\\s*:?$"))
                return i + 1 < lines.size() ? petLine(lines.get(i + 1)) : null;
            if (plain.matches("(?i)^(?:Pet\\s*:|Active Pet(?:\\s*:)?).+")) {
                return petLine(lines.get(i));
            }
        }
        return null;
    }

    static Pet petLine(String raw) {
        if (raw == null) return null;
        String plain = Zealots.strip(raw), trimmed = plain.trim();
        Matcher match = PET_LINE.matcher(trimmed);
        if (!match.matches()) return null;
        String name = match.group(2).trim();
        if (name.isEmpty() || name.length() > 100) return null;
        int level = Integer.parseInt(match.group(1));
        if (level <= 0) return null;
        int nameStart = plain.indexOf(trimmed) + match.start(2);
        return new Pet(level, name, colourAt(raw, nameStart));
    }

    private static int colourAt(String raw, int plainIndex) {
        int seen = 0, colour = 0xFFFFFFFF;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '§' && i + 1 < raw.length()) {
                ChatFormatting format = ChatFormatting.getByCode(raw.charAt(++i));
                if (format == ChatFormatting.RESET) colour = 0xFFFFFFFF;
                else if (format != null && format.getColor() != null) colour = 0xFF000000 | format.getColor();
            } else if (seen++ >= plainIndex) break;
        }
        return colour;
    }

    private static String legacy(Component component) {
        StringBuilder out = new StringBuilder();
        component.visit((style, text) -> {
            if (style.getColor() != null && !text.contains("§")) {
                for (ChatFormatting format : ChatFormatting.values())
                    if (format.isColor() && format.getColor() != null
                            && format.getColor() == style.getColor().getValue()) {
                        out.append('§').append(format.getChar());
                        break;
                    }
            }
            out.append(text);
            return Optional.empty();
        }, Style.EMPTY);
        return out.toString();
    }

    private static ItemStack armorStack(Minecraft mc, int index, boolean sample) {
        ItemStack worn = mc.player == null ? ItemStack.EMPTY : mc.player.getItemBySlot(ARMOR[index]);
        return sample && worn.isEmpty() ? Samples.ARMOR[index] : worn;
    }

    private static int[] drawArmor(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        int w = PAD * 2 + CELL * ARMOR.length, h = PAD * 2 + 16;
        if (g == null) return new int[]{w, h};
        backdrop(g, x, y, w, h, armorOpacity);
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < ARMOR.length; i++) {
            ItemStack stack = armorStack(mc, i, sample);
            if (stack.isEmpty()) continue;
            int sx = x + PAD + i * CELL, sy = y + PAD;
            g.item(stack, sx, sy);
            g.itemDecorations(font, stack, sx, sy);
        }
        return new int[]{w, h};
    }

    private static int[] drawPet(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        Pet pet = activePet == null && sample ? Samples.PET : activePet;
        if (pet == null) return new int[]{80, 25};
        HeldItem held = activePet == null ? Samples.HELD_ITEM : heldFor(pet);
        ItemStack icon = activePet == null ? Samples.HELD_ICON : heldIcon(held);
        int tx = x + PAD + (icon == null ? 0 : CELL);
        String level = "[Lvl " + pet.level() + "] ";
        int w = tx - x + PAD + Math.max(font.width("Active Pet"), font.width(level) + font.width(pet.name()));
        int h = 25;
        // A newly introduced pet item can lack a cached icon. Its real name is still
        // useful; borrowing the pet's head or guessing a material would be misleading.
        if (held != null && icon == null) { w = Math.max(w, PAD * 2 + font.width("Held: " + held.name())); h += 12; }
        if (g == null) return new int[]{w, h};
        backdrop(g, x, y, w, h, petOpacity);
        if (icon != null) g.item(icon, x + PAD, y + (h - 16) / 2);
        Draw.text(g, font, "Active Pet", tx, y + PAD, 0xFFFFAA00);
        Draw.text(g, font, level, tx, y + PAD + 12, Draw.lerp(Theme.muted(), Theme.text(), .55f));
        Draw.text(g, font, pet.name(), tx + font.width(level), y + PAD + 12, pet.colour());
        if (held != null && icon == null) {
            Draw.text(g, font, "Held: ", x + PAD, y + PAD + 24, Theme.muted());
            Draw.text(g, font, held.name(), x + PAD + font.width("Held: "), y + PAD + 24, held.colour());
        }
        return new int[]{w, h};
    }

    private static ItemStack heldIcon(HeldItem held) {
        if (held == null) return null;
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        // A missing catalogue icon otherwise scans every stored item twice per frame
        // (measurement and drawing). Retry at the pet read cadence so newly seen
        // icons appear promptly without making unknown pet items expensive.
        if (held.equals(iconItem) && iconWorld == mc.level && now >= iconReadAt && now - iconReadAt < 250)
            return cachedHeldIcon;
        ItemStack stack = Recipes.icon(held.name());
        if (stack == null) stack = Recipes.held(held.name());
        iconItem = held;
        iconWorld = mc.level;
        iconReadAt = now;
        cachedHeldIcon = stack == null || stack.isEmpty() ? null : stack;
        return cachedHeldIcon;
    }

    private static void backdrop(GuiGraphicsExtractor g, int x, int y, int w, int h, double opacity) {
        if (opacity > 0) Draw.roundedRect(g, x, y, w, h, 2,
                Draw.alpha(Theme.bg(), (float) (opacity / 100)));
    }

    static int armorIndex(double localX, double localY) {
        if (localY < PAD || localY >= PAD + 16 || localX < PAD) return -1;
        int index = (int) ((localX - PAD) / CELL);
        if (index >= ARMOR.length || localX >= PAD + index * CELL + 16) return -1;
        return index;
    }

    private static void armorTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (!armorEnabled || mc.player == null || mc.level == null || mc.options.hideGui) return;
        int[] bounds = HudLayout.bounds(ARMOR_ID);
        if (bounds == null) return;
        float scale = HudLayout.scale(ARMOR_ID);
        int index = armorIndex((mouseX - bounds[0]) / scale, (mouseY - bounds[1]) / scale);
        if (index < 0) return;
        ItemStack stack = armorStack(mc, index, false);
        if (!stack.isEmpty()) itemTooltip(g, stack, mouseX, mouseY);
    }

    private static void petItemTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (!petEnabled || activePet == null || mc.player == null || mc.level == null || mc.options.hideGui) return;
        int[] bounds = HudLayout.bounds(PET_ID);
        if (bounds == null) return;
        ItemStack icon = heldIcon(heldFor(activePet));
        if (icon == null) return;
        float scale = HudLayout.scale(PET_ID);
        double lx = (mouseX - bounds[0]) / scale, ly = (mouseY - bounds[1]) / scale;
        int iy = (25 - 16) / 2;
        if (lx >= PAD && lx < PAD + 16 && ly >= iy && ly < iy + 16)
            itemTooltip(g, icon, mouseX, mouseY);
    }

    private static HeldItem heldFor(Pet pet) {
        return lastPet != null && petKey(lastPet).equals(petKey(pet)) ? lastHeldItem : petItems.get(pet);
    }

    private static void itemTooltip(GuiGraphicsExtractor g, ItemStack stack, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        TooltipFlag flag = mc.options.advancedItemTooltips ? TooltipFlag.ADVANCED : TooltipFlag.NORMAL;
        List<ClientTooltipComponent> lines = new ArrayList<>(stack
                .getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, flag).stream()
                .map(line -> ClientTooltipComponent.create(line.getVisualOrderText())).toList());
        stack.getTooltipImage().ifPresent(image -> lines.add(Math.min(1, lines.size()), ClientTooltipComponent.create(image)));
        // AFTER_EXTRACT runs after vanilla flushes deferred tooltips. Extract now so
        // both inventory and chat can show the actual item's name and full lore.
        g.nextStratum();
        g.tooltip(mc.font, lines, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE,
                stack.get(DataComponents.TOOLTIP_STYLE));
    }
}
