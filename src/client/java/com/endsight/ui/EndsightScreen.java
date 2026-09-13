package com.endsight.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The module browser: sidebar, categories, search, scrolling card grid.
 *
 * Knows about Module and ModuleRegistry and nothing else. No feature manager, no
 * config, no mod id - hand it a registry and it draws whatever is in there, which
 * is what lets the same screen serve a bazaar macro and a dragon sim.
 *
 * Hit testing is done against rectangles computed by layout() rather than by
 * vanilla Buttons. Buttons would have to be repositioned every time the list
 * scrolls or the filter changes, and keeping widget bounds in sync with a scrolling
 * grid is a bug factory - these rectangles are recomputed from scratch each frame,
 * so they cannot drift out of step with what is actually on screen.
 */
public class EndsightScreen extends Screen {

    private final ModuleRegistry registry;
    private final String brand;

    private static String category;
    private static String query = "";
    private static int scroll;
    /** The module id waiting for a key, or null. Not static: a half-armed chip should not outlive the screen. */
    private String binding;

    private int contentHeight;

    private final Map<String, Anim> hoverAnims = new HashMap<>();
    private final Map<String, Anim> toggleAnims = new HashMap<>();
    private final Anim entrance = new Anim(0f);

    private final List<Placed> cards = new ArrayList<>();
    private final List<Nav> navItems = new ArrayList<>();
    private final ThemeRow themeRow = new ThemeRow();

    private record Placed(Module module, int x, int y, int w, int h,
                           int gearX, int gearW) {
    }

    private record Nav(String label, String category, int x, int y, int w, int h) {
    }

    public EndsightScreen(String brand, ModuleRegistry registry) {
        super(Component.literal(brand));
        this.brand = brand;
        this.registry = registry;
    }

    // -- geometry -------------------------------------------------------------

    private int panelX() {
        return Theme.panelX(width);
    }

    private int panelY() {
        return Theme.panelY(height);
    }

    private int panelW() {
        return Theme.panelW(width);
    }

    private int panelH() {
        return Theme.panelH(height);
    }

    private int contentX() {
        return panelX() + Theme.SIDEBAR_W;
    }

    private int contentW() {
        return panelW() - Theme.SIDEBAR_W;
    }

    private int contentTop() {
        return panelY() + Theme.HEADER_H;
    }

    private int contentBottom() {
        return panelY() + panelH() - Theme.PAD;
    }

    private void layout() {
        cards.clear();
        navItems.clear();

        int navY = panelY() + Theme.HEADER_H + 22;
        int navX = panelX() + 10;
        int navW = Theme.SIDEBAR_W - 20;

        navItems.add(
                new Nav(
                        "All Modules",
                        null,
                        navX,
                        navY,
                        navW,
                        Theme.NAV_H
                )
        );

        navY += Theme.NAV_H + Theme.NAV_GAP;

        for (String c : registry.categories()) {
            navItems.add(
                    new Nav(
                            c,
                            c,
                            navX,
                            navY,
                            navW,
                            Theme.NAV_H
                    )
            );

            navY += Theme.NAV_H + Theme.NAV_GAP;
        }

        themeRow.layout(panelX(), panelY(), panelH());

        int x0 = contentX() + Theme.PAD;
        int avail = contentW() - Theme.PAD * 2;

        int columns = Math.max(
                1,
                (avail + Theme.GAP)
                        / (Theme.CARD_MIN_W + Theme.GAP)
        );

        int cardW = (
                avail - Theme.GAP * (columns - 1)
        ) / columns;

        int y = contentTop() + Theme.PAD - scroll;

        Map<String, List<Module>> groups =
                registry.grouped(category, query);

        for (Map.Entry<String, List<Module>> group : groups.entrySet()) {
            y += Theme.SECTION_H;

            int col = 0;

            for (Module m : group.getValue()) {
                int cx =
                        x0 + col * (cardW + Theme.GAP);

                int gearW =
                        m.hasSettings() ? 22 : 0;

                cards.add(
                        new Placed(
                                m,
                                cx,
                                y,
                                cardW,
                                Theme.CARD_H,
                                cx + cardW - 16 - gearW,
                                gearW
                        )
                );

                col++;

                if (col == columns) {
                    col = 0;
                    y += Theme.CARD_H + Theme.GAP;
                }
            }

            if (col != 0) {
                y += Theme.CARD_H + Theme.GAP;
            }

            y += 6;
        }

        contentHeight =
                (y + scroll)
                        - (contentTop() + Theme.PAD)
                        + Theme.PAD;
    }

