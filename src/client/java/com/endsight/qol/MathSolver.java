package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers the Golden Dragon's sum.
 *
 * "Find the answer to 76.2 + 19.6?" with fifteen seconds to type it. The answer goes
 * into chat right under the question, as a line only you see, so it is where your eyes
 * already are; or onto the clipboard, if you would rather paste it. Every sighting in
 * the logs has been a plus, but the other three are one line each.
 *
 * Decimal arithmetic, not double: 76.2 + 19.6 in a double is 95.80000000000001, and a
 * wrong answer is worse than none.
 */
public final class MathSolver {

    private MathSolver() {
    }

    private static final Pattern QUESTION = Pattern.compile(
            "Find the answer to\\s*(-?[\\d.]+)\\s*([-+*/x×÷])\\s*(-?[\\d.]+)\\s*\\?", Pattern.CASE_INSENSITIVE);

    private static boolean enabled = true;
    private static boolean clipboard = false;

    public static Module module() {
        return new Module("qol.math", "Math Solver",
                "Works out the Golden Dragon's sum and puts the answer under it.", "Quality of Life",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Toggle("Copy to clipboard",
                                "Put the answer on the clipboard instead of in chat.",
                                () -> clipboard, v -> clipboard = v)));
    }

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            Matcher m = QUESTION.matcher(Zealots.strip(message.getString()));
            if (!m.find()) return;
            String answer;
            try {
                answer = solve(new BigDecimal(m.group(1)), m.group(2), new BigDecimal(m.group(3)));
            } catch (ArithmeticException | NumberFormatException e) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (clipboard) {
                mc.keyboardHandler.setClipboard(answer);
            } else if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§d» §fAnswer: §a" + answer));
            }
        });
    }

    private static String solve(BigDecimal a, String op, BigDecimal b) {
        BigDecimal r = switch (op) {
            case "+" -> a.add(b);
            case "-" -> a.subtract(b);
            case "*", "x", "×" -> a.multiply(b);
            default -> a.divide(b, MathContext.DECIMAL64);
        };
        // 95.80 reads as 95.8, and 100.00 as 100 - what a person would type.
        return r.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
