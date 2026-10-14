package com.endsight.qol;

import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** A pinned Minecraft hover, with the chat still visible and each row copyable. */
public final class LootRollDetailsScreen extends ChatScreen {
    private static final int FOOTER_H = 18;
    private static final String HINT = "Click a line to copy · Esc to unpin";

    private final Screen parent;
    private final List<Component> source;
    private final int chatScroll;
    private final double anchorX, anchorY;
    private final List<Row> rows = new ArrayList<>();
    private int scroll, contentHeight, boxX, boxY, boxW, boxH;
    private int positionedWidth = -1, positionedHeight = -1;
    private int copied = -1;
    private long copiedUntil;

    private record Row(int index, int y, int height, List<FormattedCharSequence> lines) { }

    public LootRollDetailsScreen(Screen parent, List<Component> rows) {
        // ChatComponent considers only ChatScreen focused. Inheriting it prevents
        // the HUD from also rendering faded chat underneath the retained chat view.
        super("", false);
        this.parent = parent;
        this.source = rows.stream().map(Component::copy).map(c -> (Component) c).toList();
        Minecraft mc = Minecraft.getInstance();
        this.chatScroll = parent instanceof ChatScreen ? mc.gui.getChat().chatScrollbarPos : 0;
        // Store the click position, not the moving pointer: hovering rows must never
        // chase the cursor. Relative coordinates also keep the pin usable on resize.
        this.anchorX = mc.mouseHandler.getScaledXPos(mc.getWindow()) / Math.max(1, mc.getWindow().getGuiScaledWidth());
        this.anchorY = mc.mouseHandler.getScaledYPos(mc.getWindow()) / Math.max(1, mc.getWindow().getGuiScaledHeight());
    }

    @Override
    protected void init() {
        super.init();
        if (parent != null && (parent.width != width || parent.height != height)) parent.resize(width, height);
        if (parent instanceof ChatScreen) restoreChatScroll();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) onClose();
        // The retained draft stays read-only while choosing a copy target. In
        // particular, Enter must never send an invisible inherited input field.
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) { return true; }

    @Override
    public void removed() {
        // The parent owns the draft; vanilla removal of this pin's empty input
        // would discard it before returning to the original ChatScreen.
    }

    private int lineH() { return font.lineHeight + 1; }
    private int viewportH() { return Math.max(1, boxH - FOOTER_H); }
    private int maxScroll() { return Math.max(0, contentHeight - viewportH()); }

    private void layout() {
        rows.clear();
        // Vanilla's ShowText hover wraps at half the screen (at least 200 pixels).
        // Use the same text density, with a screen-edge cap for very small windows.
        int textW = Math.max(1, Math.min(width - 24, Math.max(width / 2, 200)));
        boxW = Math.min(textW, font.width(HINT));
        int y = 0;
        for (int i = 0; i < source.size(); i++) {
            List<FormattedCharSequence> lines = font.split(source.get(i), textW);
            int rowH = Math.max(1, lines.size()) * lineH();
            rows.add(new Row(i, y, rowH, lines));
            for (FormattedCharSequence line : lines) boxW = Math.max(boxW, font.width(line));
            y += rowH + (i == 0 ? 2 : 0);
        }
        contentHeight = y;
        boxH = Math.max(1, Math.min(height - 16, contentHeight + FOOTER_H));
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        if (positionedWidth != width || positionedHeight != height) {
            var position = DefaultTooltipPositioner.INSTANCE.positionTooltip(width, height,
                    (int) Math.round(anchorX * width), (int) Math.round(anchorY * height), boxW, boxH);
            boxX = Math.max(6, Math.min(Math.max(6, width - boxW - 6), position.x()));
            boxY = Math.max(6, Math.min(Math.max(6, height - boxH - 6), position.y()));
            positionedWidth = width;
            positionedHeight = height;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // A pin should feel like the existing hover, without a menu scrim or blur.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (parent != null) parent.extractRenderState(g, -1_000, -1_000, partialTick);
        layout();
        g.nextStratum();
        // Native tooltip chrome follows Minecraft's resource pack instead of turning
        // the compact hover into a second Endsight settings panel.
        TooltipRenderUtil.extractTooltipBackground(g, boxX, boxY, boxW, boxH, null);
        int top = boxY, bottom = top + viewportH();
        boolean feedback = System.currentTimeMillis() < copiedUntil;
        for (Row row : rows) {
            int y = top + row.y() - scroll;
            int visibleTop = Math.max(top, y), visibleBottom = Math.min(bottom, y + row.height());
            if (visibleBottom <= visibleTop) continue;
            boolean hot = inside(mouseX, mouseY, boxX - 1, visibleTop, boxW + 2, visibleBottom - visibleTop);
            boolean justCopied = feedback && copied == row.index();
            if (hot || justCopied) {
                int accent = justCopied ? Theme.pos() : Theme.accent();
                Draw.rect(g, boxX - 1, visibleTop, boxW + 2, visibleBottom - visibleTop, Draw.alpha(accent, 0.20f));
                Draw.rect(g, boxX - 2, visibleTop, 1, visibleBottom - visibleTop, accent);
            }
            int textY = y;
            for (FormattedCharSequence line : row.lines()) {
                if (textY >= top && textY + font.lineHeight <= bottom) g.text(font, line, boxX, textY, -1, true);
                textY += lineH();
            }
        }
        if (maxScroll() > 0) {
            int viewport = viewportH();
            int thumb = Math.min(viewport, Math.max(8, viewport * viewport / Math.max(1, contentHeight)));
            int y = top + Math.round((viewport - thumb) * scroll / (float) maxScroll());
            Draw.rect(g, boxX + boxW + 1, top, 1, viewport, Draw.alpha(Theme.muted(), 0.3f));
            Draw.rect(g, boxX + boxW + 1, y, 1, thumb, Theme.accent());
        }
        String hint = feedback ? "Copied · Click another line or Esc" : HINT;
        Draw.text(g, font, Draw.fit(font, hint, boxW), boxX, bottom + 7, feedback ? Theme.pos() : Theme.muted());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return true;
        layout();
        double mx = event.x(), my = event.y();
        if (!inside(mx, my, boxX - 4, boxY - 4, boxW + 8, boxH + 8)) {
            onClose();
            return true;
        }
        for (Row row : rows) {
            int y = boxY + row.y() - scroll;
            int top = Math.max(boxY, y), bottom = Math.min(boxY + viewportH(), y + row.height());
            if (!inside(mx, my, boxX - 1, top, boxW + 2, bottom - top)) continue;
            minecraft.keyboardHandler.setClipboard(copyLine(source.get(row.index())));
            copied = row.index();
            copiedUntil = System.currentTimeMillis() + 2_000;
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        layout();
        if (!inside(mouseX, mouseY, boxX - 4, boxY - 4, boxW + 8, boxH + 8)) return false;
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(scrollY * lineH() * 3)));
        return true;
    }

    static String copyLine(Component line) {
        // Every wrapped segment belongs to one source row; copy all its exact digits.
        return Zealots.strip(line.getString()).trim();
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return w > 0 && h > 0 && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void restoreChatScroll() {
        ChatComponent chat = minecraft.gui.getChat();
        chat.resetChatScroll();
        chat.scrollChat(chatScroll);
    }

    @Override
    public void onClose() {
        Screen destination = parent;
        if (destination instanceof ChatScreen && minecraft.player == null) destination = null;
        // Reuse the interrupted ChatScreen so its draft survives the pinned hover.
        minecraft.setScreen(destination);
        if (destination instanceof ChatScreen) restoreChatScroll();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
