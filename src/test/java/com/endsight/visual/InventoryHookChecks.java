package com.endsight.visual;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;

/** Checks the actual 26.1.2 bytecode, not signatures copied from an older modding example. */
public final class InventoryHookChecks {
    private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String BLIT = "(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V";
    private static final String LABEL = "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V";
    private static int checks;

    public static void main(String[] args) throws IOException {
        calls("net/minecraft/client/gui/screens/inventory/InventoryScreen", "extractBackground", "blit", BLIT, 1);
        calls("net/minecraft/client/gui/screens/inventory/ContainerScreen", "extractBackground", "blit", BLIT, 2);
        calls("net/minecraft/client/gui/screens/inventory/InventoryScreen", "extractLabels", "text", LABEL, 1);
        calls("net/minecraft/client/gui/screens/inventory/AbstractContainerScreen", "extractLabels", "text", LABEL, 2);
        calls("net/minecraft/client/gui/components/PlayerTabOverlay", "extractRenderState", "text",
                "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V", 1);
        for (String inner : new String[]{"DrawingFocusedGraphicsAccess", "DrawingBackgroundGraphicsAccess"}) {
            MethodModel message = method("net/minecraft/client/gui/components/ChatComponent$" + inner, "handleMessage");
            check(message.methodType().stringValue().equals("(IFLnet/minecraft/util/FormattedCharSequence;)Z"), "Chat argument hook");
        }
        check(method("net/minecraft/client/renderer/entity/EntityRenderer", "extractRenderState").methodType().stringValue()
                .equals("(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V"), "World nametag hook");
        String textRenderer = "net/minecraft/client/renderer/entity/DisplayRenderer$TextDisplayRenderer";
        try (var in = InventoryHookChecks.class.getClassLoader().getResourceAsStream(textRenderer + ".class")) {
            if (in == null) throw new AssertionError("Missing target " + textRenderer);
            check(ClassFile.of().parse(in.readAllBytes()).methods().stream().anyMatch(m ->
                    m.methodName().stringValue().equals("extractRenderState") && m.methodType().stringValue().equals(
                            "(Lnet/minecraft/world/entity/Display$TextDisplay;"
                                    + "Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;F)V")),
                    "Summon TextDisplay hook");
        }
        String container = "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen";
        check(method(container, "slotClicked").methodType().stringValue()
                .equals("(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V"), "Slot Lock click hook");
        check(method(container, "getHoveredSlot").methodType().stringValue()
                .equals("(DD)Lnet/minecraft/world/inventory/Slot;"), "Slot Lock coordinate hook");
        check(method(container, "mouseDragged").methodType().stringValue()
                .equals("(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z"), "Slot Lock drag hook");
        check(method("net/minecraft/client/player/LocalPlayer", "drop").methodType().stringValue()
                .equals("(Z)Z"), "World drop hook");
        long swapSends = method("net/minecraft/client/Minecraft", "handleKeybinds").code().orElseThrow()
                .elementList().stream().filter(element -> element instanceof InvokeInstruction invoke
                        && invoke.owner().asInternalName().equals("net/minecraft/client/multiplayer/ClientPacketListener")
                        && invoke.name().stringValue().equals("send")
                        && invoke.type().stringValue().equals("(Lnet/minecraft/network/protocol/Packet;)V")).count();
        check(swapSends == 1, "Offhand swap packet hook is unique");
        System.out.println(checks + " Minecraft render hook checks passed");
    }

    private static MethodModel method(String type, String name) throws IOException {
        try (var in = InventoryHookChecks.class.getClassLoader().getResourceAsStream(type + ".class")) {
            if (in == null) throw new AssertionError("Missing target " + type);
            var methods = ClassFile.of().parse(in.readAllBytes()).methods().stream()
                    .filter(method -> method.methodName().stringValue().equals(name)).toList();
            if (methods.size() != 1) throw new AssertionError("Ambiguous target " + type + "::" + name);
            return methods.getFirst();
        }
    }

    private static void calls(String type, String method, String name, String descriptor, int expected) throws IOException {
        long count = method(type, method).code().orElseThrow().elementList().stream()
                .filter(element -> element instanceof InvokeInstruction invoke && invoke.owner().asInternalName().equals(GUI)
                        && invoke.name().stringValue().equals(name) && invoke.type().stringValue().equals(descriptor)).count();
        check(count == expected, type + "::" + method + " found " + count + " injection points, expected " + expected);
    }

    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }
}
