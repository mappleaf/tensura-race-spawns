package com.mappleaf.tensuraracespawns.event;

import com.mappleaf.tensuraracespawns.TensuraRaceSpawns;
import com.mappleaf.tensuraracespawns.data.AssignedSpawnData;
import com.mappleaf.tensuraracespawns.spawn.RaceSpawnApplicator;
import io.github.manasmods.manascore.race.api.RaceAPI;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Removes spawn records owned by this addon only when the player's Tensura race is fully cleared.
 *
 * A non-empty race id that differs from the originally applied race is reconciled lazily.
 * The configured spawn is kept when it is still valid for the new race, and recalculated only when needed.
 */
public final class RaceResetCleanupEvents {
    private RaceResetCleanupEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % 20 != 0) return;
        cleanupIfRaceWasCleared(player);
    }

    public static void cleanupIfRaceWasCleared(ServerPlayer player) {
        Optional<ResourceLocation> appliedRaceId = AssignedSpawnData.getAppliedRaceId(player);
        if (appliedRaceId.isEmpty()) return;

        RaceLookupResult currentRace = getCurrentRaceId(player);
        if (!currentRace.resolved()) return;

        if (currentRace.raceId().isEmpty()) {
            AssignedSpawnData.clearSpawnRecords(player, "race has been cleared");
            return;
        }

        if (!appliedRaceId.get().equals(currentRace.raceId().get())) {
            RaceSpawnApplicator.reconcileRaceChange(player, currentRace.raceId().get(), "race changed outside reincarnation menu");
        }
    }

    private static RaceLookupResult getCurrentRaceId(ServerPlayer player) {
        Optional<?> currentRace = RaceAPI.getRaceFrom((LivingEntity) player).getRace();
        if (currentRace.isEmpty()) return RaceLookupResult.resolved(Optional.empty());

        Object raceInstance = currentRace.get();
        Optional<ResourceLocation> id = tryRegistryName(raceInstance);
        if (id.isPresent()) return RaceLookupResult.resolved(id);

        Optional<Object> baseRace = invokeNoArg(raceInstance, "getRace");
        if (baseRace.isPresent()) {
            id = tryRegistryName(baseRace.get());
            if (id.isPresent()) return RaceLookupResult.resolved(id);
        }

        TensuraRaceSpawns.LOGGER.debug("Could not resolve current race id for player {}; keeping configured spawn records", player.getGameProfile().getName());
        return RaceLookupResult.unresolved();
    }

    private static Optional<ResourceLocation> tryRegistryName(Object object) {
        Optional<Object> value = invokeNoArg(object, "getRegistryName");
        if (value.isEmpty()) return Optional.empty();
        if (value.get() instanceof ResourceLocation id) return Optional.of(id);
        if (value.get() instanceof String id) return Optional.ofNullable(ResourceLocation.tryParse(id));
        return Optional.empty();
    }

    private static Optional<Object> invokeNoArg(Object object, String methodName) {
        try {
            Method method = object.getClass().getMethod(methodName);
            return Optional.ofNullable(method.invoke(object));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private record RaceLookupResult(boolean resolved, Optional<ResourceLocation> raceId) {
        private static RaceLookupResult resolved(Optional<ResourceLocation> raceId) {
            return new RaceLookupResult(true, raceId);
        }

        private static RaceLookupResult unresolved() {
            return new RaceLookupResult(false, Optional.empty());
        }
    }
}
