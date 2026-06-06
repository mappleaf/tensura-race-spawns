package com.mappleaf.tensuraracespawns.spawn;

import com.mojang.datafixers.util.Pair;
import com.mappleaf.tensuraracespawns.TensuraRaceSpawns;
import com.mappleaf.tensuraracespawns.config.RaceSpawnConfig;
import com.mappleaf.tensuraracespawns.config.RaceSpawnRule;
import com.mappleaf.tensuraracespawns.data.AssignedSpawnData;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Predicate;

public final class RaceSpawnFinder {
    private static final int SAFE_POS_RANGE = 64;
    private static final int BIOME_SAMPLE_STEP = 64;

    private RaceSpawnFinder() {}

    public static Optional<LocatedSpawn> find(ServerPlayer player, RaceSpawnRule rule) {
        MinecraftServer server = player.server;
        ServerLevel level = resolveLevel(server, rule.spawnDimension()).orElse(server.overworld());
        if (rule.isVanillaWorldSpawn()) {
            return Optional.of(worldSpawn(level));
        }

        List<ResourceLocation> biomes = rule.spawnBiomes().stream().filter(id -> {
            boolean ok = SpawnCache.isValidBiome(id);
            if (!ok) TensuraRaceSpawns.LOGGER.warn("Configured biome '{}' is not registered; ignoring it", id);
            return ok;
        }).toList();

        List<ResourceLocation> structures = rule.spawnStructures().stream().filter(id -> {
            boolean ok = SpawnCache.isValidStructure(id);
            if (!ok) TensuraRaceSpawns.LOGGER.warn("Configured structure '{}' is not registered; ignoring it", id);
            return ok;
        }).toList();

        if (rule.hasBiomeFilter() && biomes.isEmpty()) return Optional.empty();
        if (rule.hasStructureFilter() && structures.isEmpty()) return Optional.empty();

        AssignedSpawnData assignedData = AssignedSpawnData.get(server);
        if (!structures.isEmpty()) {
            return findStructureSpawn(player, level, biomes, structures, rule.assignedToPlayer(), assignedData);
        }
        if (!biomes.isEmpty()) {
            return findBiomeSpawn(player, level, biomes, rule.assignedToPlayer(), assignedData);
        }
        return Optional.of(worldSpawn(level));
    }

