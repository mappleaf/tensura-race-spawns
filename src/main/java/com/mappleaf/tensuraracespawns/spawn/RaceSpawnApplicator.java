package com.mappleaf.tensuraracespawns.spawn;

import io.github.manasmods.manascore.race.api.ManasRace;
import com.mappleaf.tensuraracespawns.TensuraRaceSpawns;
import com.mappleaf.tensuraracespawns.config.RaceSpawnConfig;
import com.mappleaf.tensuraracespawns.config.RaceSpawnRule;
import com.mappleaf.tensuraracespawns.data.AssignedSpawnData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

public final class RaceSpawnApplicator {
    private RaceSpawnApplicator() {}

    public static void afterRaceSelected(ServerPlayer player, ManasRace race) {
        if (AssignedSpawnData.hasInitialSpawnApplied(player)) return;
        ResourceLocation raceId = race.getRegistryName();
        Optional<RaceSpawnRule> ruleOptional = RaceSpawnConfig.getRule(raceId);
        if (ruleOptional.isEmpty()) {
            AssignedSpawnData.markInitialSpawnApplied(player);
            return;
        }

        RaceSpawnRule rule = ruleOptional.get();
        Optional<LocatedSpawn> located = RaceSpawnFinder.find(player, rule);
        if (located.isEmpty()) {
            TensuraRaceSpawns.LOGGER.warn("Could not find configured spawn for race {} within radius {}; using default world spawn", raceId, RaceSpawnConfig.spawnRadius());
            located = Optional.of(new LocatedSpawn(player.server.overworld(), player.server.overworld().getSharedSpawnPos(), null, null, null));
        }

        LocatedSpawn spawn = located.get();
        BlockPos pos = spawn.pos();
        player.teleportTo(spawn.level(), pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, player.getYRot(), player.getXRot());
        player.setRespawnPosition(spawn.level().dimension(), pos, player.getYRot(), false, false);

        if (!rule.onlyInitial()) {
            AssignedSpawnData.saveFallback(player, spawn.level(), pos);
        }
        if (rule.assignedToPlayer() && spawn.assignmentKey() != null) {
            AssignedSpawnData.get(player.server).assign(player, spawn.assignmentKey());
        }
        AssignedSpawnData.markInitialSpawnApplied(player);
        TensuraRaceSpawns.LOGGER.info("Applied configured spawn for player {} race {} at {} {}", player.getGameProfile().getName(), raceId, spawn.level().dimension().location(), pos);
    }
}
