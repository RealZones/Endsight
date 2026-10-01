package com.endsight.slayers;

import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Raised souls drawn see-through. The Voidling Extremists a scythe raises stand between
 * you and the boss - a recorded T5 had three of them filling the middle of the screen.
 *
 * A soul is known by its name tag, "[Lv100] Voidling Extremist (liambal's soul)", which
 * rides a stand over the body rather than the body itself. Once a tick the tags are found
 * and each is matched to the nearest body under it; the renderer then asks {@link #alpha}
 * per body per frame, which is one set lookup.
 *
 * The fade is the game's own: an invisible teammate is drawn see-through at 15%, and the
 * souls go down that same path with this opacity in its place. Their eyes glow on top,
 * as a teammate's do, which is what keeps a faded soul findable.
 */
public final class Summons {

    private Summons() {
    }

    private static double opacity = 45;
    /** The tags go too: a faded body already says which mobs are souls, and three tags a soul fill the screen. */
    private static boolean hideTags = true;
    private static final Set<Integer> SOULS = new HashSet<>();
    /** Whatever carries a soul tag - a stand, TextDisplay, or the body itself. */
    private static final Set<Integer> TAGS = new HashSet<>();
    /** "(ImFear's soul)", with either apostrophe a font might send. */
    private static final Pattern SOUL = Pattern.compile("\\(\\S+['’]s soul\\)");

    static boolean isSoulLabel(String text) {
        if (text == null) return false;
        String plain = Zealots.strip(text);
        return SOUL.matcher(plain).find() || SOUL.matcher(new StringBuilder(plain).reverse()).find();
    }

    static boolean isUnderTag(double dx, double dy, double dz) {
        return dx * dx + dz * dz <= 0.75 * 0.75 && dy >= -0.5 && dy <= 4.5;
    }

    /** The Summons section of the Voidgloom Helper page; the helper's own switch turns it on. */
    static List<Setting> settings() {
        return List.of(new Setting.Section("Summons"),
                new Setting.Slider("Summon opacity", "How solid your raised souls are drawn.",
                        0, 100, 5, () -> opacity, v -> opacity = v, "%"),
                new Setting.Toggle("Hide summon tags", "Hides their name tags too.",
                        () -> hideTags, v -> hideTags = v));
    }

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(Summons::scan);
    }

    private static void scan(Minecraft mc) {
        SOULS.clear();
        TAGS.clear();
        if (!VoidgloomHelper.on() || mc.level == null) return;
        List<Entity> tags = new ArrayList<>();
        List<LivingEntity> bodies = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            // An invisible mob is never the body you see - on some servers it is what carries
            // the name - so it is treated as a tag, and the fade goes to the body under it.
            boolean body = e instanceof LivingEntity && !(e instanceof ArmorStand) && !(e instanceof Player)
                    && !e.isInvisible();
            Component name = e instanceof Display.TextDisplay text ? text.getText() : e.getCustomName();
            // Stripped first: the server writes its colours into the name as literal codes,
            // "§7(§dImFear's soul§7)", and the colour change before the bracket kept the
            // first build from ever matching a soul.
            if (name != null && isSoulLabel(name.getString())) {
                TAGS.add(e.getId());
                if (body) SOULS.add(e.getId());
                else if (e.getVehicle() instanceof LivingEntity riderBody
                        && !(riderBody instanceof ArmorStand) && !(riderBody instanceof Player)) {
                    SOULS.add(riderBody.getId());
                    TAGS.add(riderBody.getId());
                }
                else tags.add(e);
            } else if (body) bodies.add((LivingEntity) e);
        }
        // Nearest body under each tag, and never one body for two tags: three souls
        // standing together must be three bodies, not the closest one three times.
        for (Entity tag : tags) {
            LivingEntity best = null;
            double bestSq = 0.75 * 0.75;
            for (LivingEntity b : bodies) {
                double up = tag.getY() - b.getY(), dx = b.getX() - tag.getX(), dz = b.getZ() - tag.getZ();
                double sq = dx * dx + dz * dz;
                if (!isUnderTag(dx, up, dz) || sq >= bestSq || SOULS.contains(b.getId())) continue;
                best = b;
                bestSq = sq;
            }
            if (best != null) {
                SOULS.add(best.getId());
                TAGS.add(best.getId());
            }
        }
    }

    /** Whether this entity's name tag is a soul's and should not be drawn. */
    public static boolean hideTag(Entity e) {
        return VoidgloomHelper.on() && hideTags && TAGS.contains(e.getId());
    }

    /** How solid to draw this body, 0 to 1, or -1 for anything that is not a soul. */
    public static float alpha(LivingEntity e) {
        // Full opacity draws it normally: the see-through path would only add sorting.
        if (!VoidgloomHelper.on() || opacity >= 100 || !SOULS.contains(e.getId())) return -1f;
        return (float) (opacity / 100.0);
    }
}
