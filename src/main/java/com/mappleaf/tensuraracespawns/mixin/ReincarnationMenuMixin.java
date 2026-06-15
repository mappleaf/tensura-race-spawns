package com.mappleaf.tensuraracespawns.mixin;

import com.mappleaf.tensuraracespawns.spawn.RaceSpawnApplicator;
import io.github.manasmods.manascore.race.api.ManasRace;
import io.github.manasmods.manascore.race.api.ManasRaceInstance;
import io.github.manasmods.manascore.race.api.RaceAPI;
import io.github.manasmods.tensura.menu.ReincarnationMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(value = ReincarnationMenu.class, remap = false)
public abstract class ReincarnationMenuMixin {
    @Inject(method = "clickMenuButton", at = @At("RETURN"), remap = false)
    private void tensuraracespawns$afterConfirmedRaceSelection(
            Player player,
            int buttonId,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }

        if (buttonId != ReincarnationMenu.SUBMIT_BUTTON_ID && buttonId != ReincarnationMenu.CHANGE_RACE_ONLY_SUBMIT_ID) {
            return;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        Optional<ManasRaceInstance> currentRace = RaceAPI.getRaceFrom((LivingEntity) player).getRace();
        if (currentRace.isEmpty()) {
            return;
        }

        ManasRace race = currentRace.get().getRace();
        if (race != null) {
            RaceSpawnApplicator.afterRaceSelected(serverPlayer, race);
        }
    }
}
