package com.endsight.storage;

import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Drag the storage preview to where you want it, with a ghost of the game's own window
 * underneath so you can see what you are lining it up against.
 *
 * This exists because the obvious version was miserable. Placing the panel used to be a
 * toggle on the settings page: switch it on, close the menu, open Storage, drag, reopen
 * the menu, switch it off, open Storage again to check. Six steps to nudge a box, and
 * five of them were navigation. Here the button opens this, you drag, you press escape,
 * and you are back on the settings page you left - the panel you dragged is the real one,
 * drawn by the same code that draws it in game, so there is nothing to verify afterwards.
 *
 * The ghost is the last real storage window seen, remembered by {@link StoragePreview}.
 * Falling back to a guess when there is none is deliberate: a first-run guess that is
 * roughly right beats refusing to open, and the moment you open Storage once it becomes
 * exact.
 */
final class PreviewPlacementScreen extends Screen {

    private final Screen parent;

    private boolean dragging;
    private int fromX, fromY, baseX, baseY;

    PreviewPlacementScreen(Screen parent) {
        super(Component.literal("Place preview"));
        this.parent = parent;
    }

    // A plain double chest, for the case where Storage has not been opened this session.
    private static final int GUESS_W = 176;
    private static final int GUESS_ROWS = 6;

    private int gridLeft() {
        if (StoragePreview.frameGridLeft >= 0) return StoragePreview.frameGridLeft;
        return (width - GUESS_W) / 2 + 8;
    }

    private int windowTop() {
        if (StoragePreview.frameWindowTop >= 0) return StoragePreview.frameWindowTop;
        return (height - (114 + GUESS_ROWS * StoragePreview.CELL)) / 2;
    }

    private int rows() {
        return StoragePreview.frameGridLeft >= 0 ? StoragePreview.frameRows : GUESS_ROWS;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, Theme.scrim());

        drawGhost(g);

        PageSnapshot snap = StoragePreview.lastSnapshot();
        int[] b = StoragePreview.boundsAt(gridLeft(), windowTop(),
                StoragePreview.drawnRows(snap), width, height);
        StoragePreview.drawPanel(g, snap, b, System.currentTimeMillis(), true);

        String hint = "Drag to place  ·  Right-click to recentre  ·  Esc when done";
        Draw.textCentered(g, font, hint, width / 2, height - 28, Theme.muted());
    }

    /**
     * The game's window, as an outline of its slots.
     *
     * Slots rather than the window texture, because the slot grid is what the panel is
     * aligned to and drawing the thing you are actually aligning against is the whole
     * point of showing it. Dimmed so it reads as a reference and not as something you
     * could click.
     */
    private void drawGhost(GuiGraphicsExtractor g) {
        int[] cells = StoragePreview.frameCells;
        if (cells != null && cells.length >= 2) {
            for (int i = 0; i < cells.length; i += 2) {
                Draw.roundedRect(g, cells[i], cells[i + 1],
                        StoragePreview.CELL - 2, StoragePreview.CELL - 2, 2,
                        Draw.alpha(Theme.line(), 0.55f));
            }
            return;
        }
        int gx = gridLeft(), gy = windowTop() + 18;
        for (int r = 0; r < GUESS_ROWS; r++) {
            for (int c = 0; c < PageSnapshot.COLS; c++) {
                Draw.roundedRect(g, gx + c * StoragePreview.CELL, gy + r * StoragePreview.CELL,
                        StoragePreview.CELL - 2, StoragePreview.CELL - 2, 2,
                        Draw.alpha(Theme.line(), 0.55f));
            }
        }
    }

    // ── input ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();
        PageSnapshot snap = StoragePreview.lastSnapshot();
        int[] b = StoragePreview.boundsAt(gridLeft(), windowTop(),
                StoragePreview.drawnRows(snap), width, height);

        boolean onPanel = mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
        if (!onPanel) return super.mouseClicked(event, doubleClick);

        if (event.button() == 1) {
            StoragePreview.offsetX = 0;
            StoragePreview.offsetY = 0;
            return true;
        }
        dragging = true;
        fromX = mx;
        fromY = my;
        baseX = StoragePreview.offsetX;
        baseY = StoragePreview.offsetY;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (!dragging) return super.mouseDragged(event, dx, dy);
        StoragePreview.offsetX = baseX + ((int) event.x() - fromX);
        StoragePreview.offsetY = baseY + ((int) event.y() - fromY);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {                 // escape goes back, not out
            minecraft.setScreen(parent);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
