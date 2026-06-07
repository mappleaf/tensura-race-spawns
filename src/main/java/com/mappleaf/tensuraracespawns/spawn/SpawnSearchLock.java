package com.mappleaf.tensuraracespawns.spawn;

import com.mappleaf.tensuraracespawns.TensuraRaceSpawns;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds a player in place while a configured spawn point is being searched.
 * Blindness is refreshed every tick, so normal effect removal does not let the player see while locked.
 */
public final class SpawnSearchLock {
    private static final int BLINDNESS_TICKS = 80;
    private static final Map<UUID, LockedPlayer> LOCKED = new ConcurrentHashMap<>();

    private SpawnSearchLock() {}

    public static void lock(ServerPlayer player) {
        LOCKED.put(player.getUUID(), new LockedPlayer(
                player.serverLevel().dimension().location().toString(),
                player.position(),
                player.getYRot(),
                player.getXRot(),
                player.getAbilities().invulnerable
        ));
        setTemporaryInvulnerable(player, true);
        applyBlindness(player);
        freeze(player);
        TensuraRaceSpawns.LOGGER.debug("Locked player {} while searching configured race spawn", player.getGameProfile().getName());
    }

    public static void unlock(ServerPlayer player) {
        LockedPlayer lock = LOCKED.remove(player.getUUID());
        if (lock != null) {
            setTemporaryInvulnerable(player, lock.wasInvulnerable());
        }
        player.removeEffect(MobEffects.BLINDNESS);
        TensuraRaceSpawns.LOGGER.debug("Unlocked player {} after configured race spawn search", player.getGameProfile().getName());
    }

    public static boolean isLocked(ServerPlayer player) {
        return LOCKED.containsKey(player.getUUID());
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LockedPlayer lock = LOCKED.get(player.getUUID());
        if (lock == null) return;

        setTemporaryInvulnerable(player, true);
        applyBlindness(player);
        freeze(player);

        ServerLevel level = player.serverLevel();
        if (!level.dimension().location().toString().equals(lock.dimensionId)) return;

        boolean moved = player.position().distanceToSqr(lock.position) > 0.0001D;
        boolean rotated = Math.abs(player.getYRot() - lock.yRot) > 0.01F || Math.abs(player.getXRot() - lock.xRot) > 0.01F;
        if (moved || rotated) {
            player.teleportTo(level, lock.position.x, lock.position.y, lock.position.z, lock.yRot, lock.xRot);
        }

        player.setYRot(lock.yRot);
        player.setXRot(lock.xRot);
        player.yHeadRot = lock.yRot;
        player.yBodyRot = lock.yRot;
        player.yHeadRotO = lock.yRot;
        player.yBodyRotO = lock.yRot;
    }

    @SubscribeEvent
    public static void onMobEffectRemove(MobEffectEvent.Remove event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!isLocked(player)) return;
        if (!MobEffects.BLINDNESS.equals(event.getEffect())) return;
        event.setCanceled(true);
    }


    private static void setTemporaryInvulnerable(ServerPlayer player, boolean invulnerable) {
        if (player.getAbilities().invulnerable == invulnerable) return;
        player.getAbilities().invulnerable = invulnerable;
        player.onUpdateAbilities();
    }

    private static void applyBlindness(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLINDNESS_TICKS, 0, false, false, false));
    }

    private static void freeze(ServerPlayer player) {
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
    }

    private record LockedPlayer(String dimensionId, Vec3 position, float yRot, float xRot, boolean wasInvulnerable) {}
}
