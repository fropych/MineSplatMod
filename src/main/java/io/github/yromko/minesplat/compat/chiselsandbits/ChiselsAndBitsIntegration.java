package io.github.yromko.minesplat.compat.chiselsandbits;

import io.github.yromko.minesplat.cnb.BlockStateStrings;
import io.github.yromko.minesplat.cnb.CnbBlueprint;
import io.github.yromko.minesplat.cnb.CnbIntegration;
import io.github.yromko.minesplat.cnb.CnbPackedModel;
import io.github.yromko.minesplat.cnb.CnbPacking;
import io.github.yromko.minesplat.cnb.CnbPlacementProgress;
import io.github.yromko.minesplat.cnb.CnbPlacementResult;
import io.github.yromko.minesplat.palette.PaletteEntry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Optional bridge kept behind reflective calls so this class can only link C&B
 * after Fabric Loader has verified that the exact supported mod is present.
 * All invoked members belong to the published Chisels & Bits API.
 */
public final class ChiselsAndBitsIntegration implements CnbIntegration {
    private static final int MAX_HOST_BLOCKS_PER_TICK = 8;
    private static final int MAX_BITS_PER_TICK = 8192;
    private static final String API_CLASS = "mod.chiselsandbits.api.IChiselsAndBitsAPI";
    private static final String BLOCK_INFORMATION_CLASS =
            "mod.chiselsandbits.api.blockinformation.BlockInformation";
    private static final String BATCH_MUTATION_CLASS =
            "mod.chiselsandbits.api.util.IBatchMutation";

    private final Object api;
    private final Constructor<?> blockInformationConstructor;
    private final Method closeBatchMethod;
    private volatile PlacementJob job;

