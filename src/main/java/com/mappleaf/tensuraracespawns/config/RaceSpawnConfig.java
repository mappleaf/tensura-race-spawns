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
    private static boolean useAsyncLocator = true;

    private RaceSpawnConfig() {}

    public static void init(Path path) {
        configPath = path;

        TensuraRaceSpawns.LOGGER.debug(
                "Initializing race spawn config at {}",
                configPath.toAbsolutePath()
        );

        ensureDefaultFile();
        reload();
    }

    public static int spawnRadius() {
        return Math.max(0, spawnRadius);
    }

    public static boolean useAsyncLocator() {
        return useAsyncLocator;
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
        try (CommentedFileConfig cfg = CommentedFileConfig.builder(configPath)
                .preserveInsertionOrder()
                .build()) {

            cfg.load();

            if (migrate(cfg)) {
                cfg.save();
                TensuraRaceSpawns.LOGGER.info(
                        "Migrated race spawn config at {} by adding missing default fields",
                        configPath
                );
            }

            Number radius = cfg.getOrElse("settings.spawnRadius", 6400);
            spawnRadius = radius.intValue();
            useAsyncLocator = Boolean.TRUE.equals(cfg.getOrElse("settings.useAsyncLocator", Boolean.TRUE));
            CommentedConfig races = cfg.get("Races");
            if (races == null) return;
            for (CommentedConfig.Entry entry : races.entrySet()) {
                if (!(entry.getValue() instanceof Config raceCfg)) continue;
                String name = entry.getKey();
                String dimension = String.valueOf(raceCfg.getOrElse("spawnDimension", "")).trim();
                TaggedEntries biomeEntries = parseTaggedEntries(name, "spawnBiome", raceCfg.get("spawnBiome"));
                TaggedEntries structureEntries = parseTaggedEntries(name, "spawnStructure", raceCfg.get("spawnStructure"));
                boolean onlyInitial = Boolean.TRUE.equals(raceCfg.getOrElse("onlyInitial", Boolean.TRUE));
                boolean assigned = Boolean.TRUE.equals(raceCfg.getOrElse("assignedToPlayer", Boolean.FALSE));
                RULES.put(name, new RaceSpawnRule(
                        dimension,
                        biomeEntries.ids(),
                        biomeEntries.tags(),
                        structureEntries.ids(),
                        structureEntries.tags(),
                        onlyInitial,
                        assigned
                ));
            }
            TensuraRaceSpawns.LOGGER.info("Loaded {} race spawn rules from {}", RULES.size(), configPath);
        } catch (Exception e) {
            TensuraRaceSpawns.LOGGER.error("Could not load {}", configPath, e);
        }
    }

    /**
     * Keeps old configs compatible with newer addon versions.
     * Missing required fields are appended automatically, while existing values are preserved.
     */
    private static boolean migrate(CommentedFileConfig cfg) {
        TensuraRaceSpawns.LOGGER.info(
                "Checking race spawn config migration at {}",
                configPath.toAbsolutePath()
        );

        boolean changed = false;

        changed |= ensureTable(cfg, List.of("settings"));
        changed |= putRootIfMissing(cfg, List.of("settings", "spawnRadius"), 6400);
        changed |= putRootIfMissing(cfg, List.of("settings", "useAsyncLocator"), Boolean.TRUE);

        changed |= ensureTable(cfg, List.of("Races"));

        Object rawRaces = cfg.get(List.of("Races"));
        if (!(rawRaces instanceof Config races)) {
            TensuraRaceSpawns.LOGGER.warn("Cannot migrate race configs: [Races] is not a TOML table");
            return changed;
        }

        for (Config.Entry entry : races.entrySet()) {
            String raceName = entry.getKey();

            if (!(entry.getValue() instanceof Config)) {
                TensuraRaceSpawns.LOGGER.warn(
                        "Skipping config migration for Races.{}: expected TOML table",
                        raceName
                );
                continue;
            }

            changed |= migrateRace(cfg, raceName);
        }

        if (changed) {
            TensuraRaceSpawns.LOGGER.info(
                    "Race spawn config migrated."
            );
        }

        return changed;
    }

    private static boolean migrateRace(Config root, String raceName) {
        boolean changed = false;

        changed |= putRootIfMissing(root, List.of("Races", raceName, "spawnDimension"), "");
        changed |= putRootIfMissing(root, List.of("Races", raceName, "spawnBiome"), new ArrayList<>());
        changed |= putRootIfMissing(root, List.of("Races", raceName, "spawnStructure"), new ArrayList<>());
        changed |= putRootIfMissing(root, List.of("Races", raceName, "onlyInitial"), Boolean.TRUE);
        changed |= putRootIfMissing(root, List.of("Races", raceName, "assignedToPlayer"), Boolean.FALSE);

        return changed;
    }

    private static boolean ensureTable(Config root, List<String> path) {
        Object existing = root.get(path);

        if (existing instanceof Config) {
            return false;
        }

        root.set(path, CommentedConfig.inMemory());
        return true;
    }

    private static boolean putRootIfMissing(Config cfg, List<String> path, Object value) {
        if (cfg.contains(path)) {
            return false;
        }

        cfg.set(path, value);
        return true;
    }

    private record TaggedEntries(List<ResourceLocation> ids, List<ResourceLocation> tags) {}

    private static TaggedEntries parseTaggedEntries(String raceName, String field, Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return new TaggedEntries(List.of(), List.of());
        }

        ArrayList<ResourceLocation> ids = new ArrayList<>();
        ArrayList<ResourceLocation> tags = new ArrayList<>();
        for (Object object : list) {
            if (object == null) continue;

            String text = String.valueOf(object).trim();
            if (text.isEmpty()) continue;

            boolean isTag = text.startsWith("#");
            String idText = isTag ? text.substring(1).trim() : text;
            ResourceLocation id = ResourceLocation.tryParse(idText);
            if (id == null) {
                TensuraRaceSpawns.LOGGER.warn("Ignoring invalid entry '{}' in Races.{}.{}", object, raceName, field);
                continue;
            }

            if (isTag) {
                tags.add(id);
            } else {
                ids.add(id);
            }
        }

        return new TaggedEntries(List.copyOf(ids), List.copyOf(tags));
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
                # You should use it if Async Locator Refined is installed as it prevents server from
                # lagging during spawn location search.
                # If the mod is missing or fails, synchronous vanilla locating is used automatically.
                # So just keep it "true" unless unexpected shit happens.
                useAsyncLocator=true

            [Races]
                # Core Tensura races can be configured by path name: [Races.slime], [Races.human], etc.
                # Addon races should normally be quoted full registry ids: [Races.\"addonid:race_name\"].
                # Entries beginning with # in spawnBiome/spawnStructure are treated as registry tags.
                [Races.human]
                    spawnDimension="minecraft:overworld"
                    # You can use tags in biomes, ex. "#minecraft:is_overworld"
                    spawnBiome=[]
                    # And in structures too.
                    spawnStructure=[]
                    # Means that it will be only starting location and will be resetted after spawnpoint
                    # is changed
                    onlyInitial=true
                    # If set to true, provided structure or biome will be assigned to the first player that
                    # will be spawned in. No one will be able to spawn in biome / structure before the
                    # assigned player's world spawn changed once again
                    assignedToPlayer=false
                
                # All the spawn* fields can be empty. That means they will be ignored during search and
                # default values will be used. If no biome or structure is specified, race will just use a
                # default world spawn
                [Races.slime]
                    spawnDimension=""
                    spawnBiome=[]
                    spawnStructure=[]
                    onlyInitial=true
                    assignedToPlayer=false
            """;
}