    private static Optional<ServerLevel> resolveLevel(MinecraftServer server, String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) return Optional.empty();
        ResourceLocation id = ResourceLocation.tryParse(dimensionId);
        if (id == null) return Optional.empty();
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        return Optional.ofNullable(server.getLevel(key));
    }

    private static LocatedSpawn worldSpawn(ServerLevel level) {
        BlockPos pos = level.getSharedSpawnPos();
        return new LocatedSpawn(level, pos, null, null, null);
    }

    private static Optional<LocatedSpawn> findStructureSpawn(ServerPlayer player, ServerLevel level, List<ResourceLocation> biomeIds, List<ResourceLocation> structureIds, boolean assigned, AssignedSpawnData assignedData) {
        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        List<Holder<Structure>> holders = new ArrayList<>();
        for (ResourceLocation id : structureIds) {
            structureRegistry.getHolder(ResourceKey.create(Registries.STRUCTURE, id)).ifPresent(holders::add);
        }
        if (holders.isEmpty()) return Optional.empty();

        HolderSet<Structure> holderSet = HolderSet.direct(holders);
        int radiusBlocks = RaceSpawnConfig.spawnRadius();
        int radiusChunks = Math.max(1, (radiusBlocks + 15) / 16);
        BlockPos base = level.getSharedSpawnPos();
        Set<Long> triedCenters = new HashSet<>();

        for (BlockPos searchCenter : spiral(base, radiusBlocks, Math.max(256, radiusBlocks / 8))) {
            Pair<BlockPos, Holder<Structure>> nearest = level.getChunkSource().getGenerator().findNearestMapStructure(level, holderSet, searchCenter, radiusChunks, false);
            if (nearest == null) continue;
            BlockPos structurePos = nearest.getFirst();
            if (Math.abs(structurePos.getX() - base.getX()) > radiusBlocks || Math.abs(structurePos.getZ() - base.getZ()) > radiusBlocks) continue;
            long packed = ChunkPos.asLong(structurePos.getX() >> 4, structurePos.getZ() >> 4);
            if (!triedCenters.add(packed)) continue;

            ResourceLocation structureId = structureRegistry.getKey(nearest.getSecond().value());
            if (structureId == null) continue;
            if (!biomeIds.isEmpty()) {
                ResourceLocation centerBiome = biomeRegistry.getKey(level.getBiome(structurePos).value());
                if (!biomeIds.contains(centerBiome)) continue;
            }

            Optional<BlockPos> safe = findValidSpawn(player, level, structurePos, SAFE_POS_RANGE);
            if (safe.isEmpty()) continue;
            ResourceLocation spawnBiome = biomeRegistry.getKey(level.getBiome(safe.get()).value());
            String assignmentKey = assignmentKey("structure", structureId, structurePos);
            if (assigned && !assignedData.isAvailableFor(assignmentKey, player.getUUID())) continue;
            return Optional.of(new LocatedSpawn(level, safe.get(), assignmentKey, spawnBiome, structureId));
        }
        return Optional.empty();
    }

    private static Optional<LocatedSpawn> findBiomeSpawn(ServerPlayer player, ServerLevel level, List<ResourceLocation> biomeIds, boolean assigned, AssignedSpawnData assignedData) {
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        int radius = RaceSpawnConfig.spawnRadius();
        BlockPos base = level.getSharedSpawnPos();
        Predicate<Holder<Biome>> biomePredicate = holder -> biomeIds.contains(biomeRegistry.getKey(holder.value()));

        // Fast path: use vanilla 3D biome search, similar to Origins.
        Pair<BlockPos, Holder<Biome>> closest = level.findClosestBiome3d(biomePredicate, base, radius, BIOME_SAMPLE_STEP, 64);
        if (closest != null) {
            Optional<LocatedSpawn> spawn = tryBiomeCandidate(player, level, biomeRegistry, assigned, assignedData, closest.getFirst());
            if (spawn.isPresent()) return spawn;
        }

        // Fallback path: sample more candidates so assignedToPlayer can skip already occupied points.
        for (BlockPos candidate : spiral(base, radius, BIOME_SAMPLE_STEP)) {
            if (!biomePredicate.test(level.getBiome(candidate))) continue;
            Optional<LocatedSpawn> spawn = tryBiomeCandidate(player, level, biomeRegistry, assigned, assignedData, candidate);
            if (spawn.isPresent()) return spawn;
        }
        return Optional.empty();
    }

    private static Optional<LocatedSpawn> tryBiomeCandidate(ServerPlayer player, ServerLevel level, Registry<Biome> biomeRegistry, boolean assigned, AssignedSpawnData assignedData, BlockPos raw) {
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, raw);
        Optional<BlockPos> safe = findValidSpawn(player, level, surface, SAFE_POS_RANGE);
        if (safe.isEmpty()) return Optional.empty();
        ResourceLocation biome = biomeRegistry.getKey(level.getBiome(safe.get()).value());
        if (biome == null) return Optional.empty();
        String assignmentKey = assignmentKey("biome", biome, safe.get());
        if (assigned && !assignedData.isAvailableFor(assignmentKey, player.getUUID())) return Optional.empty();
        return Optional.of(new LocatedSpawn(level, safe.get(), assignmentKey, biome, null));
    }

    private static Optional<BlockPos> findValidSpawn(ServerPlayer player, ServerLevel level, BlockPos startPos, int range) {
        int dx = 1;
        int dz = 0;
        int segmentLength = 1;
        int segmentPassed = 0;
        int x = startPos.getX();
        int z = startPos.getZ();
        BlockPos.MutableBlockPos mutable = startPos.mutable();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int centerY = Mth.clamp(startPos.getY(), minY + 1, maxY - 1);

        for (int vertical = 0; vertical < maxY - minY; vertical++) {
            int y = centerY + ((vertical & 1) == 0 ? vertical / 2 : -(vertical / 2 + 1));
            if (y < minY || y >= maxY) continue;
            for (int steps = 0; steps < range * range; steps++) {
                x += dx;
                z += dz;
                mutable.set(x, y, z);
                Vec3 vec = DismountHelper.findSafeDismountLocation(player.getType(), level, mutable, true);
                if (vec != null) {
                    BlockPos pos = BlockPos.containing(vec);
                    level.getChunkSource().addRegionTicket(TicketType.START, new ChunkPos(pos), 11, Unit.INSTANCE);
                    return Optional.of(pos);
                }
                segmentPassed++;
                if (segmentPassed == segmentLength) {
                    segmentPassed = 0;
                    int oldDx = dx;
                    dx = -dz;
                    dz = oldDx;
                    if (dz == 0) segmentLength++;
                }
            }
        }
        return Optional.empty();
    }

    private static Iterable<BlockPos> spiral(BlockPos center, int radius, int step) {
        ArrayList<BlockPos> list = new ArrayList<>();
        list.add(center);
        if (radius <= 0) return list;
        for (int r = step; r <= radius; r += step) {
            for (int x = -r; x <= r; x += step) {
                list.add(center.offset(x, 0, -r));
                list.add(center.offset(x, 0, r));
            }
            for (int z = -r + step; z <= r - step; z += step) {
                list.add(center.offset(-r, 0, z));
                list.add(center.offset(r, 0, z));
            }
        }
        return list;
    }

    private static String assignmentKey(String type, ResourceLocation id, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        return type + ":" + id + ":" + chunkX + ":" + chunkZ;
    }
}
