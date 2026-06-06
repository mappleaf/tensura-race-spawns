package com.mappleaf.tensuraracespawns.spawn;

import com.mappleaf.tensuraracespawns.TensuraRaceSpawns;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.HashSet;
import java.util.Set;

public final class SpawnCache {
    private static final Set<ResourceLocation> VALID_BIOMES = new HashSet<>();
    private static final Set<ResourceLocation> VALID_STRUCTURES = new HashSet<>();

    private SpawnCache() {}

    public static void rebuild(MinecraftServer server) {
        VALID_BIOMES.clear();
        VALID_STRUCTURES.clear();
        for (ServerLevel level : server.getAllLevels()) {
            Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
            Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            biomes.keySet().forEach(VALID_BIOMES::add);
            structures.keySet().forEach(VALID_STRUCTURES::add);
        }
        TensuraRaceSpawns.LOGGER.info("Cached {} biomes and {} structures for race spawn validation", VALID_BIOMES.size(), VALID_STRUCTURES.size());
    }

    public static boolean isValidBiome(ResourceLocation id) {
        return VALID_BIOMES.contains(id);
    }

    public static boolean isValidStructure(ResourceLocation id) {
        return VALID_STRUCTURES.contains(id);
    }
}
