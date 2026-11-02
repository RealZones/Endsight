package com.endsight.ui;

import com.endsight.EndsightClient;
import com.endsight.qol.CommandBinds;
import com.endsight.zealots.ZealotTracker;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Actual browser input and OS clipboard, in an isolated client configuration. */
public final class CommandBindsVisualTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(960, 640);
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(mc -> {
                mc.options.guiScale().set(2);
                mc.options.renderDistance().set(2);
                mc.resizeGui();
                mc.setScreen(new EndsightScreen("Endsight", EndsightClient.registry()));
            });
            context.getInput().typeChars("bind");
            context.waitTicks(2);
            context.runOnClient(mc -> {
                EndsightScreen screen = (EndsightScreen) mc.screen;
                SettingsScreen editor = (SettingsScreen) field(screen, "editor");
                Object row = rows(editor).stream().filter(candidate -> invoke(candidate, "setting") instanceof Setting.Action action
                        && action.label().equals("New bind")).findFirst().orElseThrow();
                int before = CommandBinds.all().size();
                click(screen, row, false);
                require(field(screen, "query").equals("bind"), "New cleared the active search");
                require(CommandBinds.all().size() == before + 1, "New did not create one command");
                Setting.Command command = CommandBinds.all().getLast();
                require(field(editor, "editing") == command, "new command did not receive keyboard focus");
                String originalClipboard = mc.keyboardHandler.getClipboard();
                try {
                    mc.keyboardHandler.setClipboard("§a/warp end\r\n");
                    key(screen, 86, 2);
                    require(command.command().equals("/warp end"), "chat clipboard did not paste as a clean command");
                    key(screen, 65, 2);
                    mc.keyboardHandler.setClipboard("/warp hub");
                    key(screen, 86, 2);
                    require(command.command().equals("/warp hub"), "paste did not replace selected text");
                    key(screen, 65, 2); key(screen, 67, 2);
                    require(mc.keyboardHandler.getClipboard().equals("/warp hub"), "copy lost command text");
                    key(screen, 88, 2);
                    require(command.command().isEmpty(), "cut left selected text behind");
                    key(screen, 86, 2);
                    key(screen, 269, 0);
                    screen.charTyped(new CharacterEvent(0x1F63A));
                    key(screen, 259, 0);
                    require(command.command().equals("/warp hub"), "backspace broke a Unicode codepoint");
                    require(field(screen, "query").equals("bind"), "command editing changed the browser search");
                    invoke(screen, "layout");
                    Object commandRow = rows(editor).stream().filter(candidate -> invoke(candidate, "setting") == command)
                            .findFirst().orElseThrow();
                    click(screen, commandRow, true);
                    key(screen, 296, 0);
                    require(Keybinds.get(command.id()) == 296, "new row's key chip did not bind F7");
                    require(command.command().equals("/warp hub"), "binding a key changed the command");
                    Keybinds.clear(command.id());
                } finally { mc.keyboardHandler.setClipboard(originalClipboard); }
                System.out.println("COMMAND BINDS CLIENT CHECKS PASSED: search retained, new row focused, clipboard paste/select/copy/cut, Unicode backspace and key binding");
            });
            context.waitTicks(2);
            context.takeScreenshot("command-binds-search-and-paste");
            context.runOnClient(mc -> {
                CommandBinds.all().getLast().remove().run();
                mc.setScreen(null);
                ItemStack original = mc.player.getMainHandItem().copy();
                try {
                    for (String name : List.of("Glacial Scythe", "Axe of the Shredded", "Stick")) {
                        ItemStack weapon = new ItemStack(Items.STICK);
                        weapon.set(DataComponents.CUSTOM_NAME, Component.literal(name));
                        mc.player.setItemInHand(InteractionHand.MAIN_HAND, weapon);
                        set(ZealotTracker.class, "sinceSpecial", 42);
                        mc.gui.setTitle(Component.literal("§5Special Zealot"));
                        require(field(ZealotTracker.class, "sinceSpecial").equals(0), "actual title hook failed with " + name);
                    }
                } finally { mc.player.setItemInHand(InteractionHand.MAIN_HAND, original); }
                set(ZealotTracker.class, "sinceSpecial", 3);
                mc.gui.setSubtitle(Component.literal("§aA Special Zealot has spawned nearby!"));
                require(field(ZealotTracker.class, "sinceSpecial").equals(3), "subtitle reset a companion spawn twice");
                mc.getChatListener().handleSystemMessage(Component.literal("[Debug] Zealot · Roll 0.16942/100 · Special Zealot hit"), false);
                require(field(ZealotTracker.class, "sinceSpecial").equals(3), "hidden debug companion reset the same spawn twice");
                mc.gui.clearTitles();
                System.out.println("SPECIAL ZEALOT CLIENT CHECKS PASSED: actual title hook resets without debug; subtitle and hidden debug companions preserve later kills");
            });
        }
    }

    private static void click(EndsightScreen screen, Object row, boolean chip) {
        int x = (int) invoke(row, "x"), y = (int) invoke(row, "y");
        int width = (int) invoke(row, "w"), height = (int) invoke(row, "h");
        screen.mouseClicked(new MouseButtonEvent(x + width - (chip ? 52 : 20), y + height / 2.0,
                new MouseButtonInfo(0, 0)), false);
    }

    private static void key(EndsightScreen screen, int key, int modifiers) {
        screen.keyPressed(new KeyEvent(key, 0, modifiers));
    }

    @SuppressWarnings("unchecked") private static List<Object> rows(SettingsScreen screen) {
        return (List<Object>) field(screen, "rows");
    }

    private static Object field(Object target, String name) {
        try {
            Field field = (target instanceof Class<?> type ? type : target.getClass()).getDeclaredField(name);
            field.setAccessible(true); return field.get(target instanceof Class<?> ? null : target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }

    private static void set(Class<?> type, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }

    private static Object invoke(Object target, String name) {
        try {
            Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
