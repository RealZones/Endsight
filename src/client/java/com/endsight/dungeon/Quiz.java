package com.endsight.dungeon;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.List;

/**
 * The Quiz room: Oruo the Omniscient asks three questions and you click the block under
 * the right answer. Questions, options and verdicts come from chat ({@link QuizLogic});
 * the room only says where each answer stands, as a label like "ⓒ Pat." over its block.
 *
 * Your click is remembered and believed only once Oruo says it was right, so the book
 * learns this server's answers where they differ from the bundled ones, and a wrong one
 * is crossed off for next time. A teammate's answer teaches it too, read from which
 * answer block they were standing at when Oruo ruled.
 */
public final class Quiz {
    private static final String ID = "dungeon.quiz";

    private static final QuizLogic.Book BOOK = new QuizLogic.Book();
    private static final QuizLogic.Asking ASKING = new QuizLogic.Asking();

    private static boolean enabled = true, announce = true, highlight = true, tracer = true, throughWalls = true;
    private static boolean active, loaded, unsaid;
    private static ClientLevel world;
    private static String question;                     // being asked now, or null between questions
    private static int index;
    private static List<String> options = List.of();
    private static String answer;                       // the option to click, or null
    private static String pendingQuestion, pendingOption;   // your click, until Oruo rules on it
    private static BlockPos marked;                     // the block under the answer's label
    private static int ticks;

    private Quiz() { }

