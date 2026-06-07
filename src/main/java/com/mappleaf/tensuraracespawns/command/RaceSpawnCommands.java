package com.mappleaf.tensuraracespawns.command;

import com.mappleaf.tensuraracespawns.TensuraRaceSpawns;
import com.mappleaf.tensuraracespawns.config.RaceSpawnConfig;
import com.mappleaf.tensuraracespawns.spawn.RaceSpawnApplicator;
import com.mappleaf.tensuraracespawns.spawn.SpawnCache;
import com.mojang.brigadier.Command;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class RaceSpawnCommands {
    private RaceSpawnCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal(TensuraRaceSpawns.MOD_ID)
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reload")
                                .executes(context -> reload(context.getSource())))
        );
    }

    private static int reload(CommandSourceStack source) {
        RaceSpawnConfig.reload();
        SpawnCache.rebuild(source.getServer());
        RaceSpawnApplicator.restartActiveSearches(source.getServer());

        source.sendSuccess(
                () -> Component.literal("Tensura Race Spawns config reloaded."),
                true
        );

        TensuraRaceSpawns.LOGGER.info("Race spawn config was reloaded by command");
        return Command.SINGLE_SUCCESS;
    }
}
