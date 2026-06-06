package com.mappleaf.tensuraracespawns.event;

import com.mappleaf.tensuraracespawns.data.AssignedSpawnData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;

import java.util.Optional;

public final class RespawnFallbackEvents {
    private RespawnFallbackEvents() {}

    @SubscribeEvent
    public static void onRespawnPosition(PlayerRespawnPositionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.getOriginalDimensionTransition().missingRespawnBlock()) return;

        Optional<AssignedSpawnData.FallbackSpawn> fallback = AssignedSpawnData.getFallback(player);
        if (fallback.isEmpty()) return;

        AssignedSpawnData.FallbackSpawn stored = fallback.get();
        ResourceLocation dimensionId = ResourceLocation.tryParse(stored.dimension());
        if (dimensionId == null) return;
        ServerLevel level = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (level == null) return;

        BlockPos pos = new BlockPos(stored.x(), stored.y(), stored.z());
        Vec3 vec = Vec3.atBottomCenterOf(pos);
        event.setDimensionTransition(new DimensionTransition(level, vec, Vec3.ZERO, player.getYRot(), player.getXRot(), false, DimensionTransition.DO_NOTHING));
        event.setCopyOriginalSpawnPosition(false);
    }
}
