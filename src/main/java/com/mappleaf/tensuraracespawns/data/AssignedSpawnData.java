package com.mappleaf.tensuraracespawns.data;

import com.mappleaf.tensuraracespawns.TensuraRaceSpawns;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

public final class AssignedSpawnData extends SavedData {
    private static final String DATA_NAME = TensuraRaceSpawns.MOD_ID + "_assigned_spawns";
    private final Map<String, UUID> assignments = new HashMap<>();

    public static AssignedSpawnData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(AssignedSpawnData::new, AssignedSpawnData::load), DATA_NAME);
    }

    public static AssignedSpawnData load(CompoundTag tag, HolderLookup.Provider provider) {
        AssignedSpawnData data = new AssignedSpawnData();
        CompoundTag entries = tag.getCompound("entries");
        for (String key : entries.getAllKeys()) {
            try {
                data.assignments.put(key, entries.getUUID(key));
            } catch (Exception ignored) {
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag entries = new CompoundTag();
        assignments.forEach(entries::putUUID);
        tag.put("entries", entries);
        return tag;
    }

    public boolean isAvailableFor(String assignmentKey, UUID playerId) {
        UUID owner = assignments.get(assignmentKey);
        return owner == null || owner.equals(playerId);
    }

    public void assign(ServerPlayer player, String assignmentKey) {
        assignments.put(assignmentKey, player.getUUID());
        this.setDirty();
        CompoundTag tag = persistent(player);
        tag.putString("assignedSpawnKey", assignmentKey);
        tag.putUUID("assignedSpawnOwner", player.getUUID());
        putPersistent(player, tag);
    }

    public static CompoundTag persistent(ServerPlayer player) {
        return player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getCompound(TensuraRaceSpawns.MOD_ID);
    }

    public static void putPersistent(ServerPlayer player, CompoundTag subTag) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        persisted.put(TensuraRaceSpawns.MOD_ID, subTag);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }

    public static boolean hasInitialSpawnApplied(ServerPlayer player) {
        return persistent(player).getBoolean("initialSpawnApplied");
    }

    public static void markInitialSpawnApplied(ServerPlayer player) {
        CompoundTag tag = persistent(player);
        tag.putBoolean("initialSpawnApplied", true);
        putPersistent(player, tag);
    }

    public static void saveFallback(ServerPlayer player, Level level, net.minecraft.core.BlockPos pos) {
        CompoundTag tag = persistent(player);
        tag.putString("fallbackDimension", level.dimension().location().toString());
        tag.putInt("fallbackX", pos.getX());
        tag.putInt("fallbackY", pos.getY());
        tag.putInt("fallbackZ", pos.getZ());
        putPersistent(player, tag);
    }

    public static Optional<FallbackSpawn> getFallback(ServerPlayer player) {
        CompoundTag tag = persistent(player);
        if (!tag.contains("fallbackDimension")) return Optional.empty();
        return Optional.of(new FallbackSpawn(tag.getString("fallbackDimension"), tag.getInt("fallbackX"), tag.getInt("fallbackY"), tag.getInt("fallbackZ")));
    }

    public record FallbackSpawn(String dimension, int x, int y, int z) {}
}
