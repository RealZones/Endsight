package com.endsight.qol;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * What Pets and Wardrobe remember, one file per player and server.
 *
 * NBT rather than the properties file these started in, because the pictures have to
 * survive a restart: the stack is the only thing that knows a pet's skin, and the server
 * only hands it over while its menu is open.
 */
final class LoadoutStore {
    private LoadoutStore() { }

    private static UUID scopedPlayer;
    private static ServerData scopedServer;
    private static String scoped;

    /**
     * Who this is for, or null outside a world, where item registries are not there to read.
     * Asked every tick and every frame, so the string is only built when either half changes.
     */
    static String scope(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return null;
        UUID player = mc.player.getUUID();
        ServerData server = mc.getCurrentServer();
        if (scoped == null || server != scopedServer || !player.equals(scopedPlayer)) {
            scopedPlayer = player;
            scopedServer = server;
            scoped = player + "|" + (server == null ? "local" : server.ip);
        }
        return scoped;
    }

    private static Path path(String name, String scope) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8)));
            return FabricLoader.getInstance().getConfigDir().resolve("endsight/" + name + "-" + hash.substring(0, 20) + ".nbt");
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static CompoundTag read(String name, String scope) {
        Path file = path(name, scope);
        if (!Files.isRegularFile(file)) return new CompoundTag();
        try { return NbtIo.readCompressed(file, NbtAccounter.create(16L * 1024 * 1024)); }
        catch (IOException | RuntimeException failure) {
            // Only pictures and picks: a bad file costs one press of Refresh, never the mod.
            System.err.println("[Endsight] could not read " + file.getFileName() + ": " + failure);
            return new CompoundTag();
        }
    }

    static void write(String name, String scope, CompoundTag root) {
        Path file = path(name, scope);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, temp);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            System.err.println("[Endsight] could not save " + file.getFileName() + ": " + failure);
        }
    }

    /** Only what draws the picture; lore is most of a stack's size and nothing here reads it back. */
    static ItemStack picture(ItemStack stack) {
        ItemStack copy = stack.copyWithCount(1);
        copy.remove(DataComponents.LORE);
        return copy;
    }

    static void put(CompoundTag tag, String key, ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || stack == null) return;
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess());
        ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result().ifPresent(item -> tag.put(key, item));
    }

    static ItemStack get(CompoundTag tag, String key) {
        Minecraft mc = Minecraft.getInstance();
        Tag item = tag.get(key);
        if (mc.level == null || item == null) return ItemStack.EMPTY;
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess());
        return ItemStack.OPTIONAL_CODEC.parse(ops, item).result().orElse(ItemStack.EMPTY);
    }
}