    public ChiselsAndBitsIntegration() {
        try {
            Class<?> apiType = Class.forName(API_CLASS);
            api = apiType.getMethod("getInstance").invoke(null);
            if (api == null) {
                throw new IllegalStateException("Chisels & Bits API is unavailable");
            }
            Class<?> blockInformation = Class.forName(BLOCK_INFORMATION_CLASS);
            blockInformationConstructor = findBlockInformationConstructor(blockInformation);
            closeBatchMethod = Class.forName(BATCH_MUTATION_CLASS).getMethod("close");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Cannot connect to the Chisels & Bits public API", unwrap(exception));
        }
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::serverStopping);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return "";
    }

    @Override
    public int bitsPerBlockSide() {
        Object size = invoke(api, "getStateEntrySize");
        return (int) invoke(size, "getBitsPerBlockSide");
    }

    @Override
    public CompletableFuture<List<PaletteEntry>> filterEligible(
            List<PaletteEntry> candidates
    ) {
        CompletableFuture<List<PaletteEntry>> result = new CompletableFuture<>();
        MinecraftClient.getInstance().execute(() -> {
            try {
                Object eligibility = invoke(api, "getEligibilityManager");
                List<PaletteEntry> accepted = new ArrayList<>();
                for (PaletteEntry candidate : candidates) {
                    Object information = information(
                            BlockStateStrings.resolve(candidate.stateString()));
                    if ((boolean) invoke(eligibility, "canBeChiseled", information)) {
                        accepted.add(candidate);
                    }
                }
                if (accepted.isEmpty()) {
                    throw new IllegalArgumentException(
                            "No Chisels & Bits-compatible blocks remain in the palette");
                }
                result.complete(List.copyOf(accepted));
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    @Override
    public CompletableFuture<CnbPlacementResult> place(
            MinecraftClient client,
            CnbBlueprint blueprint,
            int quarterTurns,
            BlockPos origin,
            Consumer<CnbPlacementProgress> progress
    ) {
        if (client == null || !client.isInSingleplayer() || client.getServer() == null
                || client.player == null || client.world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Chisels & Bits placement requires a singleplayer world"));
        }
        if (!client.player.isCreative()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Chisels & Bits placement requires Creative mode"));
        }
        CnbPackedModel packed = CnbPacking.pack(
                blueprint, bitsPerBlockSide(), quarterTurns);
        CompletableFuture<CnbPlacementResult> result = new CompletableFuture<>();
        MinecraftServer server = client.getServer();
        server.execute(() -> {
            try {
                if (job != null) {
                    throw new IllegalStateException(
                            "Another Chisels & Bits placement is already active");
                }
                ServerPlayerEntity player = server.getPlayerManager()
                        .getPlayer(client.player.getUuid());
                if (player == null || !player.isCreative()) {
                    throw new IllegalStateException(
                            "Creative server player is unavailable");
                }
                ServerWorld world = player.getServerWorld();
                if (!world.getRegistryKey().equals(client.world.getRegistryKey())) {
                    throw new IllegalStateException(
                            "Client and integrated server dimensions do not match");
                }
                List<Object> palette = resolvePalette(blueprint, quarterTurns);
                preflight(world, packed, origin);
                job = new PlacementJob(
                        server, world, player, blueprint, packed, palette,
                        origin.toImmutable(), progress, result);
                progress.accept(new CnbPlacementProgress(
                        0, packed.hostBlocks().size(), "Placement started"));
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    @Override
    public void cancelPlacement() {
        PlacementJob active = job;
        if (active != null) {
            active.cancelRequested = true;
        }
    }

    private List<Object> resolvePalette(CnbBlueprint blueprint, int quarterTurns) {
        Object eligibility = invoke(api, "getEligibilityManager");
        List<Object> result = new ArrayList<>(blueprint.palette().size());
        for (var entry : blueprint.palette()) {
            Object information = information(BlockStateStrings.resolveRotated(
                    entry.blockState(), quarterTurns));
            if (!(boolean) invoke(eligibility, "canBeChiseled", information)) {
                throw new IllegalArgumentException(
                        "Block is not supported by Chisels & Bits: " + entry.blockState());
            }
            result.add(information);
        }
        return List.copyOf(result);
    }

    private Object information(BlockState state) {
        try {
            return blockInformationConstructor.newInstance(state, Optional.empty());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Cannot create Chisels & Bits block information", unwrap(exception));
        }
    }

    private static void preflight(
            ServerWorld world,
            CnbPackedModel packed,
            BlockPos origin
    ) {
        for (CnbPackedModel.HostBlock host : packed.hostBlocks()) {
            validateEmptyTarget(world, origin.add(host.x(), host.y(), host.z()));
        }
    }

    private static void validateEmptyTarget(ServerWorld world, BlockPos target) {
        if (world.isOutOfHeightLimit(target)) {
            throw new IllegalStateException("Placement is outside the world build height");
        }
        if (!world.getWorldBorder().contains(target)) {
            throw new IllegalStateException("Placement is outside the world border");
        }
        if (!world.isChunkLoaded(target)) {
            throw new IllegalStateException("A target chunk is not loaded");
        }
        if (!world.getBlockState(target).isAir()) {
            throw new IllegalStateException("Placement collides with an existing block");
        }
    }

    private void tick(MinecraftServer server) {
        PlacementJob active = job;
        if (active == null || active.server != server) {
            return;
        }
        if (active.cancelRequested) {
            rollback(active, new IllegalStateException("Placement cancelled"));
            return;
        }
        int hosts = 0;
        int bits = 0;
        try {
            while (active.nextHost < active.packed.hostBlocks().size()
                    && hosts < MAX_HOST_BLOCKS_PER_TICK) {
                CnbPackedModel.HostBlock host =
                        active.packed.hostBlocks().get(active.nextHost);
                if (hosts > 0 && bits + host.bitCount() > MAX_BITS_PER_TICK) {
                    break;
                }
                applyHost(active, host);
                active.nextHost++;
                hosts++;
                bits += host.bitCount();
            }
            active.progress.accept(new CnbPlacementProgress(
                    active.nextHost,
                    active.packed.hostBlocks().size(),
                    "Placing miniature"));
            if (active.nextHost >= active.packed.hostBlocks().size()) {
                job = null;
                active.result.complete(new CnbPlacementResult(
                        active.packed.hostBlocks().size(),
                        active.blueprint.voxelCount()));
            }
        } catch (Throwable throwable) {
            rollback(active, throwable);
        }
    }

    private void applyHost(PlacementJob active, CnbPackedModel.HostBlock host) {
        BlockPos target = active.origin.add(host.x(), host.y(), host.z());
        validateEmptyTarget(active.world, target);
        active.touched.add(target.toImmutable());
        Object mutator = invoke(
                invoke(api, "getMutatorFactory"), "in", active.world, target);
        Object tracker = invoke(
                invoke(api, "getChangeTrackerManager"),
                "getChangeTracker",
                active.player);
        Object batch = invoke(mutator, "batch", tracker);
        int side = active.packed.bitsPerBlockSide();
        int[] localIndices = host.localIndices();
        int[] paletteIndices = host.paletteIndices();
        try {
            for (int index = 0; index < localIndices.length; index++) {
                int local = localIndices[index];
                int x = local % side;
                int y = (local / side) % side;
                int z = local / (side * side);
                invoke(
                        mutator,
                        "setInBlockTarget",
                        active.palette.get(paletteIndices[index]),
                        BlockPos.ORIGIN,
                        new Vec3d(
                                (x + 0.5) / side,
                                (y + 0.5) / side,
                                (z + 0.5) / side));
            }
        } finally {
            invokePublic(closeBatchMethod, batch);
        }
    }

    private void rollback(PlacementJob active, Throwable failure) {
        for (BlockPos position : active.touched) {
            active.world.setBlockState(
                    position, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        job = null;
        active.result.completeExceptionally(failure);
    }

    private void serverStopping(MinecraftServer server) {
        PlacementJob active = job;
        if (active != null && active.server == server) {
            rollback(active, new IllegalStateException(
                    "Placement rolled back because the world is closing"));
        }
    }

    private static Constructor<?> findBlockInformationConstructor(Class<?> type) {
        for (Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 2
                    && parameters[0].isAssignableFrom(BlockState.class)
                    && parameters[1].isAssignableFrom(Optional.class)) {
                return constructor;
            }
        }
        throw new IllegalStateException(
                "Chisels & Bits BlockInformation constructor was not found");
    }

    static Object invoke(Object target, String name, Object... arguments) {
        try {
            Method method = findMethod(target.getClass(), name, arguments);
            return invokePublic(method, target, arguments);
        } catch (ReflectiveOperationException exception) {
            Throwable cause = unwrap(exception);
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                    "Chisels & Bits API call failed: " + name, cause);
        }
    }

    static Object invokePublic(
            Method method,
            Object target,
            Object... arguments
    ) {
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException exception) {
            Throwable cause = unwrap(exception);
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                    "Chisels & Bits API call failed: " + method.getName(), cause);
        }
    }

    private static Method findMethod(
            Class<?> type,
            String name,
            Object[] arguments
    ) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals(name) || parameters.length != arguments.length) {
                continue;
            }
            boolean compatible = true;
            for (int index = 0; index < parameters.length; index++) {
                if (arguments[index] != null
                        && !wrap(parameters[index]).isInstance(arguments[index])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class PlacementJob {
        private final MinecraftServer server;
        private final ServerWorld world;
        private final ServerPlayerEntity player;
        private final CnbBlueprint blueprint;
        private final CnbPackedModel packed;
        private final List<Object> palette;
        private final BlockPos origin;
        private final Consumer<CnbPlacementProgress> progress;
        private final CompletableFuture<CnbPlacementResult> result;
        private final Set<BlockPos> touched = new LinkedHashSet<>();
        private int nextHost;
        private volatile boolean cancelRequested;

        private PlacementJob(
                MinecraftServer server,
                ServerWorld world,
                ServerPlayerEntity player,
                CnbBlueprint blueprint,
                CnbPackedModel packed,
                List<Object> palette,
                BlockPos origin,
                Consumer<CnbPlacementProgress> progress,
                CompletableFuture<CnbPlacementResult> result
        ) {
            this.server = server;
            this.world = world;
            this.player = player;
            this.blueprint = blueprint;
            this.packed = packed;
            this.palette = palette;
            this.origin = origin;
            this.progress = progress;
            this.result = result;
        }
    }
}
