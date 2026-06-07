package com.mappleaf.tensuraracespawns.spawn;

import io.github.manasmods.manascore.race.api.ManasRace;
import com.mappleaf.tensuraracespawns.TensuraRaceSpawns;
import com.mappleaf.tensuraracespawns.config.RaceSpawnConfig;
import com.mappleaf.tensuraracespawns.config.RaceSpawnRule;
import com.mappleaf.tensuraracespawns.data.AssignedSpawnData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RaceSpawnApplicator {
    private static final AtomicLong SEARCH_TOKEN = new AtomicLong();
    private static final Map<UUID, PendingSearch> PENDING_SEARCHES = new ConcurrentHashMap<>();

    private RaceSpawnApplicator() {}

    public static void afterRaceSelected(ServerPlayer player, ManasRace race) {
        ResourceLocation raceId = race.getRegistryName();

        if (AssignedSpawnData.hasInitialSpawnApplied(player)) {
            reconcileRaceChange(player, raceId, "race selection");
            return;
        }

        Optional<RaceSpawnRule> ruleOptional = RaceSpawnConfig.getRule(raceId);
        if (ruleOptional.isEmpty()) {
            AssignedSpawnData.markInitialSpawnApplied(player, raceId);
            return;
        }

        startSearch(player, raceId, ruleOptional.get(), "race selection");
    }


    public static void reconcileRaceChange(ServerPlayer player, ResourceLocation currentRaceId, String reason) {
        Optional<ResourceLocation> appliedRaceId = AssignedSpawnData.getAppliedRaceId(player);
        if (appliedRaceId.isEmpty()) return;
        if (appliedRaceId.get().equals(currentRaceId)) return;
        if (PENDING_SEARCHES.containsKey(player.getUUID())) return;

        Optional<RaceSpawnRule> newRule = RaceSpawnConfig.getRule(currentRaceId);
        if (newRule.isEmpty()) {
            TensuraRaceSpawns.LOGGER.info(
                    "Player {} race changed from {} to {}, but the new race has no configured spawn rule; clearing managed spawn records",
                    player.getGameProfile().getName(),
                    appliedRaceId.get(),
                    currentRaceId
            );
            AssignedSpawnData.clearSpawnRecords(player, "race changed to unconfigured race " + currentRaceId);
            AssignedSpawnData.markInitialSpawnApplied(player, currentRaceId);
            return;
        }

        if (RaceSpawnFinder.isCurrentRespawnValidForRule(player, newRule.get())) {
            if (!newRule.get().assignedToPlayer()) {
                AssignedSpawnData.clearAssignedSpawnKey(player);
            }
            AssignedSpawnData.updateAppliedRaceId(player, currentRaceId);
            TensuraRaceSpawns.LOGGER.debug(
                    "Player {} race changed from {} to {}, but current configured spawn is still valid; keeping it",
                    player.getGameProfile().getName(),
                    appliedRaceId.get(),
                    currentRaceId
            );
            return;
        }

        TensuraRaceSpawns.LOGGER.info(
                "Player {} race changed from {} to {}; current configured spawn is not valid for the new race, recalculating ({})",
                player.getGameProfile().getName(),
                appliedRaceId.get(),
                currentRaceId,
                reason
        );
        AssignedSpawnData.clearSpawnRecords(player, "race changed to " + currentRaceId + " and current spawn is invalid");
        startSearch(player, currentRaceId, newRule.get(), reason);
    }
    public static void restartActiveSearches(MinecraftServer server) {
        ArrayList<PendingSearch> active = new ArrayList<>(PENDING_SEARCHES.values());
        if (active.isEmpty()) return;

        TensuraRaceSpawns.LOGGER.info("Restarting {} active race spawn search task(s) after config reload", active.size());

        for (PendingSearch pending : active) {
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId());
            if (player == null || AssignedSpawnData.hasInitialSpawnApplied(player)) {
                PENDING_SEARCHES.remove(pending.playerId(), pending);
                continue;
            }

            RaceSpawnRule rule = RaceSpawnConfig.getRule(pending.raceId()).orElse(pending.rule());
            startSearch(player, pending.raceId(), rule, "config reload");
        }
    }

    private static void startSearch(ServerPlayer player, ResourceLocation raceId, RaceSpawnRule rule, String reason) {
        long token = SEARCH_TOKEN.incrementAndGet();
        PendingSearch pending = new PendingSearch(player.getUUID(), raceId, rule, token);
        PENDING_SEARCHES.put(player.getUUID(), pending);

        SpawnSearchLock.lock(player);
        TensuraRaceSpawns.LOGGER.debug(
                "Starting configured spawn search {} for player {} race {} ({})",
                token,
                player.getGameProfile().getName(),
                raceId,
                reason
        );

        try {
            RaceSpawnFinder.findAsync(player, rule).whenComplete((located, throwable) -> player.server.submit(() -> {
                PendingSearch current = PENDING_SEARCHES.get(player.getUUID());
                if (current == null || current.token() != token) {
                    TensuraRaceSpawns.LOGGER.debug(
                            "Ignoring obsolete configured spawn search {} for player {} race {}",
                            token,
                            player.getGameProfile().getName(),
                            raceId
                    );
                    return;
                }

                try {
                    if (throwable != null) {
                        TensuraRaceSpawns.LOGGER.error("Could not find configured spawn for race {}; using default world spawn", raceId, throwable);
                        apply(player, raceId, rule, Optional.empty());
                        return;
                    }
                    apply(player, raceId, rule, located == null ? Optional.empty() : located);
                } finally {
                    PENDING_SEARCHES.remove(player.getUUID(), current);
                    SpawnSearchLock.unlock(player);
                }
            }));
        } catch (Throwable t) {
            PendingSearch current = PENDING_SEARCHES.get(player.getUUID());
            if (current != null && current.token() == token) {
                try {
                    TensuraRaceSpawns.LOGGER.error("Could not start configured spawn search for race {}; using default world spawn", raceId, t);
                    apply(player, raceId, rule, Optional.empty());
                } finally {
                    PENDING_SEARCHES.remove(player.getUUID(), current);
                    SpawnSearchLock.unlock(player);
                }
            }
        }
    }

    private static void apply(ServerPlayer player, ResourceLocation raceId, RaceSpawnRule rule, Optional<LocatedSpawn> located) {
        if (AssignedSpawnData.hasInitialSpawnApplied(player)) return;
        if (located.isEmpty()) {
            TensuraRaceSpawns.LOGGER.warn("Could not find configured spawn for race {} within radius {}; using default world spawn", raceId, RaceSpawnConfig.spawnRadius());
            located = Optional.of(new LocatedSpawn(player.server.overworld(), player.server.overworld().getSharedSpawnPos(), null, null, null));
        }

        LocatedSpawn spawn = located.get();
        BlockPos pos = spawn.pos();
        player.teleportTo(spawn.level(), pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, player.getYRot(), player.getXRot());
        player.setRespawnPosition(spawn.level().dimension(), pos, player.getYRot(), false, false);
        AssignedSpawnData.markManagedRespawnPoint(player);

        if (!rule.onlyInitial()) {
            AssignedSpawnData.saveFallback(player, spawn.level(), pos);
        }
        AssignedSpawnData.saveAppliedSpawnMetadata(player, spawn.matchedBiome(), spawn.matchedStructure());
        if (rule.assignedToPlayer() && spawn.assignmentKey() != null) {
            AssignedSpawnData.get(player.server).assign(player, spawn.assignmentKey());
        }
        AssignedSpawnData.markInitialSpawnApplied(player, raceId);
        TensuraRaceSpawns.LOGGER.info("Applied configured spawn for player {} race {} at {} {}", player.getGameProfile().getName(), raceId, spawn.level().dimension().location(), pos);
    }

    private record PendingSearch(UUID playerId, ResourceLocation raceId, RaceSpawnRule rule, long token) {}
}