    private int maxScroll() {
        return Math.max(
                0,
                contentHeight
                        - (contentBottom() - contentTop())
        );
    }

    private Anim anim(
            Map<String, Anim> map,
            String key,
            float initial
    ) {
        return map.computeIfAbsent(
                key,
                k -> new Anim(initial)
        );
    }

    // -- render ---------------------------------------------------------------

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor g,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        super.extractRenderState(
                g,
                mouseX,
                mouseY,
                partialTick
        );

        scroll = Math.max(
                0,
                Math.min(scroll, maxScroll())
        );

        layout();

        Font font = this.font;

        float in =
                entrance.to(
                        1f,
                        Theme.EASE_SLOW
                );

        g.fill(
                0,
                0,
                width,
                height,
                Draw.alpha(
                        Theme.scrim(),
                        0.70f * in
                )
        );

        int px = panelX();
        int py = panelY();
        int pw = panelW();
        int ph = panelH();

        Draw.roundedOutline(
                g,
                px - 1,
                py - 1,
                pw + 2,
                ph + 2,
                Theme.RADIUS_LG + 1,
                Theme.line(),
                Theme.bg()
        );

        drawSidebar(
                g,
                font,
                mouseX,
                mouseY
        );

        drawContent(
                g,
                font,
                mouseX,
                mouseY
        );

