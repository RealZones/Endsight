package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.StainedGlassPaneBlock;

import java.util.List;
import java.util.Optional;

/**
 * A Heart of the Dragon button in the server's main menu.
 *
 * Drawn as one more of the menu's own items, in the empty slot under Skills, with the
 * same tooltip shape the server uses - so it reads as part of the menu, not a mod box
 * over it. The click sends /hotd and is swallowed before it can reach the slot.
 */
public final class MenuButton {

    private MenuButton() {
    }

    private static final String MENU = "Dragon Sim Menu";
    /** Under the Skills sword (slot 19) in the 6-row menu. */
    private static final int SLOT = 28;
    // Built on first draw: an ItemStack made while the mod initialises crashes with
    // "Components not bound yet" - item components arrive after the client entrypoint.
    private static ItemStack icon;
    private static final List<Component> TIP = List.of(
            Component.literal("Heart of the Dragon").withStyle(ChatFormatting.LIGHT_PURPLE),
            Component.literal("Spend powder on perks.").withStyle(ChatFormatting.GRAY),
            Component.empty(),
            Component.literal("Click to open!").withStyle(ChatFormatting.YELLOW));

    private static boolean enabled = true;

    public static Module module() {
        return new Module("qol.hotdButton", "HOTD Button",
                "A Heart of the Dragon item in the Dragon Sim Menu that opens /hotd.", "Quality of Life",
                () -> enabled, v -> enabled = v, List.of());
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> draw(container, g, mx, my));
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> !click(container, click.x(), click.y(), click.button()));
        });
        // The menu pads its empty slots with blank glass panes. Hovering ours would show
        // the pane's blank tooltip, so the pane's lines are replaced with the button's -
        // vanilla then draws them, and they look like every other item's.
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (!(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen)) return;
            Slot slot = slot(screen);
            if (slot == null || stack != slot.getItem()) return;
            lines.clear();
            lines.addAll(TIP);
        });
    }

    /** The slot the button lives in, or null when this is not the menu or something real sits there. */
    private static Slot slot(AbstractContainerScreen<?> screen) {
        if (!enabled || !Zealots.strip(screen.getTitle().getString()).trim().equals(MENU)) return null;
        List<Slot> slots = screen.getMenu().slots;
        if (slots.size() <= SLOT) return null;
        Slot slot = slots.get(SLOT);
        return free(slot.getItem()) ? slot : null;
    }

    /** Empty, or the blank glass pane the menu pads its empty slots with. */
    private static boolean free(ItemStack s) {
        return s.isEmpty() || (s.getItem() instanceof BlockItem b && b.getBlock() instanceof StainedGlassPaneBlock);
    }

    private static boolean over(AbstractContainerScreen<?> screen, Slot slot, double mx, double my) {
        int x = screen.leftPos + slot.x, y = screen.topPos + slot.y;
        return mx >= x && mx < x + 16 && my >= y && my < y + 16;
    }

    private static void draw(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mx, int my) {
        Slot slot = slot(screen);
        if (slot == null) return;
        int x = screen.leftPos + slot.x, y = screen.topPos + slot.y;
        if (icon == null) icon = new ItemStack(Items.DRAGON_EGG);
        // A new stratum, or the egg lands under the pane vanilla already drew there.
        g.nextStratum();
        g.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);   // the slot's own grey, so the pane is gone
        g.fakeItem(icon, x, y);
        if (over(screen, slot, mx, my)) {
            g.fill(x, y, x + 16, y + 16, 0x80FFFFFF);
            // A truly empty slot gets no tooltip from vanilla; the pane case is handled
            // through the tooltip callback so there is never a second box.
            if (slot.getItem().isEmpty()) g.setTooltipForNextFrame(Minecraft.getInstance().font, TIP, Optional.empty(), mx, my);
        }
    }

    private static boolean click(AbstractContainerScreen<?> screen, double mx, double my, int button) {
        Slot slot = slot(screen);
        if (slot == null || button != 0 || !over(screen, slot, mx, my)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendCommand("hotd");
        return true;
    }
}
