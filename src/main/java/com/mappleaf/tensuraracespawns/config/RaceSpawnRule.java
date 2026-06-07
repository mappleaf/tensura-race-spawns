package com.mappleaf.tensuraracespawns.config;

import net.minecraft.resources.ResourceLocation;
import java.util.List;

public record RaceSpawnRule(
        String spawnDimension,
        List<ResourceLocation> spawnBiomes,
        List<ResourceLocation> spawnBiomeTags,
        List<ResourceLocation> spawnStructures,
        List<ResourceLocation> spawnStructureTags,
        boolean onlyInitial,
        boolean assignedToPlayer
) {
    public boolean isVanillaWorldSpawn() {
        return spawnDimension.isBlank() && !hasBiomeFilter() && !hasStructureFilter();
    }

    public boolean hasBiomeFilter() {
        return !spawnBiomes.isEmpty() || !spawnBiomeTags.isEmpty();
    }

    public boolean hasStructureFilter() {
        return !spawnStructures.isEmpty() || !spawnStructureTags.isEmpty();
    }
}
