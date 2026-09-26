package com.endsight.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One module's settings, laid out from its {@link Setting} list.
 *
 * Deliberately the same panel, sidebar width, header height and palette as the
 * module browser, so opening settings feels like moving within one thing rather than
 * arriving somewhere else. The sidebar is kept - empty of nav but carrying the brand
 * and a back target - because a panel that changes shape between screens is the
 * cheapest way to make a UI feel unfinished.
 */
public class SettingsScreen extends Screen {

    private final Screen parent;
    private final String brand;
    private final Module module;

    private int scroll;
    private int contentHeight;
    private Setting.Slider dragging;
    /** The id waiting for a key, or null. One at a time - two would race for the press. */
    private String binding;
    /** The command row being typed into, or null. */
    private Setting.Command editing;
    /** The colour picker; open when a Color row's swatch was clicked. */
    private final ColorPicker picker = new ColorPicker();

    private final Map<String, Anim> anims = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();

    private record Row(Setting setting, int x, int y, int w, int h) {
    }

    private static final int ROW_H = 46;
    private static final int SECTION_ROW_H = 30;
    /** A note wraps to this many lines at most; a tier with forty items is still a glance. */
    private static final int NOTE_LINES = 6;

    private int noteH(Setting.Note n, int w) {
        return 24 + Draw.wrap(font, n.description(), w - 16, NOTE_LINES).size() * 11;
    }
    private static final int TRACK_H = 4;
    private static final int KNOB = 10;

    /**
     * Where each module's page was scrolled to, and which page was open when the GUI
     * was closed - so opening it again lands where you left it, not on a fresh browser.
     * Cleared by Back and Escape, which are you leaving the page on purpose.
     */
    private static final Map<String, Integer> scrolls = new HashMap<>();
    private static String openModule;

    public SettingsScreen(Screen parent, String brand, Module module) {
        super(Component.literal(module.title()));
        this.parent = parent;
        this.brand = brand;
        this.module = module;
        this.scroll = scrolls.getOrDefault(module.id(), 0);
        openModule = module.id();
    }

    /** The id of the settings page that was open when the GUI last closed, or null. */
    public static String openModule() {
        return openModule;
    }

    @Override
    public void removed() {
        scrolls.put(module.id(), scroll);
        super.removed();
    }

    /** Back to the browser, on purpose: the next open starts there. */
    private void back() {
        openModule = null;
        minecraft.setScreen(parent);
    }

    private final ThemeRow themeRow = new ThemeRow();

    private int panelX() { return Theme.panelX(width); }
    private int panelY() { return Theme.panelY(height); }
    private int panelW() { return Theme.panelW(width); }
    private int panelH() { return Theme.panelH(height); }
    private int contentX() { return panelX() + Theme.SIDEBAR_W; }
    private int contentW() { return panelW() - Theme.SIDEBAR_W; }
    private int contentTop() { return panelY() + Theme.HEADER_H; }
    private int contentBottom() { return panelY() + panelH() - Theme.PAD; }

