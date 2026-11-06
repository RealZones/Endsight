package com.endsight.dragons;

import com.endsight.goop.GoopTracker;
import com.endsight.qol.NpcPrices;
import com.endsight.qol.Rarity;
import com.endsight.storage.CacheTracker;
import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What each drop is worth, for every profit readout, one list per tracker.
 *
 * It used to list every drop the mod knew of, with a detail pane and a Save button for one
 * item at a time. Fear's verdict was that it sucked, and it did: rows for things he had never
 * had, including pets that could not be saved at all. Now a list holds what that tracker has
 * actually paid out, the ones with no price come first, and a price is edited where it stands.
 */
public final class DropPricesScreen extends Screen {
    private static final int PANEL_W = 470, PANEL_H = 400, PAD = 14, ROW = 22, TAB_W = 54, TAB_H = 18, GAP = 4;
    private static final int LIST_TOP = 74, LIST_FOOT = 30, CHIP_W = 74, CHIP_H = 16, SEARCH_W = 130;

    /** @param costs what the tracker spends rather than earns, labelled so a price there reads the right way round */
    private record Tab(String name, Supplier<Map<String, Integer>> drops, Set<String> costs) { }
    private record Row(String name, int count, NpcPrices.Price price, boolean cost) { }

    private static final List<Tab> TABS = List.of(
            new Tab("Slayer", BossDrops::slayerDropCounts, Set.of()),
            new Tab("Zealot", DropPricesScreen::zealot, Set.of()),
            new Tab("Goop", GoopTracker::dropCounts, Set.of(GoopTracker.COST_ITEM)),
            new Tab("Cache", CacheTracker::dropCounts, Set.of(CacheTracker.COST_ITEM)));

    private final Screen parent;
    private int tab;
    private EditBox search, amount;
    private String editing, status = "";
    private boolean statusBad;
    private int scroll;
    // Rebuilt on a change, not per frame: every price is a lookup and a slayer history runs long.
    private List<Row> rows = List.of();
    private long builtRevision = -1;
    private int builtTab = -1;
    private String builtQuery;

    private DropPricesScreen(Screen parent, int tab) {
        super(Component.literal("Drop prices"));
        this.parent = parent;
        this.tab = tab;
    }