    public static Module module() {
        return new Module(ID, "Quiz", "Names the quiz answer and marks the block to click.",
                "Dungeons", () -> enabled, value -> { enabled = value; if (!value) reset(); }, List.of(
                new Setting.Toggle("Say the answer", "Print the answer in chat when a question appears.",
                        () -> announce, value -> announce = value),
                new Setting.Toggle("Highlight the answer", "Box the block to click.",
                        () -> highlight, value -> highlight = value),
                new Setting.Toggle("Tracer", "Draw a line to the block to click.",
                        () -> tracer, value -> tracer = value),
                new Setting.Toggle("Through walls", "Keep the box and tracer visible through blocks.",
                        () -> throughWalls, value -> throughWalls = value)));
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { BOOK.save(); reset(); });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) chat(message.getString()); });
        ClientTickEvents.END_CLIENT_TICK.register(Quiz::tick);
        // The tracer starts at the camera, so it is redrawn every frame; the box is drawn
        // from the tick, which is also what shows in the test client.
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> drawTracer());
        // Which answer you picked. Left or right click, block or label: whichever the
        // server takes, the nearest label to it is the option.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) clicked(Vec3.atCenterOf(hit.getBlockPos()));
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (level.isClientSide()) clicked(Vec3.atCenterOf(pos));
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide()) clicked(entity.position());
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide()) clicked(entity.position());
            return InteractionResult.PASS;
        });
    }

    private static void reset() {
        active = false; world = null; ASKING.clear();
        endQuestion();
    }

    private static void endQuestion() {
        question = null; index = 0; options = List.of(); answer = null; marked = null;
        pendingQuestion = null; pendingOption = null; unsaid = false;
    }

    private static void chat(String raw) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.level == null || mc.player == null || !DungeonContext.active()) return;
        if (!loaded) { BOOK.load(file()); loaded = true; }
        String line = Zealots.strip(raw).trim();
        boolean asked = ASKING.feed(line);
        // Not reset(): that clears ASKING, and on the room's first question this line is
        // the one that just completed it. The test room caught that as "Quiz #0".
        if ((asked || QuizLogic.oruo(line)) && (!active || world != mc.level)) {
            endQuestion();
            active = true;
            world = mc.level;
        }
        if (!active) return;
        if (asked) { ask(mc); return; }
        QuizLogic.Ruling ruling = QuizLogic.ruling(line);
        if (ruling == null) return;
        switch (ruling.verdict()) {
            case RIGHT, WRONG -> {
                // Your own click if it was seen; otherwise, yours or a teammate's, the
                // answer block they are standing at. A wrong answer is still worth
                // something: three options with two crossed off name the third.
                String option = ruling.name().equals(mc.player.getName().getString())
                        && question != null && question.equals(pendingQuestion) ? pendingOption : standingAt(mc, ruling.name());
                if (question != null && option != null) {
                    if (ruling.verdict() == QuizLogic.Verdict.RIGHT) BOOK.learn(question, option);
                    else BOOK.exclude(question, option);
                    BOOK.save();
                }
                endQuestion();
            }
            case RESET -> endQuestion();
            case DONE -> reset();
        }
    }

    /** The option whose block this player is standing at, or null when it is not clear. */
    private static String standingAt(Minecraft mc, String name) {
        Player player = null;
        for (Player p : mc.level.players()) if (p.getName().getString().equals(name)) player = p;
        if (player == null) return null;
        List<String> shown = new java.util.ArrayList<>();
        List<Double> distances = new java.util.ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Player) continue;
            String text = label(entity);
            if (!QuizLogic.lettered(text) || !options.contains(QuizLogic.option(text))) continue;
            shown.add(QuizLogic.option(text));
            distances.add(entity.position().distanceTo(player.position()));
        }
        // Within five blocks of the label and two nearer than any other: the blocks in
        // the clips stand six or more apart, so anyone who just clicked one is beside it.
        int at = QuizLogic.standingAt(distances, 5, 2);
        return at < 0 ? null : shown.get(at);
    }

    private static void ask(Minecraft mc) {
        endQuestion();
        question = ASKING.text();
        index = ASKING.index();
        options = ASKING.options();
        String worked = QuizLogic.worked(question, System.currentTimeMillis());
        answer = worked != null && options.contains(worked) ? worked : BOOK.deduce(question, options);
        // Said on the next tick: this runs before the last option reaches the chat, and
        // the answer printed above "ⓒ Pat" instead of under the question.
        unsaid = announce;
    }

    private static void say(Minecraft mc) {
        unsaid = false;
        if (question == null) return;
        String worked = QuizLogic.worked(question, System.currentTimeMillis());
        String head = "§5Quiz §8#" + index + " §7- ";
        if (answer != null) {
            boolean deduced = worked == null && !answer.equals(QuizLogic.resolve(BOOK.answer(question), options));
            mc.player.sendSystemMessage(Component.literal(head + "§a" + answer + (deduced ? " §8(by elimination)" : "")));
        } else {
            var out = BOOK.excluded(question).stream().map(QuizLogic::option).distinct().toList();
            mc.player.sendSystemMessage(Component.literal(head + (out.isEmpty()
                    ? "§enot seen before, I will remember your answer"
                    : "§enot " + String.join("§7, §e", out) + "§7, I will remember your answer")));
        }
    }

    private static void tick(Minecraft mc) {
        if (!active) return;
        if (!enabled || mc.player == null || mc.level != world || !DungeonContext.active()) { reset(); return; }
        if (unsaid) say(mc);
        if (answer == null) { marked = null; return; }
        // The labels can arrive a moment after the chat lines; look again four times a second.
        if (marked == null || ++ticks % 5 == 0) marked = blockUnder(mc, answer);
        if (!highlight || marked == null) return;
        var box = Gizmos.cuboid(new AABB(marked).inflate(.02),
                GizmoStyle.strokeAndFill(0xFF55FF55, 2.5f, 0x5055FF55));
        if (throughWalls) box.setAlwaysOnTop();
    }

    /** The label standing over this option's block, nearest you, or null. */
    private static Entity labelFor(Minecraft mc, String option) {
        Entity best = null;
        double nearest = 24 * 24;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Player) continue;
            String text = label(entity);
            if (!QuizLogic.lettered(text) || !option.equals(QuizLogic.option(text))) continue;
            double distance = entity.distanceToSqr(mc.player);
            if (distance < nearest) { nearest = distance; best = entity; }
        }
        return best;
    }

    /** The first solid block at or below the label: the one the clip shows being clicked. */
    private static BlockPos blockUnder(Minecraft mc, String option) {
        Entity label = labelFor(mc, option);
        if (label == null) return null;
        BlockPos from = label.blockPosition();
        for (int down = 0; down <= 3; down++) {
            BlockPos pos = from.below(down);
            if (!mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty()) return pos.immutable();
        }
        return null;
    }

    private static void clicked(Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || !active || question == null || mc.level == null) return;
        String best = null;
        double nearest = 3.5 * 3.5;                        // labels stand well apart; this is "over it"
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Player) continue;
            String text = label(entity);
            if (!QuizLogic.lettered(text) || !options.contains(QuizLogic.option(text))) continue;
            double distance = entity.position().distanceToSqr(at);
            if (distance < nearest) { nearest = distance; best = QuizLogic.option(text); }
        }
        if (best == null) return;
        pendingQuestion = question;
        pendingOption = best;
    }

    private static String label(Entity entity) {
        var text = entity instanceof Display.TextDisplay display ? display.getText() : entity.getCustomName();
        return text == null ? "" : Zealots.strip(text.getString()).trim();
    }

    private static void drawTracer() {
        Minecraft mc = Minecraft.getInstance();
        if (!tracer || !active || marked == null || mc.level != world) return;
        // Same shape as the Revenant tile tracer: start a little ahead of and below the
        // camera so the line reads as coming from the player, not the eye.
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.position();
        var forward = camera.forwardVector();
        Vec3 from = eye.add(forward.x() * .8, forward.y() * .8 - .45, forward.z() * .8);
        Vec3 to = Vec3.atCenterOf(marked);
        var wide = Gizmos.line(from, to, 0x3055FF55, 6f);
        var thin = Gizmos.line(from, to, 0xCC55FF55, 1.8f);
        if (throughWalls) { wide.setAlwaysOnTop(); thin.setAlwaysOnTop(); }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("quiz-answers.txt");
    }
}
