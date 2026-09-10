package com.endsight.storage;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Storage snapshots, written to disk so they survive a restart.
 *
 * Until now every relaunch started blind: the snapshots lived in a HashMap and nothing
 * wrote them anywhere, so the first hover over every page showed "open this page once
 * to remember it" no matter how many times you had already opened it.
 *
 * NBT rather than the properties file the settings use, because a snapshot is up to 63
 * live ItemStacks with full components - enchantments, custom names, lore, skull
 * textures. Writing names and counts as text would be trivial and would quietly throw
 * away everything that makes the preview worth looking at; the items would come back as
 * the right shape and the wrong objects. ItemStack has a codec that does this properly,
 * and NBT is what that codec speaks.
 *
 * Keyed by server address. Storage pages are a DragSim concept, and showing snapshots
 * from one server while connected to another is worse than showing none - it looks
 * live. Connecting somewhere else starts empty rather than lying.
 */
public final class SnapshotStore {

    private SnapshotStore() {
    }

    private static final String SERVER = "server";
    private static final String PAGES = "pages";
    private static final String ITEMS = "items";

    /** Saved at most this often while playing, so a long session is not lost to a crash. */
    private static final long SAVE_EVERY_MS = 15_000;

    private static boolean loaded;
    private static boolean dirty;
    private static long lastSave;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight-storage.nbt");
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null) {
                // Left the server: forget that we loaded, so reconnecting re-reads and
                // re-checks the server key rather than carrying another world's pages.
                loaded = false;
                return;
            }
            if (!loaded) {
                load();
                loaded = true;
            }
            if (dirty && System.currentTimeMillis() - lastSave > SAVE_EVERY_MS) {
                save();
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (dirty) save();
        });
    }

    /** Called by StoragePreview whenever a page snapshot changes. */
    public static void markDirty() {
        dirty = true;
    }

    // ── reading ───────────────────────────────────────────────────────────────

    private static void load() {
        Path path = file();
        if (!Files.exists(path)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.create(32L * 1024 * 1024));
            String savedServer = root.getStringOr(SERVER, "");
            if (!savedServer.equals(serverKey())) return;      // different server, start empty

            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess());
            Map<Integer, PageSnapshot> into = StoragePreview.snapshots();

            for (CompoundTag pageTag : root.getListOrEmpty(PAGES).compoundStream().toList()) {
                int page = pageTag.getIntOr("page", -1);
                if (page < 0) continue;

                List<ItemStack> items = new ArrayList<>();
                for (Tag itemTag : pageTag.getListOrEmpty(ITEMS)) {
                    items.add(ItemStack.OPTIONAL_CODEC.parse(ops, itemTag)
                            .result().orElse(ItemStack.EMPTY));
                }

                into.put(page, new PageSnapshot(page,
                        pageTag.getIntOr("count", 0),
                        items,
                        pageTag.getLongOr("at", System.currentTimeMillis())));
            }
        } catch (IOException | RuntimeException e) {
            // A snapshot file is a convenience, never load-bearing. A corrupt or
            // out-of-date one costs the previews and nothing else, so it is dropped
            // rather than allowed to stop the mod.
            System.err.println("[Endsight] could not read snapshots, starting empty: " + e);
        }
    }

    // ── writing ───────────────────────────────────────────────────────────────

    private static void save() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Map<Integer, PageSnapshot> from = StoragePreview.snapshots();
        lastSave = System.currentTimeMillis();
        dirty = false;
        if (from.isEmpty()) return;

        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess());

            ListTag pages = new ListTag();
            for (PageSnapshot snap : from.values()) {
                CompoundTag pageTag = new CompoundTag();
                pageTag.putInt("page", snap.page());
                pageTag.putInt("count", snap.pageCount());
                pageTag.putLong("at", snap.capturedAt());

                ListTag items = new ListTag();
                for (ItemStack stack : snap.items()) {
                    ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack)
                            .result().ifPresent(items::add);
                }
                pageTag.put(ITEMS, items);
                pages.add(pageTag);
            }

            CompoundTag root = new CompoundTag();
            root.putString(SERVER, serverKey());
            root.put(PAGES, pages);

            Path path = file();
            Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(root, path);
        } catch (IOException | RuntimeException e) {
            System.err.println("[Endsight] could not write snapshots: " + e);
        }
    }

    /**
     * Which server these snapshots belong to.
     *
     * The address rather than the display name, because the name is whatever the server
     * list entry was called and two entries can point at the same place.
     */
    private static String serverKey() {
        ServerData data = Minecraft.getInstance().getCurrentServer();
        return data == null || data.ip == null ? "singleplayer" : data.ip.toLowerCase();
    }
}
