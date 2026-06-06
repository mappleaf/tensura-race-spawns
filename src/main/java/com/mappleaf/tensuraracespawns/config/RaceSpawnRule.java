package com.mappleaf.tensuraracespawns.config;

import net.minecraft.resources.ResourceLocation;
import java.util.List;

public record RaceSpawnRule(
        String spawnDimension,
        List<ResourceLocation> spawnBiomes,
        List<ResourceLocation> spawnStructures,
        boolean onlyInitial,
        boolean assignedToPlayer
) {
    public boolean isVanillaWorldSpawn() {
        return spawnDimension.isBlank() && spawnBiomes.isEmpty() && spawnStructures.isEmpty();
    }

    public boolean hasBiomeFilter() {
        return !spawnBiomes.isEmpty();
    }

    public boolean hasStructureFilter() {
        return !spawnStructures.isEmpty();
    }
}
