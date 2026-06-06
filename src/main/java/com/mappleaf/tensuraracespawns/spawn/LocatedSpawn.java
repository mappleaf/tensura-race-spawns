package com.mappleaf.tensuraracespawns.spawn;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public record LocatedSpawn(ServerLevel level, BlockPos pos, String assignmentKey, ResourceLocation matchedBiome, ResourceLocation matchedStructure) {
}
