package com.mappleaf.tensuraracespawns.spawn;

import com.mappleaf.tensuraracespawns.TensuraRaceSpawns;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Optional bridge to Async Locator Refined.
 *
 * This class intentionally uses reflection so the addon does not require
 * Async Locator Refined at compile time or runtime.
 */
public final class AsyncLocatorCompat {
    private static final String ASYNC_LOCATOR_CLASS = "brightspark.asynclocator.AsyncLocator";
    private static final String RESOURCE_OR_TAG_RESULT_CLASS = "net.minecraft.commands.arguments.ResourceOrTagArgument$Result";
    private static Boolean available;
    private static boolean missingLogged;

    private AsyncLocatorCompat() {}

    public static boolean isAvailable() {
        if (available != null) return available;
        try {
            Class.forName(ASYNC_LOCATOR_CLASS, false, AsyncLocatorCompat.class.getClassLoader());
            available = true;
            TensuraRaceSpawns.LOGGER.info("Async Locator Refined detected; configured spawn locating will use its async API when possible");
        } catch (Throwable ignored) {
            available = false;
            if (!missingLogged) {
                missingLogged = true;
                TensuraRaceSpawns.LOGGER.info("Async Locator Refined is not installed; configured spawn locating will use vanilla synchronous fallback");
            }
        }
        return available;
    }

    @SuppressWarnings("unchecked")
    public static Optional<CompletableFuture<Pair<BlockPos, Holder<Structure>>>> locateStructure(ServerLevel level, HolderSet<Structure> structures, BlockPos center, int radiusChunks, boolean skipKnownStructures) {
        if (!isAvailable()) return Optional.empty();
        try {
            Class<?> clazz = Class.forName(ASYNC_LOCATOR_CLASS);
            Method locate = clazz.getMethod("locate", ServerLevel.class, HolderSet.class, BlockPos.class, int.class, boolean.class);
            Object task = locate.invoke(null, level, structures, center, radiusChunks, skipKnownStructures);
            Method completableFuture = task.getClass().getMethod("completableFuture");
            Object future = completableFuture.invoke(task);
            if (future instanceof CompletableFuture<?> completable) {
                return Optional.of((CompletableFuture<Pair<BlockPos, Holder<Structure>>>) completable);
            }
        } catch (Throwable t) {
            available = false;
            TensuraRaceSpawns.LOGGER.warn("Async Locator Refined structure integration failed; falling back to vanilla synchronous locating", t);
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public static Optional<CompletableFuture<Pair<BlockPos, Holder<Biome>>>> locateBiome(ServerLevel level, Registry<Biome> biomeRegistry, List<ResourceLocation> biomeIds, BlockPos center, int radiusBlocks, int horizontalStep, int verticalStep) {
        if (!isAvailable()) return Optional.empty();
        if (biomeIds.isEmpty()) return Optional.empty();

        try {
            Class<?> asyncLocator = Class.forName(ASYNC_LOCATOR_CLASS);
            Class<?> resultInterface = Class.forName(RESOURCE_OR_TAG_RESULT_CLASS);
            Object biomeResult = createBiomeResultProxy(resultInterface, biomeRegistry, biomeIds);

            Method locateBiome = asyncLocator.getMethod("locateBiome", ServerLevel.class, resultInterface, BlockPos.class, int.class, int.class, int.class);
            Object task = locateBiome.invoke(null, level, biomeResult, center, radiusBlocks, horizontalStep, verticalStep);
            Method completableFuture = task.getClass().getMethod("completableFuture");
            Object future = completableFuture.invoke(task);
            if (future instanceof CompletableFuture<?> completable) {
                return Optional.of((CompletableFuture<Pair<BlockPos, Holder<Biome>>>) completable);
            }
        } catch (Throwable t) {
            available = false;
            TensuraRaceSpawns.LOGGER.warn("Async Locator Refined biome integration failed; falling back to vanilla synchronous locating", t);
        }
        return Optional.empty();
    }

    private static Object createBiomeResultProxy(Class<?> resultInterface, Registry<Biome> biomeRegistry, List<ResourceLocation> biomeIds) {
        String printable = biomeIds.stream().map(ResourceLocation::toString).collect(Collectors.joining(", "));
        return Proxy.newProxyInstance(
                AsyncLocatorCompat.class.getClassLoader(),
                new Class<?>[]{resultInterface},
                (proxy, method, args) -> switch (method.getName()) {
                    case "test" -> {
                        if (args == null || args.length != 1 || !(args[0] instanceof Holder<?> holder)) yield false;
                        Object value = holder.value();
                        if (!(value instanceof Biome biome)) yield false;
                        ResourceLocation actual = biomeRegistry.getKey(biome);
                        yield actual != null && biomeIds.contains(actual);
                    }
                    case "asPrintable", "toString" -> printable;
                    case "hashCode" -> biomeIds.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("Unsupported ResourceOrTagArgument.Result method: " + method);
                }
        );
    }
}
