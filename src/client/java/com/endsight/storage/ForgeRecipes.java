package com.endsight.storage;

import com.endsight.hud.HudLayout;
import com.endsight.hud.Readout;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Forge is not a crafting grid: it is a server menu whose result items carry
 * their ingredients and duration in lore. Capture those facts while the player opens
 * Forge recipe pages, but treat duration as observed data only. The server has
 * already been moving those numbers around; costs are the part worth trusting first.
 */
public final class ForgeRecipes {

    private ForgeRecipes() {
    }

    private static final Pattern COUNT = Pattern.compile("^(.+?) x([\\d,]+)$");
    private static final Pattern DURATION = Pattern.compile("^Duration: (.+)$");
    private static final Pattern TIME_LEFT = Pattern.compile("^Time Remaining: (.+)$");
    private static final Pattern FINISH_NOW = Pattern.compile("^Finish now: (.+)$");
    private static final Pattern TIME_PART = Pattern.compile("(\\d+)\\s*([dhms])");
    private static final String FORGE = "The Forge";
    private static final String TIMER_ID = "storage.forge.timer";
    private static final int RECENT_MAX = 10;
    private static final int RECENT_W = 126;
    private static final int RECENT_ROW = 19;

    record Ingredient(String name, int count) {
    }

    record ForgeRecipe(String process, String name, String duration, List<Ingredient> needs, String requirement) {
    }

    record Active(int slot, String name, long until, long seenAt, String finishNow, boolean claimable) {
    }

    record Row(int slot, String name, String value, boolean hot) {
    }

    record Recent(String process, String name) {
    }

    public record Info(String category, String duration, String requirement) {
    }

    private static boolean enabled = true;
    private static boolean timer = true;
    private static boolean readyOutline = true;
    private static boolean recentPanel = true;
    private static final Map<String, ForgeRecipe> RECIPES = new LinkedHashMap<>();
    private static final Map<Integer, Active> ACTIVE = new LinkedHashMap<>();
    private static final Set<Integer> SLOTS = new HashSet<>();
    private static final List<Recent> RECENT = new ArrayList<>();
    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static Recent pendingRecent;
    private static long pendingRecentAt;

