package com.endsight.ui;

import com.endsight.hud.Area;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Matrix3x2f;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Output equivalence for hot-path changes; optional timings are CPU microbenchmarks, not FPS. */
public final class PerformanceChecks {
    private static int checks;
    private static volatile Object sink;
    private static final Matrix3x2f POSE = new Matrix3x2f();

    public static void main(String[] args) {
        search();
        caps();
        sidebar();
        caches();
        ShapeGeometryChecks.run();
        for (int columns : new int[]{4, 18, 34}) {
            GuiRenderState before = grid(columns, 18, false), after = grid(columns, 18, true);
            check(Arrays.equals(pixels(before), pixels(after)), "GUI layers preserve every blended pixel, cols=" + columns);
        }
        if (Boolean.getBoolean("endsight.performanceBenchmark")) benchmark();
        System.out.println(checks + " performance output-equivalence checks passed");
    }

    private static void search() {
        check(SettingsSearch.normalize(null).equals(""), "null query");
        Random random = new Random(25731);
        char[] chars = "AbcXYZ09 _§\t\n\r\f\u000B\u2003\u00A0İ".toCharArray();
        for (int n = 0; n < 5000; n++) {
            StringBuilder value = new StringBuilder();
            for (int i = random.nextInt(45); i > 0; i--) value.append(chars[random.nextInt(chars.length)]);
            String text = value.toString();
            check(SettingsSearch.normalize(text).equals(text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")),
                    "search normalization preserves ASCII regex and Unicode strip behavior");
        }
    }

    private static void caps() {
        for (int r = 1; r <= 150; r++) for (int row = 0; row < r; row++) {
            double dy = r - row - 0.5;
            int expected = (int) Math.round(r - Math.sqrt(Math.max(0, (double) r * r - dy * dy)));
            check(Draw.capInset(r, row) == expected, "cached and fallback rounded-cap geometry");
        }
    }

    private static void sidebar() {
        Area.Sidebar first = new Area.Sidebar(List.of("§6Slayer: §5Voidgloom Seraph IV", "§aXP: 300/500",
                "§7⏣ Void Sepulture", "§6Pet: §dBlack Cat"));
        check(first.line("Slayer:").equals("§6Slayer: §5Voidgloom Seraph IV"), "raw rarity/style codes retained");
        check(first.line("XP:").equals("§aXP: 300/500"), "XP prefix");
        check(first.line("Other:") == null, "missing line remains null");
        check(first.shows(Area.Where.VOID_SEPULTURE), "formatted location recognized");
        check(!first.shows(Area.Where.UNKNOWN) && !first.shows(Area.Where.END), "no guessed location");
        Area.Sidebar changed = new Area.Sidebar(List.of("§7⏣ Village"));
        check(changed.shows(Area.Where.HUB) && changed.line("Slayer:") == null, "next read immediately reflects a transfer");
        check(first.shows(Area.Where.VOID_SEPULTURE), "reads are independent");
        for (net.minecraft.ChatFormatting format : net.minecraft.ChatFormatting.values()) {
            if (!format.isColor() || format.getColor() == null) continue;
            var styled = net.minecraft.network.chat.Component.literal("Pet").withStyle(format);
            check(Area.legacy(styled).equals("§" + format.getChar() + "Pet"), "styled sidebar/pet colour retained");
            var literal = net.minecraft.network.chat.Component.literal("§dPet").withStyle(format);
            check(Area.legacy(literal).equals("§dPet"), "literal colour codes take precedence");
        }
        check(Area.legacy(net.minecraft.network.chat.Component.literal("Pet").withColor(0x123456)).equals("Pet"),
                "non-legacy RGB remains unchanged");
    }

    private static void caches() {
        try {
            ColorPicker picker = new ColorPicker();
            var hue = ColorPicker.class.getDeclaredField("hue"); hue.setAccessible(true);
            var spectrum = ColorPicker.class.getDeclaredMethod("spectrum"); spectrum.setAccessible(true);
            for (float h : new float[]{0, .27f, .6f, .999f, 0}) {
                hue.setFloat(picker, h);
                int[] colours = (int[]) spectrum.invoke(picker);
                check(spectrum.invoke(picker) == colours, "unchanged hue reuses its spectrum");
                for (int y = 0; y < 81; y += 3) for (int x = 0; x < 120; x += 3)
                    check(colours[y / 3 * 40 + x / 3] == (0xFF000000 | net.minecraft.util.Mth.hsvToRgb(
                            h, x / 117f, 1f - y / 78f)), "colour-picker cache preserves every RGB cell");
            }
            Class<?> stats = com.endsight.hud.ServerStats.class;
            var fps = stats.getDeclaredField("fps"); fps.setAccessible(true);
            var ping = stats.getDeclaredField("ping"); ping.setAccessible(true);
            var rate = stats.getDeclaredField("rate"); rate.setAccessible(true);
            var parts = stats.getDeclaredMethod("parts", net.minecraft.client.Minecraft.class, boolean.class); parts.setAccessible(true);
            boolean originalFps = fps.getBoolean(null), originalPing = ping.getBoolean(null);
            double originalRate = rate.getDouble(null);
            Locale originalLocale = Locale.getDefault(Locale.Category.FORMAT);
            try {
                fps.setBoolean(null, false); ping.setBoolean(null, false);
                for (Locale locale : List.of(Locale.US, Locale.FRANCE)) for (double value : new double[]{20, 18.32, 17.98}) {
                    Locale.setDefault(Locale.Category.FORMAT, locale); rate.setDouble(null, value);
                    @SuppressWarnings("unchecked") List<String[]> actual = (List<String[]>) parts.invoke(null, null, false);
                    check(actual.getFirst()[1].equals(String.format("%.1f", value)), "TPS and locale invalidate cached text");
                    check(parts.invoke(null, null, false) == actual, "unchanged HUD reuses the formatted rows");
                }
                fps.setBoolean(null, true); ping.setBoolean(null, true);
                @SuppressWarnings("unchecked") List<String[]> sample = (List<String[]>) parts.invoke(null, null, true);
                check(sample.size() == 3 && sample.getFirst()[1].equals("240") && sample.get(1)[1].equals("48ms")
                        && sample.getLast()[1].equals("20.0"), "sample mode and toggles invalidate cached text");
            } finally {
                fps.setBoolean(null, originalFps); ping.setBoolean(null, originalPing); rate.setDouble(null, originalRate);
                Locale.setDefault(Locale.Category.FORMAT, originalLocale);
            }
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static GuiRenderState grid(int cols, int rows, boolean separateRows) {
        GuiRenderState state = new GuiRenderState();
        rect(state, 0, 0, cols * 20 + 10, rows * 20 + 10, 0x80404040);
        for (int row = 0; row < rows; row++) {
            if (separateRows) state.nextStratum();
            for (int col = 0; col < cols; col++) {
                int x = 5 + col * 20, y = 5 + row * 20;
                rect(state, x + 1, y, 16, 1, 0xCFFFFFFF);
                rect(state, x + 1, y + 17, 16, 1, 0xCFFFFFFF);
                rect(state, x, y + 1, 1, 16, 0xCFFFFFFF);
                rect(state, x + 17, y + 1, 1, 16, 0xCFFFFFFF);
                if ((row + col) % 3 == 0) rect(state, x + 1, y + 1, 16, 16, 0x3044FF88);
                // Stand-ins retain the same bounds and painter order as icon/text submissions.
                rect(state, x + 2, y + 2, 16, 16, 0xA0553388);
                rect(state, x + 10, y + 2, 6, 8, 0xFFFFAA00);
            }
        }
        if (separateRows) state.nextStratum();
        rect(state, 7, 9, 47, 23, 0xCA161620); // late hover/viewer overlapping previous rows
        return state;
    }

    private static void rect(GuiRenderState state, int x, int y, int w, int h, int colour) {
        state.addGuiElement(new ColoredRectangleRenderState(RenderPipelines.GUI, TextureSetup.noTexture(),
                POSE, x, y, x + w, y + h, colour, colour, null));
    }

    private static int[] pixels(GuiRenderState state) {
        int width = 710, height = 380;
        int[] pixels = new int[width * height];
        state.forEachElement(element -> {
            ColoredRectangleRenderState rect = (ColoredRectangleRenderState) element;
            ScreenRectangle b = rect.bounds();
            for (int y = b.top(); y < b.bottom(); y++) for (int x = b.left(); x < b.right(); x++) {
                int previous = pixels[y * width + x], colour = rect.col1(), alpha = colour >>> 24;
                int result = 0xFF000000;
                for (int shift : new int[]{0, 8, 16}) result |= ((((colour >> shift) & 255) * alpha
                        + ((previous >> shift) & 255) * (255 - alpha)) / 255) << shift;
                pixels[y * width + x] = result;
            }
        }, GuiRenderState.TraverseRange.ALL);
        return pixels;
    }

    private static void benchmark() {
        for (int i = 0; i < 20; i++) { sink = grid(34, 18, false); sink = grid(34, 18, true); }
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (boolean rows : new boolean[]{false, true}) {
            long bytes = bean.getCurrentThreadAllocatedBytes(), start = System.nanoTime();
            for (int i = 0; i < 100; i++) sink = grid(34, 18, rows);
            System.out.printf(Locale.ROOT, "GUI submission %s: %.3f ms/frame, %.0f allocated bytes/frame%n",
                    rows ? "row strata" : "original", (System.nanoTime() - start) / 100_000_000.0,
                    (bean.getCurrentThreadAllocatedBytes() - bytes) / 100.0);
        }
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