    private static void open(int tab) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new DropPricesScreen(mc.screen, tab));
    }

    public static void open() { open(0); }
    public static void openZealot() { open(1); }
    public static void openGoop() { open(2); }
    public static void openCache() { open(3); }

    private static Map<String, Integer> zealot() {
        Map<String, Integer> drops = new LinkedHashMap<>();
        for (String item : NpcPrices.ZEALOT_PROFIT_ITEMS) drops.put(item, 0);
        return drops;
    }

    private int px() { return Math.max(8, (width - pw()) / 2); }
    private int py() { return Math.max(8, (height - ph()) / 2); }
    private int pw() { return Math.min(PANEL_W, width - 16); }
    private int ph() { return Math.min(PANEL_H, height - 16); }
    private int listX() { return px() + PAD; }
    private int listW() { return pw() - PAD * 2; }
    private int listTop() { return py() + LIST_TOP; }
    private int visible() { return Math.max(1, (ph() - LIST_TOP - LIST_FOOT) / ROW); }
    private int chipX() { return listX() + listW() - CHIP_W - 4; }
    private int tabX(int index) { return listX() + index * (TAB_W + GAP); }
    private int searchX() { return px() + pw() - PAD - SEARCH_W; }

    @Override
    protected void init() {
        // Reading it again is what the old Reload button did, without a button for it.
        NpcPrices.reloadCustom();
        search = new EditBox(font, searchX() + 6, py() + 37, SEARCH_W - 12, 12, Component.literal("Search drops"));
        search.setBordered(false);
        search.setMaxLength(60);
        search.setHint(Component.literal("Search..."));
        search.setResponder(value -> scroll = 0);
        addRenderableWidget(search);

        amount = new EditBox(font, 0, 0, CHIP_W - 12, 12, Component.literal("Price per item"));
        amount.setBordered(false);
        amount.setMaxLength(24);
        amount.visible = false;
        addRenderableWidget(amount);
        builtTab = -1;
    }

    private void build() {
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (builtTab == tab && builtRevision == NpcPrices.revision() && query.equals(builtQuery)) return;
        builtTab = tab; builtRevision = NpcPrices.revision(); builtQuery = query;
        Tab current = TABS.get(tab);
        List<Row> next = new ArrayList<>();
        current.drops().get().forEach((name, count) -> {
            if (!name.toLowerCase(Locale.ROOT).contains(query)) return;
            NpcPrices.Price price = NpcPrices.priceInfo(name);
            next.add(new Row(name, count, price, current.costs().contains(name)));
        });
        // Unpriced first: those are what the "+ ?" on a readout is waiting for. Then the cost,
        // which every line under it is weighed against, then the column as you would read it.
        next.sort(Comparator.<Row>comparingInt(r -> r.price() == null ? 0 : r.cost() ? 1 : 2)
                .thenComparingLong(r -> r.price() == null ? 0 : -r.price().coins())
                .thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));
        rows = next;
        scroll = Math.max(0, Math.min(scroll, rows.size() - visible()));
    }

    private static String source(NpcPrices.Price price) {
        return switch (price.source()) {
            case "Custom" -> "Yours";
            case "Learned sale" -> "Last sale";
            case "Built-in NPC" -> "NPC";
            default -> "Default";
        };
    }

    private int rowAt(int mx, int my) {
        int index = scroll + Math.floorDiv(my - listTop(), ROW);
        boolean inside = mx >= listX() && mx < listX() + listW() && my >= listTop() && my < listTop() + visible() * ROW;
        return inside && index >= 0 && index < rows.size() ? index : -1;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        build();
        int px = px(), py = py(), pw = pw(), ph = ph(), x = listX(), w = listW();
        g.fill(0, 0, width, height, Draw.alpha(Theme.scrim(), .30f));
        Draw.roundedRect(g, px, py, pw, ph, Theme.RADIUS_LG, Theme.panel());
        Draw.roundedRing(g, px - 1, py - 1, pw + 2, ph + 2, Theme.RADIUS_LG + 1, Theme.line());

        Draw.text(g, font, "Drop prices", x, py + 14, Theme.text());
        Draw.textRight(g, font, "Back", px + pw - PAD, py + 14,
                in(px + pw - PAD - 30, py + 8, 34, 20, mouseX, mouseY) ? Theme.accent() : Theme.muted());
        for (int i = 0; i < TABS.size(); i++) {
            boolean selected = i == tab, hot = in(tabX(i), py + 33, TAB_W, TAB_H, mouseX, mouseY);
            Draw.roundedRect(g, tabX(i), py + 33, TAB_W, TAB_H, 4,
                    selected ? Draw.alpha(Theme.accent(), .2f) : hot ? Theme.hover() : Theme.raised());
            Draw.textCentered(g, font, TABS.get(i).name(), tabX(i) + TAB_W / 2, py + 38, selected ? Theme.accent() : Theme.muted());
        }
        Draw.roundedOutline(g, searchX(), py + 33, SEARCH_W, TAB_H, 4, Theme.line(), Theme.input());

        Draw.text(g, font, "Drop", x + 6, py + 60, Theme.dim());
        Draw.textRight(g, font, "Got", chipX() - 70, py + 60, Theme.dim());
        Draw.textRight(g, font, "Each", x + w - 6, py + 60, Theme.dim());
        Draw.rect(g, x, py + 70, w, 1, Theme.hair());

        amount.visible = false;
        for (int i = scroll; i < Math.min(rows.size(), scroll + visible()); i++) drawRow(g, rows.get(i), i, mouseX, mouseY);
        if (rows.isEmpty()) {
            boolean searching = !search.getValue().isBlank();
            Draw.textCentered(g, font, searching ? "No matching drops" : "Nothing yet", px + pw / 2, listTop() + 40, Theme.muted());
            if (!searching) Draw.textCentered(g, font, "Drops show here once you get them", px + pw / 2, listTop() + 54, Theme.dim());
        }

        long unpriced = rows.stream().filter(r -> r.price() == null).count();
        String foot = !status.isEmpty() ? status
                : unpriced > 0 ? unpriced + " still need a price. Click one, type 2.5m, press Enter."
                : "Click a price to change it. Right click clears yours.";
        Draw.text(g, font, Draw.fit(font, foot, w - 12), x + 6, py + ph - 19,
                statusBad ? Theme.neg() : status.isEmpty() ? Theme.dim() : Theme.pos());
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawRow(GuiGraphicsExtractor g, Row row, int index, int mouseX, int mouseY) {
        int x = listX(), w = listW(), y = listTop() + (index - scroll) * ROW, chip = chipX(), chipY = y + (ROW - CHIP_H) / 2 - 1;
        if (rowAt(mouseX, mouseY) == index) Draw.roundedRect(g, x, y, w, ROW - 2, 4, Theme.hover());
        String label = label(row.name());
        int nameW = chip - 110 - (x + 6);
        Draw.text(g, font, Draw.fit(font, label, nameW), x + 6, y + 6, colour(row.name()));
        if (row.cost()) Draw.text(g, font, "cost", x + 10 + Math.min(nameW, font.width(label)), y + 6, Theme.dim());
        if (row.count() > 0) Draw.textRight(g, font, String.format(Locale.ROOT, "%,d", row.count()), chip - 70, y + 6, Theme.muted());
        if (row.price() != null) Draw.textRight(g, font, source(row.price()), chip - 6, y + 6, Theme.dim());

        if (row.name().equals(editing)) {
            Draw.roundedOutline(g, chip, chipY, CHIP_W, CHIP_H, 4, Theme.accent(), Theme.input());
            amount.setX(chip + 6);
            amount.setY(chipY + 4);
            amount.visible = true;
        } else if (row.price() == null) {
            Draw.roundedOutline(g, chip, chipY, CHIP_W, CHIP_H, 4, Theme.accent(), Theme.input());
            Draw.textCentered(g, font, "Set price", chip + CHIP_W / 2, chipY + 4, Theme.accent());
        } else {
            Draw.roundedRect(g, chip, chipY, CHIP_W, CHIP_H, 4, Theme.raised());
            Draw.textCentered(g, font, brief(row.price().coins()), chip + CHIP_W / 2, chipY + 4, Theme.text());
        }
    }

    /** A dropped pet is always level 1, so the prefix only pushes its name along. */
    private static String label(String name) {
        return name.replaceFirst("^\\[Lvl \\d+\\]\\s*", "");
    }

    /** A pet's rarity is in its name, where the item lookup does not read it. */
    private static int colour(String name) {
        int rarity = name.endsWith("(Mythic)") ? 0xFF55FF : name.endsWith("(Legendary)") ? 0xFFAA00 : name.endsWith("(Epic)") ? 0xAA00AA
                : name.endsWith("(Rare)") ? 0x5555FF : name.endsWith("(Uncommon)") ? 0x55FF55 : Rarity.colour(name, Theme.text() & 0xFFFFFF);
        return 0xFF000000 | rarity;
    }

    /**
     * Exact, unlike a readout's one decimal: 1.25m shown as 1.3m is what the box would open
     * with, so clicking a price and clicking away would have quietly changed it.
     */
    private static String brief(long coins) {
        long unit = coins >= 1_000_000_000L ? 1_000_000_000L : coins >= 1_000_000L ? 1_000_000L : coins >= 1_000L ? 1_000L : 1L;
        String suffix = unit == 1_000_000_000L ? "b" : unit == 1_000_000L ? "m" : unit == 1_000L ? "k" : "";
        return new BigDecimal(coins).divide(BigDecimal.valueOf(unit)).stripTrailingZeros().toPlainString() + suffix;
    }

    private boolean in(int x, int y, int w, int h, int mx, int my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void edit(String name) {
        editing = name;
        Long own = NpcPrices.customPrice(name);
        // The box only reads three decimals back, so a longer price opens as plain digits.
        amount.setValue(own == null ? "" : own.equals(NpcPrices.parseAmount(brief(own))) ? brief(own) : String.valueOf(own));
        setFocused(amount);
        amount.setFocused(true);
        search.setFocused(false);
        status = "";
    }

    private void stopEditing() {
        editing = null;
        amount.setFocused(false);
        setFocused(null);
    }

    /** False when what was typed is not a price, so the box stays open to fix it. */
    private boolean commit() {
        if (editing == null) return true;
        String text = amount.getValue().trim();
        if (text.isEmpty()) { stopEditing(); return true; }
        Long coins = NpcPrices.parseAmount(text);
        if (coins == null) { say("Type a price like 250000, 800k or 2.5m", true); return false; }
        if (!NpcPrices.setCustom(editing, coins)) { say("Could not save that price", true); return false; }
        say("Saved " + brief(coins) + " for " + label(editing), false);
        stopEditing();
        return true;
    }

    private void say(String text, boolean bad) {
        status = text;
        statusBad = bad;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y(), px = px(), py = py();
        if (event.button() == 0 && in(px + pw() - PAD - 30, py + 8, 34, 20, mx, my)) { minecraft.setScreen(parent); return true; }
        if (event.button() == 0) for (int i = 0; i < TABS.size(); i++)
            if (in(tabX(i), py + 33, TAB_W, TAB_H, mx, my)) {
                commit();
                tab = i; scroll = 0; status = "";
                return true;
            }
        int index = rowAt(mx, my);
        if (index >= 0) {
            Row row = rows.get(index);
            if (event.button() == 1) {
                if (NpcPrices.customPrice(row.name()) != null && NpcPrices.clearCustom(row.name())) say("Cleared your price for " + label(row.name()), false);
                if (row.name().equals(editing)) stopEditing();
                return true;
            }
            if (event.button() == 0 && !row.name().equals(editing) && commit()) edit(row.name());
            return true;
        }
        if (editing != null && !super.mouseClicked(event, doubleClick)) { commit(); return true; }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(Math.max(0, rows.size() - visible()), scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (editing != null && (event.key() == 257 || event.key() == 335)) {
            String done = editing;
            if (!commit()) return true;
            // On to the next one with no price, because that is usually why this page is open.
            build();
            rows.stream().filter(r -> r.price() == null && !r.name().equals(done)).findFirst().ifPresent(r -> {
                scroll = Math.max(0, Math.min(rows.indexOf(r), Math.max(0, rows.size() - visible())));
                edit(r.name());
            });
            return true;
        }
        if (event.key() == 256) {
            if (editing != null) stopEditing();
            else minecraft.setScreen(parent);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