    public static Module module() {
        return new Module("storage.forge", "Forge Utils",
                "Forge timers, ready slot outlines and recent Forge crafts.", "Mining",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Toggle("Forge timer",
                                "Show active Forge slots and ready crafts on the HUD.",
                                () -> timer, v -> timer = v),
                        new Setting.Toggle("Ready outline",
                                "Outline completed Forge slots while the Forge menu is open.",
                                () -> readyOutline, v -> readyOutline = v),
                        new Setting.Toggle("Recent crafts",
                                "Show your latest Forge crafts beside Forge menus.",
                                () -> recentPanel, v -> recentPanel = v),
                        new Setting.Note("Known", () -> RECIPES.size()
                                + " forge crafts, " + ACTIVE.size() + " active slots")));
    }

    public static boolean timerEnabled() {
        return timer;
    }

    public static void timerEnabled(boolean v) {
        timer = v;
    }

    public static int recipeCount() {
        return RECIPES.size();
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    public static boolean isForge(String item) {
        return info(item) != null;
    }

    public static Info info(String item) {
        for (ForgeRecipe r : RECIPES.values()) {
            if (r.name().equals(item)) return new Info(r.process(), r.duration(), r.requirement());
        }
        return null;
    }

    public static void init() {
        load();
        loadActive();
        loadRecent();
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenEvents.afterTick(screen).register(s -> tick(container));
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> {
                drawRecent(container, g, mx, my);
            });
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> !click(container, click.x(), click.y(), click.button()));
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "forge_timer"), (g, delta) -> draw(g));
        HudLayout.register(TIMER_ID, "Forge Timer", 0.74f, 0.19f,
                (g, font, x, y, sample) -> drawTimer(g, font, x, y, sample));
    }

    private static void tick(AbstractContainerScreen<?> screen) {
        if (!enabled) return;
        String title = Zealots.strip(screen.getTitle().getString()).trim();
        List<Slot> slots = containerSlots(screen);
        followRecentShortcut(screen, title, slots);
        boolean changed = false;
        boolean activeChanged = false;
        for (Slot slot : slots) {
            ItemStack s = slot.getItem();
            ForgeRecipe r = read(title, s);
            if (r == null) continue;
            rememberIcon(r.name(), s);
            String key = r.process() + "\t" + r.name();
            ForgeRecipe old = RECIPES.put(key, r);
            if (!same(old, r)) changed = true;
        }
        if (FORGE.equals(title)) {
            for (Slot slot : slots) {
                if (!forgeSlot(slot.index)) continue;
                Active a = readActive(slot);
                if (a != null) {
                    SLOTS.add(slot.index);
                    Active old = ACTIVE.put(a.slot(), a);
                    if (!same(old, a)) activeChanged = true;
                    if (old == null || !old.name().equals(a.name())) touchRecent(a.name());
                } else if (emptySlot(slot)) {
                    if (SLOTS.add(slot.index)) activeChanged = true;
                    if (ACTIVE.remove(slot.index) != null) activeChanged = true;
                } else {
                    if (SLOTS.remove(slot.index)) activeChanged = true;
                    if (ACTIVE.remove(slot.index) != null) activeChanged = true;
                }
            }
        }
        if (changed) save();
        if (activeChanged) saveActive();
    }

    private static ForgeRecipe read(String process, ItemStack s) {
        if (s.isEmpty()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        String name = name(s);
        String duration = "";
        String requirement = "";
        List<Ingredient> needs = new ArrayList<>();
        boolean readingNeeds = false;

        for (Component c : s.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL)) {
            String line = Zealots.strip(c.getString()).trim();
            if (line.isBlank()) continue;
            if (line.equals("Items Required")) {
                readingNeeds = true;
                continue;
            }
            Matcher d = DURATION.matcher(line);
            if (d.matches()) {
                duration = d.group(1);
                readingNeeds = false;
                continue;
            }
            if (line.startsWith("Requires ")) {
                requirement = line;
                readingNeeds = false;
                continue;
            }
            if (line.equals("Click to view!") || line.endsWith("to view recipes!")) {
                readingNeeds = false;
                continue;
            }
            if (!readingNeeds) continue;
            Matcher n = COUNT.matcher(line);
            if (n.matches()) {
                needs.add(new Ingredient(n.group(1), Integer.parseInt(n.group(2).replace(",", ""))));
            } else {
                needs.add(new Ingredient(line, 1));
            }
        }
        return needs.isEmpty() ? null : new ForgeRecipe(process, name, duration, List.copyOf(needs), requirement);
    }

    private static Active readActive(Slot slot) {
        ItemStack s = slot.getItem();
        if (s.isEmpty()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        String left = "";
        String finish = "";
        boolean claim = false;
        for (Component c : s.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL)) {
            String line = Zealots.strip(c.getString()).trim();
            Matcher t = TIME_LEFT.matcher(line);
            if (t.matches()) left = t.group(1);
            Matcher f = FINISH_NOW.matcher(line);
            if (f.matches()) finish = f.group(1);
            String lower = line.toLowerCase();
            if (lower.contains("click to claim") || lower.contains("claim your") || lower.contains("finished forging")) claim = true;
        }
        long now = System.currentTimeMillis();
        long ms = millis(left);
        if (ms <= 0 && !claim) return null;
        return new Active(slot.index, name(s), now + Math.max(0, ms), now, finish, claim);
    }

    private static boolean emptySlot(Slot slot) {
        ItemStack s = slot.getItem();
        if (s.isEmpty()) return false;
        String n = name(s).toLowerCase();
        return n.matches("slot #\\d+");
    }

    private static boolean same(ForgeRecipe a, ForgeRecipe b) {
        return a != null
                && a.process().equals(b.process())
                && a.name().equals(b.name())
                && a.duration().equals(b.duration())
                && a.requirement().equals(b.requirement())
                && a.needs().equals(b.needs());
    }

    private static boolean same(Active a, Active b) {
        return a != null
                && a.slot() == b.slot()
                && a.name().equals(b.name())
                && Math.abs(a.until() - b.until()) < 1_500
                && a.finishNow().equals(b.finishNow())
                && a.claimable() == b.claimable();
    }

    private static List<Slot> containerSlots(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        Container playerInv = mc.player == null ? null : mc.player.getInventory();
        List<Slot> out = new ArrayList<>();
        try {
            for (Slot slot : screen.getMenu().slots) {
                if (slot.container != playerInv) out.add(slot);
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return out;
    }

    private static String name(ItemStack s) {
        return Zealots.strip(s.getHoverName().getString()).trim();
    }

    private static void rememberIcon(String name, ItemStack s) {
        if (!s.isEmpty()) ICONS.putIfAbsent(name, s.copyWithCount(1));
    }

    private static boolean click(AbstractContainerScreen<?> screen, double mx, double my, int button) {
        if (!enabled || button != 0 || !forgeScreen(screen)) return false;
        RecentBox box = recentBox(screen);
        if (box != null && mx >= box.x() && mx < box.x() + box.w()
                && my >= box.y() && my < box.y() + box.h()) {
            int row = (int) ((my - box.y() - 16) / RECENT_ROW);
            List<Recent> list = recentRows();
            if (row >= 0 && row < list.size()) {
                Recent recent = list.get(row);
                pendingRecent = recent;
                pendingRecentAt = System.currentTimeMillis();
                String title = Zealots.strip(screen.getTitle().getString()).trim();
                if (recent.process().equals(title)) openRecentItem(screen, containerSlots(screen));
                else if (!openProcess(screen, recent.process())) pendingRecent = null;
            }
            return true;
        }
        Slot slot = slotAt(screen, mx, my);
        if (slot != null) {
            ForgeRecipe r = read(Zealots.strip(screen.getTitle().getString()).trim(), slot.getItem());
            if (r != null) {
                rememberIcon(r.name(), slot.getItem());
                touchRecent(r);
            }
        }
        return false;
    }

    private static Slot slotAt(AbstractContainerScreen<?> screen, double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        Container playerInv = mc.player == null ? null : mc.player.getInventory();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == playerInv) continue;
            int x = screen.leftPos + slot.x;
            int y = screen.topPos + slot.y;
            if (mx >= x && mx < x + 16 && my >= y && my < y + 16) return slot;
        }
        return null;
    }

    private static boolean openProcess(AbstractContainerScreen<?> screen, String process) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        Container playerInv = mc.player.getInventory();
        List<Slot> slots = screen.getMenu().slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.container == playerInv || slot.getItem().isEmpty()) continue;
            if (!name(slot.getItem()).equals(process)) continue;
            mc.gameMode.handleContainerInput(screen.getMenu().containerId, i, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
            return true;
        }
        return false;
    }

    private static void followRecentShortcut(AbstractContainerScreen<?> screen, String title, List<Slot> slots) {
        if (pendingRecent == null) return;
        long now = System.currentTimeMillis();
        if (now - pendingRecentAt > 3_000 || title.equals("Confirm Process")) {
            pendingRecent = null;
            return;
        }
        if (pendingRecent.process().equals(title)) openRecentItem(screen, slots);
    }

    private static void openRecentItem(AbstractContainerScreen<?> screen, List<Slot> slots) {
        if (pendingRecent == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;
        Container playerInv = mc.player.getInventory();
        List<Slot> all = screen.getMenu().slots;
        for (Slot slot : slots) {
            if (slot.container == playerInv || slot.getItem().isEmpty()) continue;
            if (!name(slot.getItem()).equals(pendingRecent.name())) continue;
            int menuIndex = all.indexOf(slot);
            if (menuIndex < 0) continue;
            mc.gameMode.handleContainerInput(screen.getMenu().containerId, menuIndex, 0,
                    net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
            pendingRecent = null;
            return;
        }
    }

    private static void touchRecent(String item) {
        ForgeRecipe r = recipe(item);
        if (r != null) touchRecent(r);
    }

    private static void touchRecent(ForgeRecipe r) {
        String key = r.process() + "\t" + r.name();
        RECENT.removeIf(e -> (e.process() + "\t" + e.name()).equals(key));
        RECENT.add(0, new Recent(r.process(), r.name()));
        while (RECENT.size() > RECENT_MAX) RECENT.remove(RECENT.size() - 1);
        saveRecent();
    }

    private static ForgeRecipe recipe(String item) {
        for (ForgeRecipe r : RECIPES.values()) if (r.name().equals(item)) return r;
        return null;
    }

    private static List<Recent> recentRows() {
        return RECENT.stream()
                .filter(r -> RECIPES.containsKey(r.process() + "\t" + r.name()))
                .limit(RECENT_MAX)
                .toList();
    }

    private static boolean forgeScreen(AbstractContainerScreen<?> screen) {
        String title = Zealots.strip(screen.getTitle().getString()).trim();
        if (title.equals(FORGE) || title.equals("Select Process")) return true;
        for (ForgeRecipe r : RECIPES.values()) if (r.process().equals(title)) return true;
        return false;
    }

    private record RecentBox(int x, int y, int w, int h) {
    }

    private static RecentBox recentBox(AbstractContainerScreen<?> screen) {
        if (!enabled || !recentPanel || !forgeScreen(screen)) return null;
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int h = 18 + Math.max(1, recentRows().size()) * RECENT_ROW;
        int x = screen.leftPos - RECENT_W - 6;
        if (x < 4) x = screen.leftPos + screen.imageWidth + 6;
        if (x + RECENT_W > sw - 4) return null;
        if (x < 4) return null;
        return new RecentBox(x, screen.topPos, RECENT_W, h);
    }

    private static void drawRecent(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mx, int my) {
        if (!enabled) return;
        RecentBox box = recentBox(screen);
        if (box == null) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        List<Recent> list = recentRows();
        Draw.roundedRect(g, box.x(), box.y(), box.w(), box.h(), 5, Draw.alpha(Theme.surface(), 0.82f));
        Draw.text(g, font, "Forge Recent", box.x() + 6, box.y() + 5, Theme.muted());
        if (list.isEmpty()) {
            Draw.text(g, font, "none yet", box.x() + 6, box.y() + 22, Theme.dim());
            return;
        }
        int y = box.y() + 17;
        for (Recent r : list) {
            boolean hover = mx >= box.x() && mx < box.x() + box.w() && my >= y && my < y + RECENT_ROW;
            Draw.roundedRect(g, box.x() + 3, y, box.w() - 6, RECENT_ROW - 2, 3, hover ? Theme.hover() : Theme.raised());
            ItemStack icon = ICONS.get(r.name());
            if (icon != null) g.fakeItem(icon, box.x() + 5, y);
            Draw.text(g, font, Draw.fit(font, shortName(r.name()), box.w() - 29), box.x() + 24, y + 5,
                    hover ? Theme.text() : Theme.muted());
            y += RECENT_ROW;
        }
    }

    public static void overSlot(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, Slot slot) {
        if (!enabled || !readyOutline) return;
        String title = Zealots.strip(screen.getTitle().getString()).trim();
        if (!FORGE.equals(title)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Container playerInv = mc.player.getInventory();
        long now = System.currentTimeMillis();
        if (slot.container == playerInv || !forgeSlot(slot.index)) return;
        Active live = readActive(slot);
        Active a = live != null ? live : ACTIVE.get(slot.index);
        if (a == null || (!a.claimable() && a.until() > now)) return;
        int x = slot.x;
        int y = slot.y;
        Draw.rect(g, x, y, 16, 16, Draw.alpha(Theme.pos(), 0.24f));
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("forge.txt");
    }

    private static Path activeFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("forge-active.txt");
    }

    private static Path recentFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("forge-recent.txt");
    }

    private static void save() {
        List<String> lines = new ArrayList<>();
        lines.add("# process\tresult\tduration observed\tcount*ingredient...\trequirement");
        for (ForgeRecipe r : RECIPES.values()) {
            StringBuilder sb = new StringBuilder(r.process()).append('\t')
                    .append(r.name()).append('\t')
                    .append(r.duration());
            for (Ingredient i : r.needs()) sb.append('\t').append(i.count()).append('*').append(i.name());
            sb.append('\t').append(r.requirement());
            lines.add(sb.toString());
        }
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Endsight] could not write forge recipes: " + e);
        }
    }

    private static void load() {
        Path f = file();
        boolean hadFile = Files.exists(f);
        try {
            if (hadFile) readRecipes(Files.readAllLines(f, StandardCharsets.UTF_8), false);
            boolean merged = mergeBundledRecipes();
            if (!hadFile || merged) save();
        } catch (IOException | NumberFormatException e) {
            System.err.println("[Endsight] could not read forge recipes: " + e);
        }
    }

    private static boolean mergeBundledRecipes() throws IOException {
        try (InputStream in = ForgeRecipes.class.getResourceAsStream("/endsight-forge.txt")) {
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
            if (p.length < 4) continue;
            List<Ingredient> needs = new ArrayList<>();
            for (int i = 3; i < p.length - 1; i++) {
                int star = p[i].indexOf('*');
                if (star > 0) needs.add(new Ingredient(p[i].substring(star + 1), Integer.parseInt(p[i].substring(0, star))));
            }
            ForgeRecipe r = new ForgeRecipe(p[0], p[1], p[2], List.copyOf(needs), p[p.length - 1]);
            String key = r.process() + "\t" + r.name();
            if (missingOnly && RECIPES.containsKey(key)) continue;
            ForgeRecipe old = RECIPES.put(key, r);
            if (!same(old, r)) changed = true;
        }
        return changed;
    }

    private static void saveActive() {
        List<String> lines = new ArrayList<>();
        lines.add("# slot\titem\tfinish epoch ms\tlast seen epoch ms\tfinish now\tclaimable");
        if (!SLOTS.isEmpty()) {
            StringBuilder sb = new StringBuilder("# unlocked");
            SLOTS.stream().sorted().forEach(i -> sb.append('\t').append(i));
            lines.add(sb.toString());
        }
        for (Active a : ACTIVE.values()) {
            lines.add(a.slot() + "\t" + a.name() + "\t" + a.until() + "\t" + a.seenAt()
                    + "\t" + a.finishNow() + "\t" + a.claimable());
        }
        try {
            Files.createDirectories(activeFile().getParent());
            Files.write(activeFile(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Endsight] could not write active forge slots: " + e);
        }
    }

    private static void loadActive() {
        Path f = activeFile();
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.startsWith("# unlocked")) {
                    String[] p = line.split("\t", -1);
                    for (int i = 1; i < p.length; i++) {
                        int slot = Integer.parseInt(p[i]);
                        if (forgeSlot(slot)) SLOTS.add(slot);
                    }
                    continue;
                }
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t", -1);
                if (p.length < 6) continue;
                Active a = new Active(Integer.parseInt(p[0]), p[1], Long.parseLong(p[2]),
                        Long.parseLong(p[3]), p[4], Boolean.parseBoolean(p[5]));
                if (forgeSlot(a.slot())) {
                    SLOTS.add(a.slot());
                    ACTIVE.put(a.slot(), a);
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("[Endsight] could not read active forge slots: " + e);
        }
    }

    private static void saveRecent() {
        List<String> lines = new ArrayList<>();
        lines.add("# process\tresult");
        for (Recent r : RECENT) lines.add(r.process() + "\t" + r.name());
        try {
            Files.createDirectories(recentFile().getParent());
            Files.write(recentFile(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Endsight] could not write recent forge crafts: " + e);
        }
    }

    private static void loadRecent() {
        Path f = recentFile();
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t", -1);
                if (p.length >= 2) RECENT.add(new Recent(p[0], p[1]));
            }
            while (RECENT.size() > RECENT_MAX) RECENT.remove(RECENT.size() - 1);
        } catch (IOException e) {
            System.err.println("[Endsight] could not read recent forge crafts: " + e);
        }
    }

    private static boolean forgeSlot(int i) {
        return i >= 10 && i <= 16;
    }

    private static long millis(String time) {
        if (time == null || time.isBlank()) return 0;
        long ms = 0;
        Matcher m = TIME_PART.matcher(time.toLowerCase());
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            ms += switch (m.group(2)) {
                case "d" -> n * 86_400_000L;
                case "h" -> n * 3_600_000L;
                case "m" -> n * 60_000L;
                default -> n * 1_000L;
            };
        }
        return ms;
    }

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || !timer || mc.player == null || mc.options.hideGui || rows(false).isEmpty()) return;
        HudLayout.draw(TIMER_ID, g, mc.font, false);
    }

    private static int[] drawTimer(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        List<Row> rows = sample ? sampleRows() : rows(true);
        List<Row> status = sample ? List.of(new Row(-1, "Active", "2/3", false)) : statusRows();
        String count = slotCount(sample, rows);
        int w = Readout.width(font, "FORGE", count);
        for (Row row : status) w = Math.max(w, Readout.width(font, row.name(), row.value()));
        for (Row row : rows) w = Math.max(w, Readout.width(font, shortName(row.name()), row.value()));
        int h = Readout.ROW_H + 3 + Math.max(1, status.size() + rows.size()) * (Readout.ROW_H + 2);
        if (g == null) return new int[]{w, h};

        Readout.tick(g, x, y, w, Theme.accent());
        Draw.text(g, font, "FORGE", Readout.left(x), y, Theme.muted());
        Draw.textRight(g, font, count, Readout.right(x, w), y, Theme.text());
        int ry = y + Readout.ROW_H + 3;
        for (Row row : status) {
            Draw.text(g, font, row.name(), Readout.left(x), ry, row.hot() ? Theme.accent() : Theme.dim());
            Draw.textRight(g, font, row.value(), Readout.right(x, w), ry,
                    row.hot() ? Theme.accent() : Theme.text());
            ry += Readout.ROW_H + 2;
        }
        if (rows.isEmpty() && status.isEmpty()) {
            Draw.text(g, font, "No active slots", Readout.left(x), ry, Theme.dim());
        } else {
            for (Row row : rows) {
                Draw.text(g, font, Draw.fit(font, shortName(row.name()), w - 52), Readout.left(x), ry,
                        row.hot() ? Theme.accent() : Theme.dim());
                Draw.textRight(g, font, row.value(), Readout.right(x, w), ry,
                        row.hot() ? Theme.accent() : Theme.text());
                ry += Readout.ROW_H + 2;
            }
        }
        return new int[]{w, h};
    }

    private static String slotCount(boolean sample, List<Row> rows) {
        int n;
        if (sample) {
            n = rows.size();
        } else {
            Set<Integer> slots = new HashSet<>(SLOTS);
            slots.addAll(ACTIVE.keySet());
            n = slots.isEmpty() ? rows.size() : slots.size();
        }
        return n == 1 ? "1 slot" : n + " slots";
    }

    private static List<Row> statusRows() {
        Set<Integer> slots = knownSlots();
        if (slots.isEmpty()) return List.of();
        int active = 0;
        for (int slot : slots) {
            Active a = ACTIVE.get(slot);
            if (a == null) continue;
            active++;
        }
        return List.of(new Row(-1, "Active", active + "/" + slots.size(), false));
    }

    private static List<Row> rows(boolean trim) {
        long now = System.currentTimeMillis();
        Set<Integer> slots = knownSlots();
        if (slots.isEmpty()) return List.of();
        List<Row> out = slots.stream()
                .sorted(Comparator.comparingLong(i -> ACTIVE.containsKey(i) ? ACTIVE.get(i).until() : Long.MAX_VALUE))
                .map(i -> {
                    Active a = ACTIVE.get(i);
                    if (a == null) return new Row(i, "Empty", "", false);
                    boolean hot = a.claimable() || a.until() <= now;
                    return new Row(i, a.name(), value(a, now), hot);
                })
                .filter(r -> !trim || !r.name().equals("Empty"))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return out;
    }

    private static Set<Integer> knownSlots() {
        Set<Integer> slots = new HashSet<>(SLOTS);
        slots.addAll(ACTIVE.keySet());
        return slots;
    }

    private static List<Row> sampleRows() {
        long now = System.currentTimeMillis();
        return List.of(
                new Row(10, "Refined End Stone", time(now + 626_000L - now), false),
                new Row(11, "Empty", "", false),
                new Row(12, "Void Alloy", "Ready", true));
    }

    private static String shortName(String name) {
        return name.replace("Refined ", "Ref. ").replace("Enchanted ", "Ench. ");
    }

    private static String value(Active a, long now) {
        if (a.claimable() || a.until() <= now) return "Ready";
        return time(a.until() - now);
    }

    private static String time(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        long d = s / 86_400; s %= 86_400;
        long h = s / 3_600; s %= 3_600;
        long m = s / 60; s %= 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
}
