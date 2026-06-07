package com.mappleaf.tensuraracespawns;

import com.mappleaf.tensuraracespawns.command.RaceSpawnCommands;
import com.mappleaf.tensuraracespawns.config.RaceSpawnConfig;
import com.mappleaf.tensuraracespawns.event.RespawnFallbackEvents;
import com.mappleaf.tensuraracespawns.event.RaceResetCleanupEvents;
import com.mappleaf.tensuraracespawns.spawn.SpawnCache;
import com.mappleaf.tensuraracespawns.spawn.SpawnSearchLock;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(TensuraRaceSpawns.MOD_ID)
public final class TensuraRaceSpawns {
    public static final String MOD_ID = "tensuraracespawns";
    public static final Logger LOGGER = LoggerFactory.getLogger("TensuraRaceSpawns");

    public TensuraRaceSpawns(IEventBus modBus) {
        RaceSpawnConfig.init(FMLPaths.CONFIGDIR.get().resolve("tensura").resolve("race_spawns.toml"));
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.register(RespawnFallbackEvents.class);
        NeoForge.EVENT_BUS.register(RaceResetCleanupEvents.class);
        NeoForge.EVENT_BUS.register(SpawnSearchLock.class);
        NeoForge.EVENT_BUS.register(RaceSpawnCommands.class);
    }

    private void onServerStarted(ServerStartedEvent event) {
        RaceSpawnConfig.reload();
        SpawnCache.rebuild(event.getServer());
    }
}
