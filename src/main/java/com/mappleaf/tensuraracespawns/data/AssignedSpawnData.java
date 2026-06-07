package com.mappleaf.tensuraracespawns.data;

import com.mappleaf.tensuraracespawns.TensuraRaceSpawns;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

public final class AssignedSpawnData extends SavedData {
    private static final String DATA_NAME = TensuraRaceSpawns.MOD_ID + "_assigned_spawns";

    private static final String TAG_INITIAL_APPLIED = "initialSpawnApplied";
    private static final String TAG_APPLIED_RACE_ID = "appliedRaceId";
    private static final String TAG_MANAGED_RESPAWN = "managedRespawnPoint";
    private static final String TAG_ASSIGNED_KEY = "assignedSpawnKey";
    private static final String TAG_FALLBACK_DIMENSION = "fallbackDimension";
    private static final String TAG_FALLBACK_X = "fallbackX";
    private static final String TAG_FALLBACK_Y = "fallbackY";
    private static final String TAG_FALLBACK_Z = "fallbackZ";
    private static final String TAG_APPLIED_BIOME_ID = "appliedSpawnBiomeId";
    private static final String TAG_APPLIED_STRUCTURE_ID = "appliedSpawnStructureId";

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
        tag.putString(TAG_ASSIGNED_KEY, assignmentKey);
        putPersistent(player, tag);
    }

    public void unassignIfOwned(UUID playerId, String assignmentKey) {
        UUID owner = assignments.get(assignmentKey);
        if (owner != null && owner.equals(playerId)) {
            assignments.remove(assignmentKey);
            this.setDirty();
        }
    }

    public void unassignAllOwned(UUID playerId) {
        boolean changed = assignments.entrySet().removeIf(entry -> playerId.equals(entry.getValue()));
        if (changed) {
            this.setDirty();
        }
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
        return persistent(player).getBoolean(TAG_INITIAL_APPLIED);
    }

    public static Optional<ResourceLocation> getAppliedRaceId(ServerPlayer player) {
        CompoundTag tag = persistent(player);
        if (!tag.contains(TAG_APPLIED_RACE_ID)) return Optional.empty();
        return Optional.ofNullable(ResourceLocation.tryParse(tag.getString(TAG_APPLIED_RACE_ID)));
    }

    public static boolean hasManagedSpawnRecords(ServerPlayer player) {
        CompoundTag tag = persistent(player);
        return tag.getBoolean(TAG_INITIAL_APPLIED)
                || tag.contains(TAG_APPLIED_RACE_ID)
                || tag.contains(TAG_FALLBACK_DIMENSION)
                || tag.contains(TAG_ASSIGNED_KEY)
                || tag.contains(TAG_APPLIED_BIOME_ID)
                || tag.contains(TAG_APPLIED_STRUCTURE_ID)
                || tag.getBoolean(TAG_MANAGED_RESPAWN);
    }

    public static Optional<String> getAssignedSpawnKey(ServerPlayer player) {
        CompoundTag tag = persistent(player);
        if (!tag.contains(TAG_ASSIGNED_KEY)) return Optional.empty();
        String key = tag.getString(TAG_ASSIGNED_KEY);
        return key == null || key.isBlank() ? Optional.empty() : Optional.of(key);
    }

    public static Optional<ResourceLocation> getAssignedStructureId(ServerPlayer player) {
        return getAssignedSpawnKey(player)
                .filter(key -> key.startsWith("structure:"))
                .flatMap(key -> {
                    int chunkSeparator = key.lastIndexOf(':');
                    if (chunkSeparator <= "structure:".length()) return Optional.empty();
                    int previousSeparator = key.lastIndexOf(':', chunkSeparator - 1);
                    if (previousSeparator <= "structure:".length()) return Optional.empty();
                    String id = key.substring("structure:".length(), previousSeparator);
                    return Optional.ofNullable(ResourceLocation.tryParse(id));
                });
    }

    public static void updateAppliedRaceId(ServerPlayer player, ResourceLocation raceId) {
        CompoundTag tag = persistent(player);
        tag.putBoolean(TAG_INITIAL_APPLIED, true);
        tag.putString(TAG_APPLIED_RACE_ID, raceId.toString());
        putPersistent(player, tag);
    }

    public static void clearAssignedSpawnKey(ServerPlayer player) {
        CompoundTag tag = persistent(player);
        if (!tag.contains(TAG_ASSIGNED_KEY)) return;
        String assignedKey = tag.getString(TAG_ASSIGNED_KEY);
        if (assignedKey != null && !assignedKey.isBlank()) {
            get(player.server).unassignIfOwned(player.getUUID(), assignedKey);
        }
        tag.remove(TAG_ASSIGNED_KEY);
        putPersistent(player, tag);
    }

    public static Optional<ResourceLocation> getAppliedSpawnStructureId(ServerPlayer player) {
        CompoundTag tag = persistent(player);
        if (tag.contains(TAG_APPLIED_STRUCTURE_ID)) {
            return Optional.ofNullable(ResourceLocation.tryParse(tag.getString(TAG_APPLIED_STRUCTURE_ID)));
        }
        return getAssignedStructureId(player);
    }

    public static void saveAppliedSpawnMetadata(ServerPlayer player, ResourceLocation biomeId, ResourceLocation structureId) {
        CompoundTag tag = persistent(player);
        if (biomeId != null) {
            tag.putString(TAG_APPLIED_BIOME_ID, biomeId.toString());
        } else {
            tag.remove(TAG_APPLIED_BIOME_ID);
        }
        if (structureId != null) {
            tag.putString(TAG_APPLIED_STRUCTURE_ID, structureId.toString());
        } else {
            tag.remove(TAG_APPLIED_STRUCTURE_ID);
        }
        putPersistent(player, tag);
    }

    public static void markInitialSpawnApplied(ServerPlayer player, ResourceLocation raceId) {
        CompoundTag tag = persistent(player);
        tag.putBoolean(TAG_INITIAL_APPLIED, true);
        tag.putString(TAG_APPLIED_RACE_ID, raceId.toString());
        putPersistent(player, tag);
    }


    public static void markManagedRespawnPoint(ServerPlayer player) {
        CompoundTag tag = persistent(player);
        tag.putBoolean(TAG_MANAGED_RESPAWN, true);
        putPersistent(player, tag);
    }

    public static void saveFallback(ServerPlayer player, Level level, net.minecraft.core.BlockPos pos) {
        CompoundTag tag = persistent(player);
        tag.putString(TAG_FALLBACK_DIMENSION, level.dimension().location().toString());
        tag.putInt(TAG_FALLBACK_X, pos.getX());
        tag.putInt(TAG_FALLBACK_Y, pos.getY());
        tag.putInt(TAG_FALLBACK_Z, pos.getZ());
        putPersistent(player, tag);
    }

    public static Optional<FallbackSpawn> getFallback(ServerPlayer player) {
        CompoundTag tag = persistent(player);
        if (!tag.contains(TAG_FALLBACK_DIMENSION)) return Optional.empty();
        return Optional.of(new FallbackSpawn(tag.getString(TAG_FALLBACK_DIMENSION), tag.getInt(TAG_FALLBACK_X), tag.getInt(TAG_FALLBACK_Y), tag.getInt(TAG_FALLBACK_Z)));
    }

    public static void clearSpawnRecords(ServerPlayer player, String reason) {
        CompoundTag tag = persistent(player);
        if (tag.isEmpty() && !hasManagedSpawnRecords(player)) return;

        String assignedKey = tag.contains(TAG_ASSIGNED_KEY) ? tag.getString(TAG_ASSIGNED_KEY) : null;
        AssignedSpawnData data = get(player.server);
        if (assignedKey != null && !assignedKey.isBlank()) {
            data.unassignIfOwned(player.getUUID(), assignedKey);
        }
        data.unassignAllOwned(player.getUUID());

        boolean clearRespawn = tag.getBoolean(TAG_MANAGED_RESPAWN);

        tag.remove(TAG_INITIAL_APPLIED);
        tag.remove(TAG_APPLIED_RACE_ID);
        tag.remove(TAG_MANAGED_RESPAWN);
        tag.remove(TAG_ASSIGNED_KEY);
        tag.remove("assignedSpawnOwner");
        tag.remove(TAG_APPLIED_BIOME_ID);
        tag.remove(TAG_APPLIED_STRUCTURE_ID);
        tag.remove(TAG_FALLBACK_DIMENSION);
        tag.remove(TAG_FALLBACK_X);
        tag.remove(TAG_FALLBACK_Y);
        tag.remove(TAG_FALLBACK_Z);
        putPersistent(player, tag);

        if (clearRespawn) {
            player.setRespawnPosition(Level.OVERWORLD, null, 0.0F, false, false);
        }

        TensuraRaceSpawns.LOGGER.info("Cleared configured race spawn records for player {} ({})", player.getGameProfile().getName(), reason);
    }

    public record FallbackSpawn(String dimension, int x, int y, int z) {}
}
