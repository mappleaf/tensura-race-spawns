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
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public final class RaceSpawnFinder {
    private static final int SAFE_POS_RANGE = 64;
    private static final int BIOME_SAMPLE_STEP = 32;
    private static final int ASYNC_BIOME_RETRY_CENTER_STEP = 512;
    private static final int ASYNC_BIOME_MAX_RETRIES = 48;

    private RaceSpawnFinder() {}


    public static CompletableFuture<Optional<LocatedSpawn>> findAsync(ServerPlayer player, RaceSpawnRule rule) {
        MinecraftServer server = player.server;
        ServerLevel level = resolveLevel(server, rule.spawnDimension()).orElse(server.overworld());
        if (rule.isVanillaWorldSpawn()) {
            return CompletableFuture.completedFuture(Optional.of(worldSpawn(level)));
        }

        List<ResourceLocation> biomes = expandValidBiomes(level, rule);
        List<ResourceLocation> structures = expandValidStructures(level, rule);

        if (rule.hasBiomeFilter() && biomes.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
        if (rule.hasStructureFilter() && structures.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());

        if (TensuraRaceSpawns.LOGGER.isInfoEnabled()) {
            TensuraRaceSpawns.LOGGER.info("Starting search for valid spawn location for player {}", player.getDisplayName().getString());
        }

        AssignedSpawnData assignedData = AssignedSpawnData.get(server);
        if (!structures.isEmpty()) {
            return findStructureSpawnWithOptionalAsync(player, level, biomes, structures, rule.assignedToPlayer(), assignedData);
        }
        if (!biomes.isEmpty()) {
            return findBiomeSpawnWithOptionalAsync(player, level, biomes, rule.assignedToPlayer(), assignedData);
        }
        return CompletableFuture.completedFuture(Optional.of(worldSpawn(level)));
    }


    public static boolean isCurrentRespawnValidForRule(ServerPlayer player, RaceSpawnRule rule) {
        ServerLevel expectedLevel = resolveLevel(player.server, rule.spawnDimension()).orElse(player.server.overworld());
        ResourceKey<Level> currentDimension = player.getRespawnDimension();
        BlockPos currentPos = player.getRespawnPosition();

        if (currentPos == null) {
            currentDimension = player.server.overworld().dimension();
            currentPos = player.server.overworld().getSharedSpawnPos();
        }

        ServerLevel currentLevel = player.server.getLevel(currentDimension);
        if (currentLevel == null || !currentLevel.dimension().equals(expectedLevel.dimension())) {
            return false;
        }

        if (rule.isVanillaWorldSpawn()) {
            return currentLevel.dimension().equals(player.server.overworld().dimension())
                    && currentPos.distManhattan(player.server.overworld().getSharedSpawnPos()) <= 2;
        }

        List<ResourceLocation> biomes = expandValidBiomes(currentLevel, rule);
        List<ResourceLocation> structures = expandValidStructures(currentLevel, rule);

        if (rule.hasBiomeFilter() && biomes.isEmpty()) return false;
        if (rule.hasStructureFilter() && structures.isEmpty()) return false;

        Registry<Biome> biomeRegistry = currentLevel.registryAccess().registryOrThrow(Registries.BIOME);
        ResourceLocation currentBiome = getMatchingSurfaceBiomeId(currentLevel, biomeRegistry, currentPos, biomes).orElse(null);
        if (!biomes.isEmpty() && currentBiome == null) {
            return false;
        }

        Optional<ResourceLocation> currentStructure = Optional.empty();
        if (!structures.isEmpty()) {
            currentStructure = AssignedSpawnData.getAppliedSpawnStructureId(player);
            if (currentStructure.isEmpty() || !structures.contains(currentStructure.get())) {
                return false;
            }
        }

        if (!hasVanillaLikeSpawnSpace(currentLevel, currentPos)) {
            return false;
        }

        if (!rule.assignedToPlayer()) {
            return true;
        }

        Optional<String> currentAssignment = AssignedSpawnData.getAssignedSpawnKey(player);
        if (currentAssignment.isEmpty()) return false;
        String expectedType = !structures.isEmpty() ? "structure" : "biome";
        ResourceLocation expectedId = !structures.isEmpty()
                ? currentStructure.orElse(null)
                : (currentBiome != null ? currentBiome : getSurfaceBiomeId(currentLevel, biomeRegistry, currentPos));
        if (expectedId == null) return false;
        String expectedPrefix = expectedType + ":" + expectedId + ":";
        return currentAssignment.get().startsWith(expectedPrefix)
                && AssignedSpawnData.get(player.server).isAvailableFor(currentAssignment.get(), player.getUUID());
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

    private static List<ResourceLocation> expandValidBiomes(ServerLevel level, RaceSpawnRule rule) {
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();

        for (ResourceLocation id : rule.spawnBiomes()) {
            boolean ok = SpawnCache.isValidBiome(id);
            if (!ok) {
                TensuraRaceSpawns.LOGGER.warn("Configured biome '{}' is not registered; ignoring it", id);
                continue;
            }
            result.add(id);
        }

        for (ResourceLocation tagId : rule.spawnBiomeTags()) {
            TagKey<Biome> tagKey = TagKey.create(Registries.BIOME, tagId);
            Optional<HolderSet.Named<Biome>> tag = biomeRegistry.getTag(tagKey);
            if (tag.isEmpty()) {
                TensuraRaceSpawns.LOGGER.warn("Configured biome tag '#{}' is not registered or is empty in dimension {}; ignoring it", tagId, level.dimension().location());
                continue;
            }

            int before = result.size();
            for (Holder<Biome> holder : tag.get()) {
                ResourceLocation biomeId = biomeRegistry.getKey(holder.value());
                if (biomeId != null && SpawnCache.isValidBiome(biomeId)) {
                    result.add(biomeId);
                }
            }

            int added = result.size() - before;
            if (added == 0) {
                TensuraRaceSpawns.LOGGER.warn("Configured biome tag '#{}' did not expand to registered biome ids in dimension {}; ignoring it", tagId, level.dimension().location());
            } else {
                TensuraRaceSpawns.LOGGER.debug("Expanded biome tag '#{}' to {} biome(s) for dimension {}", tagId, added, level.dimension().location());
            }
        }

        return List.copyOf(result);
    }

    private static List<ResourceLocation> expandValidStructures(ServerLevel level, RaceSpawnRule rule) {
        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();

        for (ResourceLocation id : rule.spawnStructures()) {
            if (!SpawnCache.isValidStructure(id)) {
                TensuraRaceSpawns.LOGGER.warn("Configured structure '{}' is not registered; ignoring it", id);
                continue;
            }
            result.add(id);
        }

        for (ResourceLocation tagId : rule.spawnStructureTags()) {
            TagKey<Structure> tagKey = TagKey.create(Registries.STRUCTURE, tagId);
            Optional<HolderSet.Named<Structure>> tag = structureRegistry.getTag(tagKey);
            if (tag.isEmpty()) {
                TensuraRaceSpawns.LOGGER.warn("Configured structure tag '#{}' is not registered or is empty in dimension {}; ignoring it", tagId, level.dimension().location());
                continue;
            }

            int before = result.size();
            for (Holder<Structure> holder : tag.get()) {
                ResourceLocation structureId = structureRegistry.getKey(holder.value());
                if (structureId != null && SpawnCache.isValidStructure(structureId)) {
                    result.add(structureId);
                }
            }

            int added = result.size() - before;
            if (added == 0) {
                TensuraRaceSpawns.LOGGER.warn("Configured structure tag '#{}' did not expand to registered structure ids in dimension {}; ignoring it", tagId, level.dimension().location());
            } else {
                TensuraRaceSpawns.LOGGER.debug("Expanded structure tag '#{}' to {} structure(s) for dimension {}", tagId, added, level.dimension().location());
            }
        }

        return List.copyOf(result);
    }

    private static CompletableFuture<Optional<LocatedSpawn>> findStructureSpawnWithOptionalAsync(ServerPlayer player, ServerLevel level, List<ResourceLocation> biomeIds, List<ResourceLocation> structureIds, boolean assigned, AssignedSpawnData assignedData) {
        if (!RaceSpawnConfig.useAsyncLocator() || !AsyncLocatorCompat.isAvailable()) {
            TensuraRaceSpawns.LOGGER.debug("Using vanilla synchronous structure search because Async Locator Refined is not active");
            return CompletableFuture.completedFuture(findStructureSpawn(player, level, biomeIds, structureIds, assigned, assignedData));
        }

        Optional<CompletableFuture<Optional<LocatedSpawn>>> async = findStructureSpawnAsync(player, level, biomeIds, structureIds, assigned, assignedData);
        if (async.isPresent()) {
            return async.get();
        }

        TensuraRaceSpawns.LOGGER.warn("Async Locator Refined is installed, but its structure API was unavailable; not running synchronous structure fallback");
        return CompletableFuture.completedFuture(Optional.empty());
    }

    private static Optional<CompletableFuture<Optional<LocatedSpawn>>> findStructureSpawnAsync(ServerPlayer player, ServerLevel level, List<ResourceLocation> biomeIds, List<ResourceLocation> structureIds, boolean assigned, AssignedSpawnData assignedData) {
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

        if (!RaceSpawnConfig.useAsyncLocator()) return Optional.empty();
        Optional<CompletableFuture<Pair<BlockPos, Holder<Structure>>>> located = AsyncLocatorCompat.locateStructure(level, holderSet, base, radiusChunks, false);
        if (located.isEmpty()) return Optional.empty();

        CompletableFuture<Optional<LocatedSpawn>> future = located.get()
                .thenCompose(pair -> player.server.submit(() -> {
                    if (pair == null) {
                        TensuraRaceSpawns.LOGGER.debug(
                                "Async Locator Refined structure search returned no structure"
                        );
                        return Optional.<LocatedSpawn>empty();
                    }

                    Optional<LocatedSpawn> primary = tryStructureCandidate(
                            level,
                            player.getUUID(),
                            biomeIds,
                            assigned,
                            assignedData,
                            structureRegistry,
                            biomeRegistry,
                            base,
                            radiusBlocks,
                            pair.getFirst(),
                            pair.getSecond()
                    );

                    if (primary.isEmpty()) {
                        TensuraRaceSpawns.LOGGER.debug(
                                "Async structure candidate {} was not a valid configured spawn; not running synchronous fallback",
                                pair.getFirst()
                        );
                    }

                    return primary;
                }))
                .exceptionally(throwable -> {
                    TensuraRaceSpawns.LOGGER.warn(
                            "Async Locator Refined structure search failed; not running synchronous fallback because the async mod is present",
                            throwable
                    );
                    return Optional.empty();
                });
        return Optional.of(future);
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

            Optional<LocatedSpawn> spawn = tryStructureCandidate(level, player.getUUID(), biomeIds, assigned, assignedData, structureRegistry, biomeRegistry, base, radiusBlocks, structurePos, nearest.getSecond());
            if (spawn.isPresent()) return spawn;
        }
        return Optional.empty();
    }

    private static Optional<LocatedSpawn> tryStructureCandidate(ServerLevel level, UUID playerId, List<ResourceLocation> biomeIds, boolean assigned, AssignedSpawnData assignedData, Registry<Structure> structureRegistry, Registry<Biome> biomeRegistry, BlockPos base, int radiusBlocks, BlockPos structurePos, Holder<Structure> structureHolder) {
        if (Math.abs(structurePos.getX() - base.getX()) > radiusBlocks || Math.abs(structurePos.getZ() - base.getZ()) > radiusBlocks) return Optional.empty();

        ResourceLocation structureId = structureRegistry.getKey(structureHolder.value());
        if (structureId == null) return Optional.empty();

        Optional<BlockPos> safe = findValidSpawn(level, structurePos, SAFE_POS_RANGE,
                pos -> matchesConfiguredSurfaceBiome(level, biomeRegistry, pos, biomeIds));
        if (safe.isEmpty()) return Optional.empty();

        ResourceLocation spawnBiome = biomeIds.isEmpty()
                ? getSurfaceBiomeId(level, biomeRegistry, safe.get())
                : getMatchingSurfaceBiomeId(level, biomeRegistry, safe.get(), biomeIds).orElse(null);
        if (spawnBiome == null) return Optional.empty();
        if (!biomeIds.isEmpty() && !biomeIds.contains(spawnBiome)) return Optional.empty();

        String assignmentKey = assignmentKey("structure", structureId, structurePos);
        if (assigned && !assignedData.isAvailableFor(assignmentKey, playerId)) return Optional.empty();
        return Optional.of(new LocatedSpawn(level, safe.get(), assignmentKey, spawnBiome, structureId));
    }


    private static CompletableFuture<Optional<LocatedSpawn>> findBiomeSpawnWithOptionalAsync(ServerPlayer player, ServerLevel level, List<ResourceLocation> biomeIds, boolean assigned, AssignedSpawnData assignedData) {
        if (!RaceSpawnConfig.useAsyncLocator() || !AsyncLocatorCompat.isAvailable()) {
            TensuraRaceSpawns.LOGGER.debug("Using vanilla synchronous biome search because Async Locator Refined is not active");
            return CompletableFuture.completedFuture(findBiomeSpawn(player, level, biomeIds, assigned, assignedData));
        }

        Optional<CompletableFuture<Optional<LocatedSpawn>>> async = findBiomeSpawnAsync(player, level, biomeIds, assigned, assignedData);
        if (async.isPresent()) {
            return async.get();
        }

        TensuraRaceSpawns.LOGGER.warn("Async Locator Refined is installed, but its biome API was unavailable; not running synchronous biome fallback");
        return CompletableFuture.completedFuture(Optional.empty());
    }

    private static Optional<CompletableFuture<Optional<LocatedSpawn>>> findBiomeSpawnAsync(ServerPlayer player, ServerLevel level, List<ResourceLocation> biomeIds, boolean assigned, AssignedSpawnData assignedData) {
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        int radius = RaceSpawnConfig.spawnRadius();
        BlockPos base = level.getSharedSpawnPos();

        Optional<CompletableFuture<Pair<BlockPos, Holder<Biome>>>> located = AsyncLocatorCompat.locateBiome(level, biomeRegistry, biomeIds, base, radius, BIOME_SAMPLE_STEP, 64);
        if (located.isEmpty()) return Optional.empty();

        ArrayList<BlockPos> retryCenters = new ArrayList<>();
        for (BlockPos center : spiral(base, radius, ASYNC_BIOME_RETRY_CENTER_STEP)) {
            if (center.equals(base)) continue;
            retryCenters.add(center);
            if (retryCenters.size() >= ASYNC_BIOME_MAX_RETRIES) break;
        }

        CompletableFuture<Optional<LocatedSpawn>> future = located.get()
                .handle((pair, throwable) -> player.server.submit(() -> {
                    if (throwable != null) {
                        TensuraRaceSpawns.LOGGER.warn("Async Locator Refined biome search failed; not running synchronous fallback because the async mod is present", throwable);
                        return CompletableFuture.completedFuture(Optional.<LocatedSpawn>empty());
                    }

                    if (pair != null) {
                        Optional<LocatedSpawn> primary = tryBiomeCandidate(level, player.getUUID(), biomeRegistry, biomeIds, assigned, assignedData, pair.getFirst());
                        if (primary.isPresent()) return CompletableFuture.completedFuture(primary);

                        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pair.getFirst());
                        TensuraRaceSpawns.LOGGER.debug(
                                "Async biome locate returned {} / surface {}, but it was not a valid surface spawn; continuing async biome search",
                                pair.getFirst(),
                                surface
                        );
                    }

                    return continueAsyncBiomeSearch(player, level, biomeRegistry, biomeIds, assigned, assignedData, retryCenters, 0, new HashSet<>());
                }))
                .thenCompose(java.util.function.Function.identity())
                .thenCompose(java.util.function.Function.identity());
        return Optional.of(future);
    }

    private static CompletableFuture<Optional<LocatedSpawn>> continueAsyncBiomeSearch(ServerPlayer player, ServerLevel level, Registry<Biome> biomeRegistry, List<ResourceLocation> biomeIds, boolean assigned, AssignedSpawnData assignedData, List<BlockPos> centers, int index, Set<Long> triedBiomeColumns) {
        if (index >= centers.size()) {
            TensuraRaceSpawns.LOGGER.debug("Async biome search exhausted {} retry centers without a valid spawn", centers.size());
            return CompletableFuture.completedFuture(Optional.empty());
        }

        BlockPos center = centers.get(index);
        Optional<CompletableFuture<Pair<BlockPos, Holder<Biome>>>> located = AsyncLocatorCompat.locateBiome(level, biomeRegistry, biomeIds, center, RaceSpawnConfig.spawnRadius(), BIOME_SAMPLE_STEP, 64);
        if (located.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return located.get()
                .handle((pair, throwable) -> player.server.submit(() -> {
                    if (throwable != null) {
                        TensuraRaceSpawns.LOGGER.warn("Async Locator Refined biome retry failed; not running synchronous fallback because the async mod is present", throwable);
                        return CompletableFuture.completedFuture(Optional.<LocatedSpawn>empty());
                    }

                    if (pair != null) {
                        BlockPos found = pair.getFirst();
                        long packed = ChunkPos.asLong(found.getX() >> 4, found.getZ() >> 4);
                        if (triedBiomeColumns.add(packed)) {
                            Optional<LocatedSpawn> spawn = tryBiomeCandidate(level, player.getUUID(), biomeRegistry, biomeIds, assigned, assignedData, found);
                            if (spawn.isPresent()) return CompletableFuture.completedFuture(spawn);

                            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, found);
                            TensuraRaceSpawns.LOGGER.debug(
                                    "Async biome retry located {} / surface {}, but no valid spawn was found there; continuing",
                                    found,
                                    surface
                            );
                        }
                    }

                    return continueAsyncBiomeSearch(player, level, biomeRegistry, biomeIds, assigned, assignedData, centers, index + 1, triedBiomeColumns);
                }))
                .thenCompose(java.util.function.Function.identity())
                .thenCompose(java.util.function.Function.identity());
    }

    private static Optional<LocatedSpawn> findBiomeSpawn(ServerPlayer player, ServerLevel level, List<ResourceLocation> biomeIds, boolean assigned, AssignedSpawnData assignedData) {
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        int radius = RaceSpawnConfig.spawnRadius();
        BlockPos base = level.getSharedSpawnPos();
        Predicate<Holder<Biome>> biomePredicate = holder -> biomeIds.contains(biomeRegistry.getKey(holder.value()));

        Pair<BlockPos, Holder<Biome>> closest = level.findClosestBiome3d(biomePredicate, base, radius, BIOME_SAMPLE_STEP, 64);
        if (closest != null) {
            Optional<LocatedSpawn> spawn = tryBiomeCandidate(level, player.getUUID(), biomeRegistry, biomeIds, assigned, assignedData, closest.getFirst());
            if (spawn.isPresent()) return spawn;

            TensuraRaceSpawns.LOGGER.debug(
                    "Located biome candidate at {} for {}, but it was not a valid surface spawn; continuing surface scan",
                    closest.getFirst(), biomeIds
            );
        }

        // Fallback path: scan surface columns with the same horizontal resolution as /locate biome.
        // This is important for modded worldgen: findClosestBiome3d may return an underground/3D biome sample,
        // while we need a safe top-block spawn in a matching surface biome.
        return findSurfaceBiomeSpawn(level, player.getUUID(), biomeRegistry, biomeIds, assigned, assignedData, base, radius);
    }

    private static Optional<LocatedSpawn> findSurfaceBiomeSpawn(ServerLevel level, UUID playerId, Registry<Biome> biomeRegistry, List<ResourceLocation> biomeIds, boolean assigned, AssignedSpawnData assignedData, BlockPos base, int radius) {
        for (BlockPos candidate : spiral(base, radius, BIOME_SAMPLE_STEP)) {
            if (!matchesConfiguredSurfaceBiome(level, biomeRegistry, candidate, biomeIds)) continue;
            Optional<LocatedSpawn> spawn = tryBiomeCandidate(level, playerId, biomeRegistry, biomeIds, assigned, assignedData, candidate);
            if (spawn.isPresent()) return spawn;
        }
        return Optional.empty();
    }

    private static Optional<LocatedSpawn> tryBiomeCandidate(ServerLevel level, UUID playerId, Registry<Biome> biomeRegistry, List<ResourceLocation> biomeIds, boolean assigned, AssignedSpawnData assignedData, BlockPos raw) {
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, raw);
        Optional<BlockPos> safe = findValidSpawn(level, surface, SAFE_POS_RANGE,
                pos -> matchesConfiguredSurfaceBiome(level, biomeRegistry, pos, biomeIds));
        if (safe.isEmpty()) {
            TensuraRaceSpawns.LOGGER.debug(
                    "Located biome candidate at {} / surface {}, but no vanilla-like player spawn column was accepted nearby",
                    raw,
                    surface
            );
            return Optional.empty();
        }

        ResourceLocation biome = getMatchingSurfaceBiomeId(level, biomeRegistry, safe.get(), biomeIds).orElse(null);
        if (biome == null || !biomeIds.contains(biome)) {
            TensuraRaceSpawns.LOGGER.debug(
                    "Rejected biome spawn at {} because final surface biome {} is not in {}",
                    safe.get(),
                    biome,
                    biomeIds
            );
            return Optional.empty();
        }

        String assignmentKey = assignmentKey("biome", biome, safe.get());
        if (assigned && !assignedData.isAvailableFor(assignmentKey, playerId)) {
            TensuraRaceSpawns.LOGGER.debug("Rejected biome spawn at {} because assignment key {} is already used", safe.get(), assignmentKey);
            return Optional.empty();
        }
        return Optional.of(new LocatedSpawn(level, safe.get(), assignmentKey, biome, null));
    }


    private static Optional<BlockPos> findValidSpawn(ServerLevel level, BlockPos startPos, int range, Predicate<BlockPos> extraCheck) {
        Optional<BlockPos> exactColumn = findTopValidSpawn(level, startPos.getX(), startPos.getZ(), extraCheck);
        if (exactColumn.isPresent()) return exactColumn;

        // Dense scan around the located biome/structure position. A 4-block step can skip perfectly
        // valid player-spawn columns on modded terrain, especially on cliffs, beaches and custom top blocks.
        for (BlockPos candidate : spiral(startPos, Math.min(range, 24), 1)) {
            if (candidate.getX() == startPos.getX() && candidate.getZ() == startPos.getZ()) continue;
            Optional<BlockPos> top = findTopValidSpawn(level, candidate.getX(), candidate.getZ(), extraCheck);
            if (top.isPresent()) return top;
        }

        for (BlockPos candidate : spiral(startPos, range, 4)) {
            if (Math.abs(candidate.getX() - startPos.getX()) <= 24 && Math.abs(candidate.getZ() - startPos.getZ()) <= 24) continue;
            Optional<BlockPos> top = findTopValidSpawn(level, candidate.getX(), candidate.getZ(), extraCheck);
            if (top.isPresent()) return top;
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> findTopValidSpawn(ServerLevel level, int x, int z, Predicate<BlockPos> extraCheck) {
        BlockPos column = new BlockPos(x, level.getMinBuildHeight(), z);
        if (!level.getWorldBorder().isWithinBounds(column)) return Optional.empty();

        int minY = level.getMinBuildHeight();
        int maxFeetY = level.getMaxBuildHeight() - 2; // feet + head must both be inside build height

        // Heightmap is still useful as a fast path, but it must not be trusted as the only source of
        // surface height. Worldgen mods such as Tectonic may change the vertical build range or terrain
        // after vanilla-like heightmap assumptions stop being reliable for our locate result.
        BlockPos heightmapFeet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        Optional<BlockPos> accepted = tryHeightmapWindow(level, x, z, heightmapFeet, minY, maxFeetY, extraCheck);
        if (accepted.isPresent()) {
            BlockPos feet = accepted.get();
            TensuraRaceSpawns.LOGGER.debug(
                    "Accepted spawn column x={} z={} by heightmap window heightmapFeetY={} finalFeetY={}",
                    x,
                    z,
                    heightmapFeet.getY(),
                    feet.getY()
            );
            level.getChunkSource().addRegionTicket(TicketType.START, new ChunkPos(feet), 11, Unit.INSTANCE);
            return accepted;
        }

        accepted = scanColumnForSurfaceSpawn(level, x, z, minY, maxFeetY, extraCheck);
        if (accepted.isPresent()) {
            BlockPos feet = accepted.get();
            TensuraRaceSpawns.LOGGER.debug(
                    "Accepted spawn column x={} z={} by top-down column scan heightmapFeetY={} finalFeetY={}",
                    x,
                    z,
                    heightmapFeet.getY(),
                    feet.getY()
            );
            level.getChunkSource().addRegionTicket(TicketType.START, new ChunkPos(feet), 11, Unit.INSTANCE);
            return accepted;
        }

        TensuraRaceSpawns.LOGGER.debug(
                "Rejected spawn column x={} z={} heightmapFeetY={} minY={} maxFeetY={}: no valid feet position found by heightmap window or top-down scan",
                x,
                z,
                heightmapFeet.getY(),
                minY,
                maxFeetY
        );
        return Optional.empty();
    }

    private static Optional<BlockPos> tryHeightmapWindow(ServerLevel level, int x, int z, BlockPos heightmapFeet, int minY, int maxFeetY, Predicate<BlockPos> extraCheck) {
        if (heightmapFeet.getY() < minY || heightmapFeet.getY() > maxFeetY + 1) return Optional.empty();

        BlockPos surfaceBlock = heightmapFeet.below();
        for (int dy = 1; dy >= -7; dy--) {
            BlockPos feet = new BlockPos(x, surfaceBlock.getY() + dy, z);
            Optional<BlockPos> accepted = trySpawnFeet(level, feet, minY, maxFeetY, extraCheck);
            if (accepted.isPresent()) return accepted;
        }

        for (int dy = 1; dy >= -3; dy--) {
            BlockPos feet = new BlockPos(x, heightmapFeet.getY() + dy, z);
            Optional<BlockPos> accepted = trySpawnFeet(level, feet, minY, maxFeetY, extraCheck);
            if (accepted.isPresent()) return accepted;
        }

        return Optional.empty();
    }

    private static Optional<BlockPos> scanColumnForSurfaceSpawn(ServerLevel level, int x, int z, int minY, int maxFeetY, Predicate<BlockPos> extraCheck) {
        for (int y = maxFeetY; y > minY; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            Optional<BlockPos> accepted = trySpawnFeet(level, feet, minY, maxFeetY, extraCheck);
            if (accepted.isPresent()) return accepted;
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> trySpawnFeet(ServerLevel level, BlockPos feet, int minY, int maxFeetY, Predicate<BlockPos> extraCheck) {
        if (feet.getY() <= minY || feet.getY() > maxFeetY) return Optional.empty();
        if (!level.getWorldBorder().isWithinBounds(feet)) return Optional.empty();

        if (!hasVanillaLikeSpawnSpace(level, feet)) return Optional.empty();
        if (!extraCheck.test(feet)) return Optional.empty();

        return Optional.of(feet);
    }


    private static boolean matchesConfiguredSurfaceBiome(ServerLevel level, Registry<Biome> biomeRegistry, BlockPos pos, List<ResourceLocation> biomeIds) {
        if (biomeIds.isEmpty()) return true;
        return getMatchingSurfaceBiomeId(level, biomeRegistry, pos, biomeIds).isPresent();
    }

    private static Optional<ResourceLocation> getMatchingSurfaceBiomeId(ServerLevel level, Registry<Biome> biomeRegistry, BlockPos pos, List<ResourceLocation> biomeIds) {
        ResourceLocation id = getBiomeId(level, biomeRegistry, level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos));
        if (id != null && biomeIds.contains(id)) return Optional.of(id);

        BlockPos clamped = clampToBuildHeight(level, pos);
        id = getBiomeId(level, biomeRegistry, clamped);
        if (id != null && biomeIds.contains(id)) return Optional.of(id);

        if (clamped.getY() > level.getMinBuildHeight()) {
            id = getBiomeId(level, biomeRegistry, clamped.below());
            if (id != null && biomeIds.contains(id)) return Optional.of(id);
        }

        return Optional.empty();
    }

    private static ResourceLocation getSurfaceBiomeId(ServerLevel level, Registry<Biome> biomeRegistry, BlockPos pos) {
        ResourceLocation id = getBiomeId(level, biomeRegistry, level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos));
        if (id != null) return id;
        return getBiomeId(level, biomeRegistry, clampToBuildHeight(level, pos));
    }

    private static ResourceLocation getBiomeId(ServerLevel level, Registry<Biome> biomeRegistry, BlockPos pos) {
        return biomeRegistry.getKey(level.getBiome(pos).value());
    }

    private static BlockPos clampToBuildHeight(ServerLevel level, BlockPos pos) {
        int y = Math.max(level.getMinBuildHeight(), Math.min(pos.getY(), level.getMaxBuildHeight() - 1));
        return pos.getY() == y ? pos : new BlockPos(pos.getX(), y, pos.getZ());
    }

    private static boolean hasVanillaLikeSpawnSpace(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        BlockPos head = feet.above();

        if (!level.getFluidState(below).isEmpty()) return false;
        if (!level.getFluidState(feet).isEmpty()) return false;
        if (!level.getFluidState(head).isEmpty()) return false;

        BlockState belowState = level.getBlockState(below);
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(head);

        if (belowState.getCollisionShape(level, below).isEmpty()) return false;
        if (!feetState.getCollisionShape(level, feet).isEmpty()) return false;
        return headState.getCollisionShape(level, head).isEmpty();
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
