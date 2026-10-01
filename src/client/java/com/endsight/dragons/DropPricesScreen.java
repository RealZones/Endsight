package com.endsight.dragons;

import com.endsight.qol.NpcPrices;
import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** Slayer-only price editor. The shared file holds overrides; sale evidence stays local. */
public final class DropPricesScreen extends Screen {
    private final Screen parent;
    private EditBox search;
    private EditBox amount;
    private String selected;
    private String status = "";
    private int scroll;

    private DropPricesScreen(Screen parent) {
        super(Component.literal("Slayer drop prices"));
        this.parent = parent;
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new DropPricesScreen(mc.screen));
    }

    private int x() { return Math.max(8, (width - w()) / 2); }
    private int y() { return Math.max(8, (height - h()) / 2); }
    private int w() { return Math.min(610, width - 16); }
    private int h() { return Math.min(420, height - 16); }
    private int listW() { return Math.min(248, Math.max(145, w() / 2)); }
    private int rowsTop() { return y() + 80; }
    private int visibleRows() { return Math.max(1, (h() - 105) / 20); }
    private int buttonY() { return y() + 171; }
    private int footerY() { return y() + h() - (h() < 270 ? 28 : 45); }

    @Override
    protected void init() {
        int px = x(), py = y();
        search = new EditBox(font, px + 18, py + 49, listW() - 22, 18, Component.literal("Search Slayer drops"));
        search.setMaxLength(80);
        search.setHint(Component.literal("Search drops"));
        search.setResponder(value -> scroll = 0);
        addRenderableWidget(search);

        amount = new EditBox(font, px + listW() + 15, py + 143,
                Math.max(80, w() - listW() - 31), 18, Component.literal("Custom price per item"));
        amount.setMaxLength(24);
        amount.setHint(Component.literal("e.g. 2.5m"));
        addRenderableWidget(amount);
        List<String> names = BossDrops.knownSlayerDropNames();
        if (selected == null || !names.contains(selected)) selected = names.isEmpty() ? null : names.getFirst();
        showSelected();
    }

    private List<String> filtered() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        return BossDrops.knownSlayerDropNames().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    private void showSelected() {
        if (amount != null) {
            Long custom = NpcPrices.customPrice(selected);
            amount.setValue(custom == null ? "" : String.valueOf(custom));
        }
        status = "";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int px = x(), py = y(), pw = w(), ph = h(), lw = listW();
        g.fill(0, 0, width, height, Theme.scrim());
        Draw.roundedOutline(g, px, py, pw, ph, 8, Theme.line(), Theme.bg());
        Draw.text(g, font, "Slayer drop prices", px + 18, py + 17, Theme.text());
        Draw.text(g, font, "Back", px + pw - 36, py + 17, Theme.accent());
        Draw.rect(g, px + 12, py + 38, pw - 24, 1, Theme.line());
        Draw.rect(g, px + lw + 4, py + 47, 1, ph - 62, Theme.line());

        List<String> names = filtered();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, names.size() - visibleRows())));
        for (int i = scroll; i < Math.min(names.size(), scroll + visibleRows()); i++) {
            int ry = rowsTop() + (i - scroll) * 20;
            String name = names.get(i);
            if (name.equals(selected)) Draw.roundedRect(g, px + 12, ry - 3, lw - 16, 18, 3, Theme.raised());
            String label = fit(name, lw - 62);
            Draw.text(g, font, label, px + 18, ry, name.equals(selected) ? Theme.text() : Theme.muted());
            if (NpcPrices.customPrice(name) != null) Draw.textRight(g, font, "C", px + lw - 12, ry, Theme.accent());
            else if (NpcPrices.observedPrice(name) != null) Draw.textRight(g, font, "$", px + lw - 12, ry, Theme.pos());
        }
        if (names.isEmpty()) Draw.text(g, font, "No matching Slayer drops", px + 18, rowsTop(), Theme.dim());

        int dx = px + lw + 16;
        if (selected != null) {
            int detailW = pw - lw - 35;
            Draw.text(g, font, fit(selected, detailW), dx, py + 56, Theme.text());
            NpcPrices.Price effective = NpcPrices.priceInfo(selected);
            NpcPrices.Price observed = NpcPrices.observedPrice(selected);
            Draw.text(g, font, "Current", dx, py + 82, Theme.dim());
            Draw.text(g, font, fit(effective == null ? "Unpriced" : format(effective.coins()) + "  " + effective.source(), detailW),
                    dx, py + 95, effective == null ? Theme.neg() : Theme.pos());
            Draw.text(g, font, fit("Sale / NPC evidence", detailW), dx, py + 112, Theme.dim());
            Draw.text(g, font, fit(observed == null ? "None yet" : format(observed.coins()) + "  " + observed.source(), detailW),
                    dx, py + 125, Theme.muted());
            Draw.text(g, font, fit("Custom price per item", detailW), dx, py + 133, Theme.dim());
            button(g, dx, buttonY(), 49, "Save", Theme.accent());
            button(g, dx + 56, buttonY(), 51, "Clear", Theme.muted());
        }
        Draw.rect(g, px + 12, footerY(), pw - 24, 1, Theme.line());
        String footer = status.isEmpty() ? "Share config/endsight/drop-prices.json" : status;
        int footerColour = status.startsWith("Could") || status.startsWith("Invalid") || status.startsWith("Enter")
                ? Theme.neg() : status.isEmpty() ? Theme.dim() : Theme.pos();
        Draw.text(g, font, fit(footer, pw - 160), px + 18, footerY() + 13, footerColour);
        button(g, px + pw - 80, footerY() + 6, 65, "Reload", Theme.accent());
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, String label, int colour) {
        Draw.roundedOutline(g, x, y, w, 18, 3, Theme.line(), Theme.surface());
        Draw.text(g, font, label, x + (w - font.width(label)) / 2, y + 5, colour);
    }

    private String fit(String value, int pixels) {
        if (pixels <= 0) return "";
        if (font.width(value) <= pixels) return value;
        if (font.width("...") > pixels) return "";
        while (!value.isEmpty() && font.width(value + "...") > pixels) value = value.substring(0, value.length() - 1);
        return value + "...";
    }

    private static String format(long coins) { return String.format(Locale.ROOT, "%,d", coins); }

    private boolean in(int x, int y, int w, int h, int mx, int my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y(), px = x(), py = y(), lw = listW();
        if (in(px + w() - 48, py + 9, 43, 24, mx, my)) { minecraft.setScreen(parent); return true; }
        if (in(px + w() - 80, footerY() + 6, 65, 18, mx, my)) {
            boolean reloaded = NpcPrices.reloadCustom();
            showSelected();
            status = reloaded ? "Prices reloaded" : "Invalid price file";
            return true;
        }
        if (selected != null) {
            int dx = px + lw + 16;
            if (in(dx, buttonY(), 49, 18, mx, my)) { save(); return true; }
            if (in(dx + 56, buttonY(), 51, 18, mx, my)) {
                status = NpcPrices.clearCustom(selected) ? "Custom price cleared" : "Could not save price file";
                if (status.startsWith("Custom")) amount.setValue("");
                return true;
            }
        }
        if (in(px + 12, rowsTop() - 3, lw - 16, visibleRows() * 20, mx, my)) {
            int index = scroll + (my - rowsTop() + 3) / 20;
            List<String> names = filtered();
            if (index >= 0 && index < names.size()) { selected = names.get(index); showSelected(); }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void save() {
        Long coins = NpcPrices.parseAmount(amount.getValue());
        if (coins == null) { status = "Enter coins, e.g. 250000 or 2.5m"; return; }
        status = NpcPrices.setCustom(selected, coins) ? "Custom price saved" : "Could not save price file";
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (in(x() + 12, rowsTop() - 3, listW() - 16, h() - 95, (int) mouseX, (int) mouseY)) {
            scroll = Math.max(0, Math.min(Math.max(0, filtered().size() - visibleRows()), scroll - (int) Math.signum(scrollY) * 3));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == 257 || event.key() == 335) && amount.isFocused()) { save(); return true; }
        if (event.key() == 256) { minecraft.setScreen(parent); return true; }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