        drawHeader(
                g,
                font,
                mouseX,
                mouseY
        );
    }

    private void drawSidebar(
            GuiGraphicsExtractor g,
            Font font,
            int mouseX,
            int mouseY
    ) {
        int px = panelX();
        int py = panelY();

        Draw.roundedRect(
                g,
                px,
                py,
                Theme.SIDEBAR_W,
                panelH(),
                Theme.RADIUS_LG,
                Theme.surface(),
                true,
                false,
                true,
                false
        );

        Draw.rect(
                g,
                px + Theme.SIDEBAR_W - 1,
                py + Theme.RADIUS_LG,
                1,
                panelH() - Theme.RADIUS_LG * 2,
                Theme.line()
        );

        int by =
                py + Theme.HEADER_H / 2 - 4;

        Draw.roundedRect(
                g,
                px + 16,
                by + 1,
                5,
                5,
                2,
                Theme.accent()
        );

        Draw.text(
                g,
                font,
                brand,
                px + 27,
                by,
                Theme.text()
        );

        Draw.rect(
                g,
                px,
                py + Theme.HEADER_H - 1,
                Theme.SIDEBAR_W,
                1,
                Theme.line()
        );

        Draw.text(
                g,
                font,
                "MODULES",
                px + 16,
                py + Theme.HEADER_H + 8,
                Theme.dim()
        );

        for (Nav n : navItems) {
            boolean selected =
                    (n.category == null && category == null)
                            || (n.category != null
                            && n.category.equals(category));

            boolean hovered =
                    contains(
                            n.x,
                            n.y,
                            n.w,
                            n.h,
                            mouseX,
                            mouseY
                    );

            float h =
                    anim(
                            hoverAnims,
                            "nav:" + n.label,
                            0f
                    ).to(
                            selected
                                    ? 1f
                                    : (hovered ? 0.55f : 0f),
                            Theme.EASE_FAST
                    );

            if (h > 0.01f) {
                Draw.roundedRect(
                        g,
                        n.x,
                        n.y,
                        n.w,
                        n.h,
                        Theme.RADIUS,
                        Draw.alpha(
                                Theme.accent(),
                                0.16f * h
                        )
                );
            }

            if (selected) {
                Draw.roundedRect(
                        g,
                        n.x,
                        n.y + 7,
                        2,
                        n.h - 14,
                        1,
                        Theme.accent()
                );
            }

            int label =
                    Draw.lerp(
                            Theme.muted(),
                            Theme.text(),
                            h
                    );

            Draw.text(
                    g,
                    font,
                    n.label,
                    n.x + 14,
                    n.y + (Theme.NAV_H - 8) / 2,
                    selected
                            ? Theme.accent()
                            : label
            );
        }

        themeRow.draw(
                g,
                font,
                panelX(),
                mouseX,
                mouseY
        );
    }

    private void drawHeader(
            GuiGraphicsExtractor g,
            Font font,
            int mouseX,
            int mouseY
    ) {
        int px = panelX();
        int py = panelY();

        Draw.roundedRect(
                g,
                contentX(),
                py,
                contentW(),
                Theme.HEADER_H,
                Theme.RADIUS_LG,
                Theme.bg(),
                false,
                true,
                false,
                false
        );

        Draw.rect(
                g,
                contentX(),
                py + Theme.HEADER_H - 1,
                contentW(),
                1,
                Theme.line()
        );

        String title =
                category == null
                        ? "All Modules"
                        : category;

        Draw.text(
                g,
                font,
                title,
                contentX() + Theme.PAD,
                py + 20,
                Theme.text()
        );

        int n =
                registry.count(
                        category,
                        query
                );

        int titleW =
                font.width(title);

        Draw.text(
                g,
                font,
                n + (n == 1
                        ? " module"
                        : " modules"),
                contentX()
                        + Theme.PAD
                        + titleW
                        + 10,
                py + 20,
                Theme.dim()
        );

        drawSearch(
                g,
                font,
                mouseX,
                mouseY
        );
    }

    private void drawSearch(
            GuiGraphicsExtractor g,
            Font font,
            int mouseX,
            int mouseY
    ) {
        int w = 190;
        int h = 22;

        int x =
                panelX()
                        + panelW()
                        - Theme.PAD
                        - w;

        int y =
                panelY()
                        + (Theme.HEADER_H - h) / 2;

        boolean hovered =
                contains(
                        x,
                        y,
                        w,
                        h,
                        mouseX,
                        mouseY
                );

        float f =
                anim(
                        hoverAnims,
                        "search",
                        0f
                ).to(
                        !query.isEmpty()
                                ? 1f
                                : (hovered ? 0.5f : 0f),
                        Theme.EASE_FAST
                );

        Draw.roundedOutline(
                g,
                x,
                y,
                w,
                h,
                Theme.RADIUS,
                Draw.lerp(
                        Theme.line(),
                        Theme.accent(),
                        f * 0.8f
                ),
                Theme.input()
        );

        if (query.isEmpty()) {
            Draw.text(
                    g,
                    font,
                    "Search modules...",
                    x + 10,
                    y + 7,
                    Theme.dim()
            );
        } else {
            Draw.text(
                    g,
                    font,
                    Draw.fit(
                            font,
                            query,
                            w - 20
                    ),
                    x + 10,
                    y + 7,
                    Theme.text()
            );
        }
    }

    private void drawContent(
            GuiGraphicsExtractor g,
            Font font,
            int mouseX,
            int mouseY
    ) {
        int top = contentTop();
        int bottom = contentBottom();

        Draw.roundedRect(
                g,
                contentX(),
                top,
                contentW(),
                bottom - top,
                Theme.RADIUS_LG,
                Theme.bg(),
                false,
                false,
                false,
                true
        );

        if (cards.isEmpty()) {
            String msg =
                    query.isBlank()
                            ? "Nothing registered here yet."
                            : "No modules match that search.";

            Draw.textCentered(
                    g,
                    font,
                    msg,
                    contentX()
                            + contentW() / 2,
                    top + 60,
                    Theme.dim()
            );

            return;
        }

        String lastCategory = null;

        for (Placed p : cards) {
            if (!p.module.category().equals(lastCategory)) {
                lastCategory =
                        p.module.category();

                int hy =
                        p.y - Theme.SECTION_H + 8;

                if (hy >= top && hy + 8 < bottom) {
                    String label =
                            lastCategory;

                    Draw.text(
                            g,
                            font,
                            label,
                            p.x,
                            hy,
                            Theme.muted()
                    );

                    int lineX =
                            p.x
                                    + font.width(label)
                                    + 12;

                    Draw.rect(
                            g,
                            lineX,
                            hy + 3,
                            contentX()
                                    + contentW()
                                    - Theme.PAD
                                    - lineX,
                            1,
                            Theme.hair()
                    );
                }
            }

            if (p.y + p.h < top || p.y > bottom) {
                continue;
            }

            drawCard(
                    g,
                    font,
                    p,
                    mouseX,
                    mouseY,
                    top,
                    bottom
            );
        }

        Draw.roundedRect(
                g,
                contentX(),
                bottom,
                contentW(),
                panelY()
                        + panelH()
                        - bottom,
                Theme.RADIUS_LG,
                Theme.bg(),
                false,
                false,
                false,
                true
        );

        drawScrollbar(g);
    }

    /** The key chip sits at the card's bottom right, centred on the switch's row. */
    private static int chipX(Placed p) {
        return p.x + p.w - 16 - Keybinds.CHIP_W;
    }

    private static int chipY(Placed p) {
        return p.y + p.h - 17 - Keybinds.CHIP_H / 2;
    }

    private static boolean overChip(Placed p, int mx, int my) {
        return p.module.implemented()
                && contains(chipX(p), chipY(p), Keybinds.CHIP_W, Keybinds.CHIP_H, mx, my);
    }

    private void drawCard(
            GuiGraphicsExtractor g,
            Font font,
            Placed p,
            int mouseX,
            int mouseY,
            int clipTop,
            int clip
    ) {
        Module m = p.module();

        boolean on =
                m.isEnabled();

        boolean overGear =
                p.gearW > 0
                        && contains(
                        p.gearX - 5,
                        p.y + 7,
                        p.gearW + 10,
                        p.gearW + 10,
                        mouseX,
                        mouseY
                );

        boolean hovered =
                contains(
                        p.x,
                        p.y,
                        p.w,
                        p.h,
                        mouseX,
                        mouseY
                ) && !overGear;

        float h =
                anim(
                        hoverAnims,
                        m.id(),
                        0f
                ).to(
                        hovered ? 1f : 0f,
                        Theme.EASE_FAST
                );

        float t =
                anim(
                        toggleAnims,
                        m.id(),
                        on ? 1f : 0f
                ).to(
                        on ? 1f : 0f,
                        Theme.EASE_FAST
                );

        int cardTop =
                Math.max(
                        p.y,
                        clipTop
                );

        int cardBottom =
                Math.min(
                        p.y + p.h,
                        clip
                );

        int visible =
                cardBottom - cardTop;

        if (visible <= 0) {
            return;
        }

        boolean topWhole =
                cardTop == p.y;

        boolean botWhole =
                cardBottom == p.y + p.h;

        int body =
                Draw.lerp(
                        Theme.surface(),
                        Theme.hover(),
                        h
                );

        int border =
                Draw.lerp(
                        Theme.line(),
                        Draw.alpha(
                                Theme.accent(),
                                0.55f
                        ),
                        t * 0.8f
                );

        Draw.roundedRect(
                g,
                p.x,
                cardTop,
                p.w,
                visible,
                Theme.RADIUS,
                border,
                topWhole,
                topWhole,
                botWhole,
                botWhole
        );

        Draw.roundedRect(
                g,
                p.x + 1,
                cardTop + (topWhole ? 1 : 0),
                p.w - 2,
                visible
                        - (topWhole ? 1 : 0)
                        - (botWhole ? 1 : 0),
                Theme.RADIUS - 1,
                body,
                topWhole,
                topWhole,
                botWhole,
                botWhole
        );

        if (p.y + 15 >= clipTop
                && p.y + 23 < clip) {

            Draw.text(
                    g,
                    font,
                    Draw.fit(
                            font,
                            m.title(),
                            p.w - 40 - p.gearW
                    ),
                    p.x + 16,
                    p.y + 15,
                    Draw.lerp(
                            Theme.muted(),
                            Theme.text(),
                            Math.max(h, t)
                    )
            );
        }

        int dy =
                p.y + 31;

        for (String line :
                Draw.wrap(
                        font,
                        m.description(),
                        p.w - 32,
                        2
                )) {

            if (dy + 8 >= clip) {
                break;
            }

            if (dy >= clipTop) {
                Draw.text(
                        g,
                        font,
                        line,
                        p.x + 16,
                        dy,
                        Theme.dim()
                );
            }

            dy += 11;
        }

        if (p.y + p.h - 22 >= clipTop
                && p.y + p.h - 12 < clip) {

            /*
             * A module with nothing behind it gets a label instead of a switch.
             *
             * Drawing a toggle that does nothing is the worse option: it invites a
             * click, accepts it, and leaves you wondering which of the two of you is
             * broken. Saying so costs one line of text.
             */
            if (!m.implemented()) {
                Draw.text(
                        g,
                        font,
                        "Not implemented",
                        p.x + 16,
                        p.y + p.h - 22,
                        Theme.dim()
                );
            } else {
                drawToggle(
                        g,
                        p.x + 16,
                        p.y + p.h - 22,
                        t
                );

                /*
                 * The module's key, on the switch's row at the other end of the card.
                 *
                 * Here and not only on the settings page, because a module with no
                 * settings has no gear, so no settings page, so - until this - no way
                 * to give it a key. Several modules are exactly that shape.
                 */
                float bh =
                        anim(
                                hoverAnims,
                                m.id() + ":bind",
                                0f
                        ).to(
                                overChip(p, mouseX, mouseY) || Keybinds.moduleId(m).equals(binding)
                                        ? 1f : 0f,
                                Theme.EASE_FAST
                        );

                Keybinds.chip(
                        g,
                        font,
                        Keybinds.moduleId(m),
                        chipX(p),
                        chipY(p),
                        bh,
                        Keybinds.moduleId(m).equals(binding)
                );
            }
        }

        /*
         * ---------------------------------------------------------
         * SETTINGS GEAR
         * ---------------------------------------------------------
         *
         * No background box.
         * No colour inside the gear.
         * No second colour passed into the icon.
         *
         * Hover only changes muted -> text.
         */
        if (p.gearW > 0
                && p.y + 12 >= clipTop
                && p.y + 12 + p.gearW < clip) {

            float gh =
                    anim(
                            hoverAnims,
                            m.id() + ":gear",
                            0f
                    ).to(
                            overGear ? 1f : 0f,
                            Theme.EASE_FAST
                    );

            Draw.gearIcon(
                    g,
                    font,
                    p.gearX + p.gearW / 2,
                    p.y + 12 + p.gearW / 2,
                    Draw.lerp(
                            Theme.muted(),
                            Theme.text(),
                            gh
                    )
            );
        }
    }

    /** Track plus a knob that slides. */
    private void drawToggle(
            GuiGraphicsExtractor g,
            int x,
            int y,
            float t
    ) {
        int w = 20;
        int h = 10;

        Draw.roundedRect(
                g,
                x,
                y,
                w,
                h,
                h / 2,
                Draw.lerp(
                        Theme.line(),
                        Theme.pos(),
                        t
                )
        );

        int knobX =
                x + 1
                        + (int) (
                        (w - h) * t
                );

        Draw.roundedRect(
                g,
                knobX,
                y + 1,
                h - 2,
                h - 2,
                (h - 2) / 2,
                Draw.lerp(
                        Theme.dim(),
                        Theme.text(),
                        t
                )
        );
    }

    private void drawScrollbar(
            GuiGraphicsExtractor g
    ) {
        int max = maxScroll();

        if (max <= 0) {
            return;
        }

        int trackTop =
                contentTop() + 4;

        int trackH =
                contentBottom()
                        - contentTop()
                        - 8;

        int x =
                panelX()
                        + panelW()
                        - 5;

        int thumbH =
                Math.max(
                        24,
                        (int) (
                                trackH
                                        * (float) trackH
                                        / (trackH + max)
                        )
                );

        int thumbY =
                trackTop
                        + (int) (
                        (trackH - thumbH)
                                * (scroll / (float) max)
                );

        Draw.roundedRect(
                g,
                x,
                trackTop,
                3,
                trackH,
                1,
                Theme.hair()
        );

        Draw.roundedRect(
                g,
                x,
                thumbY,
                3,
                thumbH,
                1,
                Theme.line()
        );
    }

    // -- input ----------------------------------------------------------------

    @Override
    public boolean mouseClicked(
            MouseButtonEvent event,
            boolean doubleClick
    ) {
        int mx = (int) event.x();
        int my = (int) event.y();

        layout();

        if (themeRow.click(mx, my)) {
            return true;
        }

        for (Nav n : navItems) {
            if (contains(
                    n.x,
                    n.y,
                    n.w,
                    n.h,
                    mx,
                    my
            )) {
                category = n.category;
                scroll = 0;
                return true;
            }
        }

        for (Placed p : cards) {
            if (p.y + p.h < contentTop()
                    || p.y > contentBottom()) {
                continue;
            }

            if (p.gearW > 0
                    && contains(
                    p.gearX - 5,
                    p.y + 7,
                    p.gearW + 10,
                    p.gearW + 10,
                    mx,
                    my
            )) {
                minecraft.setScreen(
                        new SettingsScreen(
                                this,
                                brand,
                                p.module
                        )
                );

                return true;
            }

            if (overChip(p, mx, my)) {
                // Left arms the chip for the next key; right clears it. Same as the
                // settings page, so a chip means one thing wherever it is.
                String id = Keybinds.moduleId(p.module);
                if (event.button() == 1) {
                    Keybinds.clear(id);
                    binding = null;
                } else {
                    binding = id.equals(binding) ? null : id;
                }
                return true;
            }

            if (contains(
                    p.x,
                    p.y,
                    p.w,
                    p.h,
                    mx,
                    my
            )) {
                // Swallowed rather than passed on: the card is still a card, it just has
                // nothing to switch.
                if (p.module.implemented()) {
                    p.module.toggle();
                }
                return true;
            }
        }

        return super.mouseClicked(
                event,
                doubleClick
        );
    }

    @Override
    public boolean charTyped(
            CharacterEvent event
    ) {
        // A key being bound must not also land in the search box.
        if (binding != null) {
            return true;
        }

        if (event.isAllowedChatCharacter()) {
            query +=
                    event.codepointAsString();

            scroll = 0;

            return true;
        }

        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(
            KeyEvent event
    ) {
        if (binding != null) {
            // Escape cancels; anything else binds, modifiers included.
            if (event.key() != 256) {
                Keybinds.set(binding, event.key());
            }
            binding = null;
            return true;
        }

        if (event.key() == 259
                && !query.isEmpty()) {

            query =
                    query.substring(
                            0,
                            query.length() - 1
                    );

            scroll = 0;

            return true;
        }

        if (event.key() == 256
                && !query.isEmpty()) {

            query = "";
            scroll = 0;

            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double scrollX,
            double scrollY
    ) {
        scrollBy(
                (int) (-scrollY * 26)
        );

        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public void scrollBy(int amount) {
        scroll =
                Math.max(
                        0,
                        Math.min(
                                scroll + amount,
                                maxScroll()
                        )
                );
    }

    private static boolean contains(
            int x,
            int y,
            int w,
            int h,
            int px,
            int py
    ) {
        return px >= x
                && px < x + w
                && py >= y
                && py < y + h;
    }
}