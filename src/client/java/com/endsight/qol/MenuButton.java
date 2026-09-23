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
 * Buttons in the server's main menu for the screens it has no item for: the Heart of
 * the Dragon, and the Accessory Bag.
 *
 * Drawn as more of the menu's own items, in empty slots, with the same tooltip shape the
 * server uses - so they read as part of the menu, not mod boxes over it. A click sends
 * the command and is swallowed before it can reach the slot.
 */
public final class MenuButton {

    private MenuButton() {
    }

    private static final String MENU = "Dragon Sim Menu";

    /** One button: its slot in the 6-row menu, the item to show, its tooltip and the command it sends. */
    private record Button(int slot, java.util.function.Supplier<ItemStack> icon, List<Component> tip, String command) {
    }

    // Built on first draw: an ItemStack made while the mod initialises crashes with
    // "Components not bound yet" - item components arrive after the client entrypoint.
    private static final java.util.Map<Integer, ItemStack> icons = new java.util.HashMap<>();

    private static final List<Button> BUTTONS = List.of(
            // Under the Skills sword (slot 19).
            new Button(28, () -> new ItemStack(Items.DRAGON_EGG), List.of(
                    Component.literal("Heart of the Dragon").withStyle(ChatFormatting.LIGHT_PURPLE),
                    Component.literal("Spend powder on perks.").withStyle(ChatFormatting.GRAY),
                    Component.empty(),
                    Component.literal("Click to open!").withStyle(ChatFormatting.YELLOW)), "hotd"),
            // Above Storage (slot 25).
            new Button(16, () -> new ItemStack(Items.BUNDLE), List.of(
                    Component.literal("Accessory Bag").withStyle(ChatFormatting.GOLD),
                    Component.literal("Your talismans, rings and artifacts.").withStyle(ChatFormatting.GRAY),
                    Component.empty(),
                    Component.literal("Click to open!").withStyle(ChatFormatting.YELLOW)), "accessories"));

    private static boolean enabled = true;

    public static Module module() {
        return new Module("qol.hotdButton", "Menu Buttons",
                "Heart of the Dragon and Accessory Bag items in the Dragon Sim Menu.", "Quality of Life",
                () -> enabled, v -> enabled = v, List.of());
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> draw(container, g, mx, my));
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> !click(container, click.x(), click.y(), click.button()));
        });
        // The menu pads its empty slots with blank glass panes. Hovering one of ours would
        // show the pane's blank tooltip, so the pane's lines are replaced with the
        // button's - vanilla then draws them, and they look like every other item's.
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (!(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen)) return;
            for (Button b : BUTTONS) {
                Slot slot = slot(screen, b);
                if (slot == null || stack != slot.getItem()) continue;
                lines.clear();
                lines.addAll(b.tip());
                return;
            }
        });
    }

    /** The slot a button lives in, or null when this is not the menu or something real sits there. */
    private static Slot slot(AbstractContainerScreen<?> screen, Button b) {
        if (!enabled || !Zealots.strip(screen.getTitle().getString()).trim().equals(MENU)) return null;
        List<Slot> slots = screen.getMenu().slots;
        if (slots.size() <= b.slot()) return null;
        Slot slot = slots.get(b.slot());
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
        for (Button b : BUTTONS) {
            Slot slot = slot(screen, b);
            if (slot == null) continue;
            int x = screen.leftPos + slot.x, y = screen.topPos + slot.y;
            ItemStack icon = icons.computeIfAbsent(b.slot(), k -> b.icon().get());
            // A new stratum, or the item lands under the pane vanilla already drew there.
            g.nextStratum();
            g.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);   // the slot's own grey, so the pane is gone
            g.fakeItem(icon, x, y);
            if (over(screen, slot, mx, my)) {
                g.fill(x, y, x + 16, y + 16, 0x80FFFFFF);
                // A truly empty slot gets no tooltip from vanilla; the pane case is handled
                // through the tooltip callback so there is never a second box.
                if (slot.getItem().isEmpty()) g.setTooltipForNextFrame(Minecraft.getInstance().font, b.tip(), Optional.empty(), mx, my);
            }
        }
    }

    private static boolean click(AbstractContainerScreen<?> screen, double mx, double my, int button) {
        if (button != 0) return false;
        for (Button b : BUTTONS) {
            Slot slot = slot(screen, b);
            if (slot == null || !over(screen, slot, mx, my)) continue;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.connection.sendCommand(b.command());
            return true;
        }
        return false;
    }
}