    private void layout() {
        rows.clear();
        int x = contentX() + Theme.PAD;
        int w = contentW() - Theme.PAD * 2;
        int y = contentTop() + Theme.PAD - scroll;

        for (Setting s : module.settings()) {
            int h = s instanceof Setting.Section ? SECTION_ROW_H
                    : s instanceof Setting.Note n ? noteH(n, w) : ROW_H;
            rows.add(new Row(s, x, y, w, h));
            y += h + 4;
        }
        contentHeight = (y + scroll) - (contentTop() + Theme.PAD) + Theme.PAD;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (contentBottom() - contentTop()));
    }

    private Anim anim(String key) {
        return anims.computeIfAbsent(key, k -> new Anim(0f));
    }

    /** Where a slider's track runs. Shared by drawing and dragging so they cannot disagree. */
    private static int trackX(Row r) {
        return r.x;
    }

    private static int trackW(Row r) {
        return r.w - 60;
    }

    private static int trackY(Row r) {
        return r.y + r.h - 16;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        layout();
        int clamped = Math.max(0, Math.min(scroll, maxScroll()));
        if (clamped != scroll) {
            scroll = clamped;
            layout();
        }

        Font font = this.font;
        g.fill(0, 0, width, height, Theme.scrim());

        int px = panelX(), py = panelY();
        Draw.roundedOutline(g, px - 1, py - 1, panelW() + 2, panelH() + 2,
                Theme.RADIUS_LG + 1, Theme.line(), Theme.bg());

        // sidebar, matching the browser so the panel does not change shape
        Draw.roundedRect(g, px, py, Theme.SIDEBAR_W, panelH(), Theme.RADIUS_LG,
                Theme.surface(), true, false, true, false);
        Draw.rect(g, px + Theme.SIDEBAR_W - 1, py + Theme.RADIUS_LG, 1,
                panelH() - Theme.RADIUS_LG * 2, Theme.line());
        int by = py + Theme.HEADER_H / 2 - 4;
        Draw.roundedRect(g, px + 16, by + 1, 5, 5, 2, Theme.accent());
        Draw.text(g, font, brand, px + 27, by, Theme.text());
        Draw.rect(g, px, py + Theme.HEADER_H - 1, Theme.SIDEBAR_W, 1, Theme.line());

        drawBack(g, font, mouseX, mouseY);
        drawModuleState(g, font, mouseX, mouseY);
        themeRow.layout(px, py, panelH());
        themeRow.draw(g, font, px, mouseX, mouseY);
        drawRows(g, font, mouseX, mouseY);

        // header last, so a scrolled row slides under it
        Draw.roundedRect(g, contentX(), py, contentW(), Theme.HEADER_H, Theme.RADIUS_LG,
                Theme.bg(), false, true, false, false);
        Draw.rect(g, contentX(), py + Theme.HEADER_H - 1, contentW(), 1, Theme.line());
        Draw.text(g, font, module.title(), contentX() + Theme.PAD, py + 20, Theme.text());
        int titleW = font.width(module.title());
        Draw.text(g, font, module.category(), contentX() + Theme.PAD + titleW + 10, py + 20, Theme.dim());

        if (picker.open() && placePicker()) picker.draw(g, font, mouseX, mouseY);
    }

    /**
     * Puts the picker under its row, or closes it if the row has scrolled away - a
     * picker floating over the header with nothing to belong to is worse than none.
     */
    private boolean placePicker() {
        for (Row r : rows) {
            if (r.setting != picker.setting()) continue;
            if (r.y + r.h < contentTop() || r.y > contentBottom()) break;
            picker.layout(r.x, r.y, r.w, r.h, contentX() + 4, contentX() + contentW() - 4, contentBottom());
            return true;
        }
        picker.close();
        return false;
    }

    /**
     * A Choice-shaped chip with the colour itself in it and its hex beside, so the
     * value can be read without opening anything. Chroma shows as the moving colour.
     */
    private void drawSwatch(GuiGraphicsExtractor g, Font font, Row r, Setting.Color c, int mouseX, int mouseY) {
        int v = c.get().getAsInt();
        String text = v == Setting.Color.CHROMA ? "Chroma" : Setting.Color.hex(v);
        int w = font.width(text) + 34;
        int cx = r.x + r.w - w, cy = r.y + r.h / 2 - 10;
        boolean open = picker.setting() == c;
        float a = anim("val:" + c.label()).to(open || contains(cx, cy, w, 20, mouseX, mouseY) ? 1f : 0f, Theme.EASE_FAST);
        Draw.roundedRect(g, cx, cy, w, 20, Theme.RADIUS - 2, Draw.lerp(Theme.raised(), Theme.hover(), a));
        Draw.roundedRect(g, cx + 7, cy + 5, 10, 10, 3, Setting.Color.live(v));
        Draw.text(g, font, text, cx + 23, cy + 6, Draw.lerp(Theme.muted(), Theme.text(), a));
    }

    private void drawBack(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int x = panelX() + 10, y = panelY() + Theme.HEADER_H + 22, w = Theme.SIDEBAR_W - 20, h = 22;
        boolean hovered = contains(x, y, w, h, mouseX, mouseY);
        float a = anim("back").to(hovered ? 1f : 0f, Theme.EASE_FAST);
        if (a > 0.01f) {
            Draw.roundedRect(g, x, y, w, h, Theme.RADIUS, Draw.alpha(Theme.accent(), 0.16f * a));
        }
        Draw.text(g, font, "< Back", x + 12, y + 7, Draw.lerp(Theme.muted(), Theme.text(), a));
    }

    private void drawRows(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int top = contentTop(), bottom = contentBottom();
        Draw.roundedRect(g, contentX(), top, contentW(), bottom - top, Theme.RADIUS_LG,
                Theme.bg(), false, false, false, true);

        if (rows.isEmpty()) {
            Draw.textCentered(g, font, "This module has nothing to configure.",
                    contentX() + contentW() / 2, top + 60, Theme.dim());
            return;
        }

        // Clip the content, not whole rows: adding sections must not make controls
        // disappear before their visible part has scrolled out of the viewport.
        g.enableScissor(contentX(), top, contentX() + contentW(), bottom);
        for (Row r : rows) {
            if (r.y + r.h <= top || r.y >= bottom) continue;

            if (r.setting instanceof Setting.Section sec) {
                Draw.text(g, font, sec.label().toUpperCase(), r.x, r.y + 14, Theme.muted());
                int lx = r.x + font.width(sec.label().toUpperCase()) + 10;
                Draw.rect(g, lx, r.y + 17, r.x + r.w - lx, 1, Theme.hair());
                continue;
            }
            if (r.setting instanceof Setting.Note n) {
                // A heading and its paragraph. No hover, nothing to click.
                Draw.text(g, font, n.label(), r.x, r.y + 8, Theme.text());
                int ly = r.y + 20;
                for (String line : Draw.wrap(font, n.description(), r.w - 16, NOTE_LINES)) {
                    Draw.text(g, font, line, r.x, ly, Theme.dim());
                    ly += 11;
                }
                continue;
            }

            boolean hovered = contains(r.x, r.y, r.w, r.h, mouseX, mouseY);
            float h = anim("row:" + r.setting.label()).to(hovered ? 1f : 0f, Theme.EASE_FAST);
            Draw.roundedRect(g, r.x - 8, r.y, r.w + 16, r.h, Theme.RADIUS,
                    Draw.lerp(Theme.bg(), Theme.surface(), h));

            if (r.setting instanceof Setting.Command c) {
                drawCommand(g, font, r, c, mouseX, mouseY);
                continue;
            }

            Draw.text(g, font, r.setting.label(), r.x, r.y + 8, Theme.text());
            if (!r.setting.description().isBlank()) {
                Draw.text(g, font, Draw.fit(font, r.setting.description(), r.w - 70),
                        r.x, r.y + 20, Theme.dim());
            }

            if (r.setting instanceof Setting.Toggle t) {
                float on = anim("val:" + t.label()).to(t.get().getAsBoolean() ? 1f : 0f, Theme.EASE_FAST);
                drawToggle(g, r.x + r.w - 22, r.y + r.h / 2 - 5, on);
            } else if (r.setting instanceof Setting.Slider s) {
                drawSlider(g, font, r, s, mouseX, mouseY);
            } else if (r.setting instanceof Setting.Choice c) {
                String v = c.get().get();
                int w = font.width(v) + 20;
                int cx = r.x + r.w - w;
                float a = anim("val:" + c.label())
                        .to(contains(cx, r.y + r.h / 2 - 10, w, 20, mouseX, mouseY) ? 1f : 0f, Theme.EASE_FAST);
                Draw.roundedRect(g, cx, r.y + r.h / 2 - 10, w, 20, Theme.RADIUS - 2,
                        Draw.lerp(Theme.raised(), Theme.hover(), a));
                Draw.textCentered(g, font, v, cx + w / 2, r.y + r.h / 2 - 4,
                        Draw.lerp(Theme.muted(), Theme.text(), a));
            } else if (r.setting instanceof Setting.Color c) {
                drawSwatch(g, font, r, c, mouseX, mouseY);
            } else if (r.setting instanceof Setting.Action act) {
                // Same shape as a Choice so the right-hand column stays one column, but
                // accent-lettered at rest: a Choice shows a value you can change, this
                // shows a verb you can press, and only the colour says which.
                String v = act.button();
                int w = font.width(v) + 20;
                int cx = r.x + r.w - w;
                float a = anim("val:" + act.label())
                        .to(contains(cx, r.y + r.h / 2 - 10, w, 20, mouseX, mouseY) ? 1f : 0f, Theme.EASE_FAST);
                Draw.roundedRect(g, cx, r.y + r.h / 2 - 10, w, 20, Theme.RADIUS - 2,
                        Draw.lerp(Draw.alpha(Theme.accent(), 0.18f), Theme.accent(), a));
                Draw.textCentered(g, font, v, cx + w / 2, r.y + r.h / 2 - 4,
                        Draw.lerp(Theme.accent(), Theme.bg(), a));
            } else if (r.setting instanceof Setting.KeyAction act) {
                drawKeyAction(g, font, r, act, mouseX, mouseY);
            }
        }

        g.disableScissor();
        Draw.roundedRect(g, contentX(), bottom, contentW(), panelY() + panelH() - bottom,
                Theme.RADIUS_LG, Theme.bg(), false, false, false, true);
    }

    /**
     * The module's own on/off switch, in the sidebar.
     *
     * A settings page for something that is switched off elsewhere is a page you have
     * to leave to act on. It also gives an otherwise empty sidebar something worth
     * having, which is the honest reason it went here first.
     */
    private void drawModuleState(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int x = panelX() + 16, y = panelY() + Theme.HEADER_H + 62, w = Theme.SIDEBAR_W - 32;
        Draw.rect(g, panelX(), y - 14, Theme.SIDEBAR_W, 1, Theme.hair());
        Draw.text(g, font, "MODULE", x, y - 6, Theme.dim());

        boolean on = module.isEnabled();
        if (!module.implemented()) {
            Draw.roundedRect(g, x - 6, y + 10, w + 12, 22, Theme.RADIUS, Theme.bg());
            Draw.text(g, font, "Not implemented", x, y + 17, Theme.dim());
            return;
        }
        boolean hovered = contains(x, y + 10, w, 22, mouseX, mouseY);
        float a = anim("state").to(hovered ? 1f : 0f, Theme.EASE_FAST);
        float t = anim("state:on").to(on ? 1f : 0f, Theme.EASE_FAST);

        Draw.roundedRect(g, x - 6, y + 10, w + 12, 22, Theme.RADIUS,
                Draw.lerp(Theme.bg(), Theme.raised(), a));
        Draw.text(g, font, on ? "Enabled" : "Disabled", x, y + 17,
                on ? Theme.pos() : Theme.muted());
        drawToggle(g, x + w - 20, y + 16, t);

        // The module's own key, under its switch. This is the one that matters for a
        // module you turn on and off mid-fight, and it is the only chip on a page whose
        // settings are all sliders.
        Draw.text(g, font, "KEY", x, y + 44, Theme.dim());
        drawChip(g, font, Keybinds.moduleId(module), x + w - CHIP_W, y + 40, mouseX, mouseY);
    }

    /**
     * Track, filled portion, knob.
     *
     * The knob grows under the cursor and while dragging rather than changing colour,
     * because the fill already carries the accent and a second accent-coloured thing
     * moving next to it reads as two controls instead of one.
     */
    private void drawSlider(GuiGraphicsExtractor g, Font font, Row r, Setting.Slider s,
                            int mouseX, int mouseY) {
        int tx = trackX(r), tw = trackW(r), ty = trackY(r);
        double f = s.fraction();
        int fill = (int) Math.round(tw * f);

        boolean over = contains(tx - 4, ty - 8, tw + 8, 16, mouseX, mouseY);
        float a = anim("val:" + s.label()).to(over || dragging == s ? 1f : 0f, Theme.EASE_FAST);

        Draw.roundedRect(g, tx, ty, tw, TRACK_H, TRACK_H / 2, Theme.line());
        if (fill > 0) {
            Draw.roundedRect(g, tx, ty, Math.max(TRACK_H, fill), TRACK_H, TRACK_H / 2, Theme.accent());
        }

        int size = KNOB + Math.round(3 * a);
        int kx = tx + fill - size / 2;
        int ky = ty + TRACK_H / 2 - size / 2;
        Draw.roundedRect(g, kx, ky, size, size, size / 2, Theme.bg());
        Draw.roundedRect(g, kx + 1, ky + 1, size - 2, size - 2, (size - 2) / 2, Theme.accent());

        Draw.textRight(g, font, s.display(), r.x + r.w, ty - 3,
                Draw.lerp(Theme.muted(), Theme.text(), a));
    }

    /** The module's key chip. Modules only - a key per setting was clutter, and said so. */
    private static final int CHIP_W = Keybinds.CHIP_W;
    private static final int CHIP_H = Keybinds.CHIP_H;

    private void drawChip(GuiGraphicsExtractor g, Font font, String id, int x, int y,
                          int mouseX, int mouseY) {
        boolean listening = id.equals(binding);
        boolean hovered = contains(x, y, CHIP_W, CHIP_H, mouseX, mouseY);
        float a = anim("bind:" + id).to(hovered || listening ? 1f : 0f, Theme.EASE_FAST);
        Keybinds.chip(g, font, id, x, y, a, listening);
    }

    // A command row: [ /command field ..................... ] [ key ] [ x ]
    private static final int REMOVE_W = 18;

    private static int cmdFieldW(Row r) {
        return r.w - CHIP_W - REMOVE_W - 16;
    }

    private void drawCommand(GuiGraphicsExtractor g, Font font, Row r, Setting.Command c, int mouseX, int mouseY) {
        int fy = r.y + r.h / 2 - 10, fw = cmdFieldW(r);
        boolean focus = editing == c;
        boolean over = contains(r.x, fy, fw, 20, mouseX, mouseY);
        float a = anim("cmd:" + c.id()).to(focus || over ? 1f : 0f, Theme.EASE_FAST);
        Draw.roundedRect(g, r.x, fy, fw, 20, Theme.RADIUS - 2, Draw.lerp(Theme.raised(), Theme.hover(), a));
        if (focus) Draw.roundedOutline(g, r.x, fy, fw, 20, Theme.RADIUS - 2, Theme.accent(), Theme.raised());
        String text = c.command();
        boolean empty = text.isEmpty();
        String shown = empty && !focus ? "type a command, /warp end" : text;
        // Long commands scroll so the end - what you are typing - stays in view.
        while (font.width(shown) > fw - 14 && shown.length() > 1) shown = shown.substring(1);
        if (focus && (System.currentTimeMillis() / 500) % 2 == 0) shown += "_";
        Draw.text(g, font, shown, r.x + 7, fy + 6, empty && !focus ? Theme.dim() : Theme.text());

        drawChip(g, font, c.id(), r.x + fw + 8, r.y + r.h / 2 - CHIP_H / 2, mouseX, mouseY);

        int xx = r.x + r.w - REMOVE_W;
        boolean overX = contains(xx, fy, REMOVE_W, 20, mouseX, mouseY);
        float ax = anim("cmdx:" + c.id()).to(overX ? 1f : 0f, Theme.EASE_FAST);
        Draw.textCentered(g, font, "x", xx + REMOVE_W / 2, fy + 6, Draw.lerp(Theme.dim(), Theme.neg(), ax));
    }

    // A keyed action row: label/description on the left, [ run ] [ key ] on the right.
    private void drawKeyAction(GuiGraphicsExtractor g, Font font, Row r, Setting.KeyAction a, int mouseX, int mouseY) {
        int bw = font.width(a.button()) + 20;
        int by = r.y + r.h / 2 - 10;
        int bx = r.x + r.w - CHIP_W - 8 - bw;
        float on = anim("keyact:" + a.id())
                .to(contains(bx, by, bw, 20, mouseX, mouseY) ? 1f : 0f, Theme.EASE_FAST);
        Draw.roundedRect(g, bx, by, bw, 20, Theme.RADIUS - 2,
                Draw.lerp(Draw.alpha(Theme.accent(), 0.18f), Theme.accent(), on));
        Draw.textCentered(g, font, a.button(), bx + bw / 2, by + 6,
                Draw.lerp(Theme.accent(), Theme.bg(), on));

        drawChip(g, font, a.id(), r.x + r.w - CHIP_W, r.y + r.h / 2 - CHIP_H / 2, mouseX, mouseY);
    }

    private void drawToggle(GuiGraphicsExtractor g, int x, int y, float t) {
        int w = 20, h = 10;
        Draw.roundedRect(g, x, y, w, h, h / 2, Draw.lerp(Theme.line(), Theme.pos(), t));
        int knobX = x + 1 + (int) ((w - h) * t);
        Draw.roundedRect(g, knobX, y + 1, h - 2, h - 2, (h - 2) / 2,
                Draw.lerp(Theme.dim(), Theme.text(), t));
    }

    // ── input ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // A chip waiting for a key takes a mouse button too - anything but left and
        // right, which are clicking and clearing. Side buttons are the usual ask.
        if (binding != null && event.button() >= 2) {
            Keybinds.set(binding, Keybinds.mouse(event.button()));
            binding = null;
            return true;
        }
        int mx = (int) event.x(), my = (int) event.y();
        layout();
        editing = null;                           // a click anywhere ends typing; the row re-focuses itself if hit

        // The picker is on top, so it sees the click first; one outside it closes it,
        // and the swatch that opened it is remembered so the same click does not reopen.
        Setting.Color wasPicking = picker.setting();
        if (picker.open() && placePicker() && picker.click(mx, my)) return true;
        picker.close();

        if (themeRow.click(mx, my)) return true;

        int bx = panelX() + 10, by = panelY() + Theme.HEADER_H + 22;
        if (contains(bx, by, Theme.SIDEBAR_W - 20, 22, mx, my)) {
            back();
            return true;
        }

        int sx = panelX() + 16, sy = panelY() + Theme.HEADER_H + 72;
        if (module.implemented()) {
            int cw = Theme.SIDEBAR_W - 32;
            if (contains(sx + cw - CHIP_W, sy + 30, CHIP_W, CHIP_H, mx, my)) {
                return listen(Keybinds.moduleId(module), event);
            }
            if (contains(sx - 6, sy, Theme.SIDEBAR_W - 20, 22, mx, my)) {
                module.toggle();
                return true;
            }
        }

        if (!contains(contentX(), contentTop(), contentW(), contentBottom() - contentTop(), mx, my)) {
            return super.mouseClicked(event, doubleClick);
        }
        for (Row r : rows) {
            if (r.y + r.h <= contentTop() || r.y >= contentBottom()) continue;
            if (!contains(r.x - 8, r.y, r.w + 16, r.h, mx, my)) continue;
            if (r.setting instanceof Setting.Note || r.setting instanceof Setting.Section) return true;
            if (r.setting instanceof Setting.Command c) {
                int fy = r.y + r.h / 2 - 10, fw = cmdFieldW(r);
                if (contains(r.x, fy, fw, 20, mx, my)) {
                    editing = c;
                    binding = null;
                } else if (contains(r.x + fw + 8, r.y + r.h / 2 - CHIP_H / 2, CHIP_W, CHIP_H, mx, my)) {
                    editing = null;
                    return listen(c.id(), event);
                } else if (contains(r.x + r.w - REMOVE_W, fy, REMOVE_W, 20, mx, my)) {
                    if (editing == c) editing = null;
                    c.remove().run();
                }
                return true;
            }

            if (r.setting instanceof Setting.Toggle t) {
                t.set().accept(!t.get().getAsBoolean());
                return true;
            }
            if (r.setting instanceof Setting.Slider s) {
                dragging = s;
                applyDrag(r, s, mx);
                return true;
            }
            if (r.setting instanceof Setting.Choice c) {
                c.next(event.button() == 1 ? -1 : 1);
                return true;
            }
            if (r.setting instanceof Setting.Color c) {
                if (wasPicking != c) picker.open(c);
                return true;
            }
            if (r.setting instanceof Setting.KeyAction a) {
                int bw = font.width(a.button()) + 20;
                int by2 = r.y + r.h / 2 - 10;
                int bx2 = r.x + r.w - CHIP_W - 8 - bw;
                if (contains(r.x + r.w - CHIP_W, r.y + r.h / 2 - CHIP_H / 2, CHIP_W, CHIP_H, mx, my)) {
                    return listen(a.id(), event);
                }
                if (contains(bx2, by2, bw, 20, mx, my)) a.run().run();
                return true;
            }
            if (r.setting instanceof Setting.Action a) {
                a.run().run();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (picker.drag((int) event.x(), (int) event.y())) return true;
        if (dragging != null) {
            layout();
            for (Row r : rows) {
                if (r.setting == dragging) {
                    applyDrag(r, dragging, (int) event.x());
                    return true;
                }
            }
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = null;
        picker.release();
        return super.mouseReleased(event);
    }

    private void applyDrag(Row r, Setting.Slider s, int mx) {
        int tx = trackX(r), tw = trackW(r);
        double f = tw <= 0 ? 0 : (mx - tx) / (double) tw;
        f = Math.max(0, Math.min(1, f));
        s.set().accept(s.clamp(s.min() + f * (s.max() - s.min())));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(scroll + (int) (-scrollY * 26), maxScroll()));
        return true;
    }

    /**
     * Start waiting for a key for {@code id} - or, on a right-click, unbind it.
     *
     * Unbinding is on the right button rather than a second chip because there is no
     * room for one, and because a chip you have to click twice to clear (once to arm,
     * once with a key that means "none") is a worse guess than the one every launcher
     * already uses.
     */
    private boolean listen(String id, MouseButtonEvent event) {
        if (event.button() == 1) {
            Keybinds.clear(id);
            binding = null;
        } else {
            binding = id.equals(binding) ? null : id;
        }
        return true;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (picker.typing()) {
            for (char ch : event.codepointAsString().toCharArray()) picker.charTyped(ch);
            return true;
        }
        if (editing != null) {
            if (event.isAllowedChatCharacter()) editing.command(editing.command() + event.codepointAsString());
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (picker.keyPressed(event.key())) return true;
        if (editing != null) {
            if (event.key() == 259 && !editing.command().isEmpty()) {          // backspace
                editing.command(editing.command().substring(0, editing.command().length() - 1));
            } else if (event.key() == 257 || event.key() == 256 || event.key() == 335) {   // enter, escape, numpad enter
                editing = null;
            }
            return true;
        }
        if (binding != null) {
            // Escape cancels rather than binding, so there is a way out of a chip you
            // armed by accident; anything else, including a modifier on its own, binds.
            if (event.key() != 256) Keybinds.set(binding, event.key());
            binding = null;
            return true;
        }
        if (event.key() == 256) {                 // escape goes back, not out
            back();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean contains(int x, int y, int w, int h, int px, int py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
