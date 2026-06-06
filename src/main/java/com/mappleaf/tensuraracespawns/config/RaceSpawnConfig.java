package com.mappleaf.tensuraracespawns.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mappleaf.tensuraracespawns.TensuraRaceSpawns;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class RaceSpawnConfig {
    private static final Map<String, RaceSpawnRule> RULES = new HashMap<>();
    private static Path configPath;
    private static int spawnRadius = 6400;

    private RaceSpawnConfig() {}

    public static void init(Path path) {
        configPath = path;
        ensureDefaultFile();
        reload();
    }

    public static int spawnRadius() {
        return Math.max(0, spawnRadius);
    }

    public static Optional<RaceSpawnRule> getRule(ResourceLocation raceId) {
        RaceSpawnRule exact = RULES.get(raceId.toString());
        if (exact != null) return Optional.of(exact);
        RaceSpawnRule byPath = RULES.get(raceId.getPath());
        return Optional.ofNullable(byPath);
    }

    public static void reload() {
        if (configPath == null) return;
        RULES.clear();
        try (CommentedFileConfig cfg = CommentedFileConfig.builder(configPath).sync().autosave().preserveInsertionOrder().build()) {
            cfg.load();
            Number radius = cfg.getOrElse("settings.spawnRadius", 6400);
            spawnRadius = radius.intValue();
            CommentedConfig races = cfg.get("Races");
            if (races == null) return;
            for (CommentedConfig.Entry entry : races.entrySet()) {
                if (!(entry.getValue() instanceof Config raceCfg)) continue;
                String name = entry.getKey();
                String dimension = String.valueOf(raceCfg.getOrElse("spawnDimension", "")).trim();
                List<ResourceLocation> biomes = parseIdList(name, "spawnBiome", raceCfg.get("spawnBiome"));
                List<ResourceLocation> structures = parseIdList(name, "spawnStructure", raceCfg.get("spawnStructure"));
                boolean onlyInitial = Boolean.TRUE.equals(raceCfg.getOrElse("onlyInitial", Boolean.TRUE));
                boolean assigned = Boolean.TRUE.equals(raceCfg.getOrElse("assignedToPlayer", Boolean.FALSE));
                RULES.put(name, new RaceSpawnRule(dimension, biomes, structures, onlyInitial, assigned));
            }
            TensuraRaceSpawns.LOGGER.info("Loaded {} race spawn rules from {}", RULES.size(), configPath);
        } catch (Exception e) {
            TensuraRaceSpawns.LOGGER.error("Could not load {}", configPath, e);
        }
    }

    private static List<ResourceLocation> parseIdList(String raceName, String field, Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        ArrayList<ResourceLocation> result = new ArrayList<>();
        for (Object object : list) {
            if (object == null) continue;
            ResourceLocation id = ResourceLocation.tryParse(String.valueOf(object).trim());
            if (id == null) {
                TensuraRaceSpawns.LOGGER.warn("Ignoring invalid id '{}' in Races.{}.{}", object, raceName, field);
                continue;
            }
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static void ensureDefaultFile() {
        try {
            Files.createDirectories(configPath.getParent());
            if (Files.exists(configPath)) return;
            Files.writeString(configPath, DEFAULT_CONFIG);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create default Tensura race spawn config", e);
        }
    }

    private static final String DEFAULT_CONFIG = """
            [settings]
                # Max horizontal search radius in blocks. Used for structures and biome sampling.
                spawnRadius=6400

            [Races]
                # Core Tensura races can be configured by path name: [Races.slime], [Races.human], etc.
                # Addon races should normally be quoted full registry ids: [Races.\"addonid:race_name\"].
                [Races.slime]
                    spawnDimension=""
                    spawnBiome=[]
                    spawnStructure=[]
                    onlyInitial=true
                    assignedToPlayer=false

                [Races.human]
                    spawnDimension="minecraft:overworld"
                    spawnBiome=[]
                    spawnStructure=[]
                    onlyInitial=true
                    assignedToPlayer=false
            """;
}
