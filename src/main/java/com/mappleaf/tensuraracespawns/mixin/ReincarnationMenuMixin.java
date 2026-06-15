package com.mappleaf.tensuraracespawns.mixin;

import com.mappleaf.tensuraracespawns.spawn.RaceSpawnApplicator;
import io.github.manasmods.manascore.race.api.ManasRace;
import io.github.manasmods.tensura.menu.ReincarnationMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(value = ReincarnationMenu.class, remap = false)
public abstract class ReincarnationMenuMixin {
    @Shadow
    @Final
    private Player player;

    @Shadow
    public abstract List<ManasRace> getRacePool();

    @Redirect(
            method = "clickMenuButton",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/github/manasmods/tensura/menu/ReincarnationMenu;setRace(Lnet/minecraft/world/entity/player/Player;Lio/github/manasmods/manascore/race/api/ManasRace;ZZ)V"
            ),
            remap = false
    )
    private void tensuraracespawns$afterConfirmedRaceSelection(
            Player player,
            ManasRace race,
            boolean resetEP,
            boolean grantUnique
    ) {
        ReincarnationMenu.setRace(player, race, resetEP, grantUnique);

        if (player instanceof ServerPlayer serverPlayer) {
            RaceSpawnApplicator.afterRaceSelected(serverPlayer, race);
        }
    }
}