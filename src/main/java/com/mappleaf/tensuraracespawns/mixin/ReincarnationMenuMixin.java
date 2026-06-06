package com.mappleaf.tensuraracespawns.mixin;

import io.github.manasmods.manascore.race.api.ManasRace;
import io.github.manasmods.tensura.menu.ReincarnationMenu;
import com.mappleaf.tensuraracespawns.spawn.RaceSpawnApplicator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ReincarnationMenu.class, remap = false)
public abstract class ReincarnationMenuMixin {
    @Inject(method = "setRace", at = @At("TAIL"), remap = false)
    private static void tensuraracespawns$afterSetRace(Player player, ManasRace race, boolean resetEP, boolean grantUnique, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            RaceSpawnApplicator.afterRaceSelected(serverPlayer, race);
        }
    }
}
