package com.endsight.qol;

import com.endsight.hud.Toast;
import com.endsight.zealots.Zealots;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.StringDecomposer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recent chat rolls retain their exact details, independently of subsequent kills. */
final class LootRollDetails {
    private LootRollDetails() { }

    static final int MAX_ROLLS = 256;
    private static final Pattern BASE_CHANCE = Pattern.compile("^(\\s*(?:▶\\s*)?)base\\s+[\\d.]+%\\s*→\\s*effective\\s+(?=[\\d.]+%\\s*[\\[(][\\d.]+,\\s*[\\d.]+[\\])]\\s*\\S)");
    private static final Map<Long, List<Component>> rolls = new LinkedHashMap<>();
    private static long nextId;

    static void init() {
        // Fabric handles this client command locally, including vanilla chat clicks.
        // A numeric identity keeps equal rounded summaries attached to their own exact roll.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                ClientCommands.literal("endsightroll").then(ClientCommands.argument("id", LongArgumentType.longArg(1))
                        .executes(command -> open(LongArgumentType.getLong(command, "id"))))));
    }

    static ClickEvent.RunCommand action(List<Component> raw) {
        long id = ++nextId;
        rolls.put(id, lines(raw));
        while (rolls.size() > MAX_ROLLS) rolls.remove(rolls.keySet().iterator().next());
        return new ClickEvent.RunCommand("/endsightroll " + id);
    }

    static List<Component> rows(long id) {
        return rolls.get(id);
    }

    static List<Component> lines(List<Component> raw) {
        List<Component> rows = new ArrayList<>();
        for (Component component : raw) {
            String text = component.getString();
            if (!text.contains("\n") && !text.contains("\r")) {
                if (contentLine(text)) rows.add(displayLine(component));
                continue;
            }
            for (String line : text.split("\\R")) {
                if (contentLine(line)) rows.add(displayLine(Component.literal(line).withStyle(component.getStyle())));
            }
        }
        return List.copyOf(rows);
    }

    static Component displayLine(Component line) {
        Matcher prefix = BASE_CHANCE.matcher(StringDecomposer.getPlainText(line));
        if (!prefix.find()) return line.copy();
        // Only the adjusted chance describes this roll. Remove the base comparison,
        // retaining exact digits and styled items even across component boundaries.
        int from = prefix.end(1), to = prefix.end();
        MutableComponent result = Component.empty();
        StringBuilder run = new StringBuilder();
        Style[] runStyle = {Style.EMPTY};
        int[] offset = {0};
        StringDecomposer.iterateFormatted(line, Style.EMPTY, (index, style, codePoint) -> {
            int position = offset[0];
            offset[0] += Character.charCount(codePoint);
            if (position >= from && position < to) return true;
            if (!run.isEmpty() && !style.equals(runStyle[0])) {
                result.append(Component.literal(run.toString()).setStyle(runStyle[0]));
                run.setLength(0);
            }
            runStyle[0] = style;
            run.appendCodePoint(codePoint);
            return true;
        });
        if (!run.isEmpty()) result.append(Component.literal(run.toString()).setStyle(runStyle[0]));
        return result;
    }

    private static boolean contentLine(String text) {
        String plain = Zealots.strip(text).trim();
        return !plain.isEmpty() && !plain.contains("LOOT DEBUG") && !plain.matches("^[═=\\-▬]+$");
    }

    private static int open(long id) {
        List<Component> rows = rows(id);
        if (rows == null) {
            Toast.changed("Loot roll", "These older details are no longer available");
            return 0;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new LootRollDetailsScreen(mc.screen, rows));
        return 1;
    }
}
