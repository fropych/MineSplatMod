package io.github.yromko.minesplat.compat.chiselsandbits;

import io.github.yromko.minesplat.cnb.BlockStateStrings;
import io.github.yromko.minesplat.cnb.CnbBlueprint;
import io.github.yromko.minesplat.cnb.CnbHiddenLighting;
import io.github.yromko.minesplat.cnb.CnbIntegration;
import io.github.yromko.minesplat.cnb.CnbPackedModel;
import io.github.yromko.minesplat.cnb.CnbPlacementProgress;
import io.github.yromko.minesplat.cnb.CnbPlacementResult;
import io.github.yromko.minesplat.palette.PaletteEntry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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
    private static final int MAX_LIGHT_SECTIONS_PER_TICK = 32;
    private static final String API_CLASS = "mod.chiselsandbits.api.IChiselsAndBitsAPI";
    private static final String BLOCK_INFORMATION_CLASS =
            "mod.chiselsandbits.api.blockinformation.IBlockInformation";
    private static final String BLOCK_INFORMATION_FACTORY_CLASS =
            "mod.chiselsandbits.api.blockinformation.IBlockInformationFactory";
    private static final String STATE_ENTRY_SIZE_CLASS =
            "mod.chiselsandbits.api.multistate.StateEntrySize";
    private static final String ELIGIBILITY_MANAGER_CLASS =
            "mod.chiselsandbits.api.chiseling.eligibility.IEligibilityManager";
    private static final String MUTATOR_FACTORY_CLASS =
            "mod.chiselsandbits.api.multistate.mutator.IMutatorFactory";
    private static final String CHANGE_TRACKER_MANAGER_CLASS =
            "mod.chiselsandbits.api.change.IChangeTrackerManager";
    private static final String BATCHED_AREA_MUTATOR_CLASS =
            "mod.chiselsandbits.api.multistate.mutator.batched.IBatchedAreaMutator";
    private static final String MULTI_STATE_BLOCK_ENTITY_CLASS =
            "mod.chiselsandbits.api.block.entity.IMultiStateBlockEntity";
    private static final String BATCH_MUTATION_CLASS =
            "mod.chiselsandbits.api.util.IBatchMutation";

    private final Object eligibilityManager;
    private final Object mutatorFactory;
    private final Object changeTrackerManager;
    private final Object blockInformationFactory;
    private final Class<?> multiStateBlockEntityClass;
    private final Method createBlockInformationMethod;
    private final Method canBeChiseledMethod;
    private final Method mutatorInMethod;
    private final Method getChangeTrackerMethod;
    private final Method batchMethod;
    private final Method closeBatchMethod;
    private final MethodHandle setInAreaTargetHandle;
    private final int bitsPerBlockSide;
    private final CnbBitCenters bitCenters;
    private volatile PlacementJob job;

    public ChiselsAndBitsIntegration() {
        try {
            Class<?> apiType = Class.forName(API_CLASS);
            Object api = apiType.getMethod("getInstance").invoke(null);
            if (api == null) {
                throw new IllegalStateException("Chisels & Bits API is unavailable");
            }
            Class<?> blockInformation = Class.forName(BLOCK_INFORMATION_CLASS);
            Class<?> blockInformationFactoryType = Class.forName(
                    BLOCK_INFORMATION_FACTORY_CLASS);
            blockInformationFactory = invokePublic(
                    apiType.getMethod("getBlockInformationFactory"), api);
            if (!blockInformationFactoryType.isInstance(blockInformationFactory)) {
                throw new IllegalStateException(
                        "Chisels & Bits block-information factory is unavailable");
            }
            createBlockInformationMethod = blockInformationFactoryType.getMethod(
                    "create", BlockState.class, Optional.class);
            Object size = invokePublic(apiType.getMethod("getStateEntrySize"), api);
            bitsPerBlockSide = (int) invokePublic(
                    Class.forName(STATE_ENTRY_SIZE_CLASS)
                            .getMethod("getBitsPerBlockSide"),
                    size);
            bitCenters = new CnbBitCenters(bitsPerBlockSide);

            eligibilityManager = invokePublic(
                    apiType.getMethod("getEligibilityManager"), api);
            mutatorFactory = invokePublic(apiType.getMethod("getMutatorFactory"), api);
            changeTrackerManager = invokePublic(
                    apiType.getMethod("getChangeTrackerManager"), api);

            canBeChiseledMethod = Class.forName(ELIGIBILITY_MANAGER_CLASS)
                    .getMethod("canBeChiseled", blockInformation);
            mutatorInMethod = findPublicMethod(
                    Class.forName(MUTATOR_FACTORY_CLASS), "in", 2);
            getChangeTrackerMethod = findPublicMethod(
                    Class.forName(CHANGE_TRACKER_MANAGER_CLASS), "getChangeTracker", 1);
            batchMethod = findPublicMethod(
                    Class.forName(BATCHED_AREA_MUTATOR_CLASS), "batch", 1);
            closeBatchMethod = Class.forName(BATCH_MUTATION_CLASS).getMethod("close");
            multiStateBlockEntityClass = Class.forName(MULTI_STATE_BLOCK_ENTITY_CLASS);
            Method setInAreaTargetMethod = findPublicMethod(
                    multiStateBlockEntityClass, "setInAreaTarget", 2);
            setInAreaTargetHandle = MethodHandles.publicLookup()
                    .unreflect(setInAreaTargetMethod)
                    .asType(MethodType.methodType(
                            void.class, Object.class, Object.class, Vec3d.class));
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
        return bitsPerBlockSide;
    }

    @Override
    public CompletableFuture<List<PaletteEntry>> filterEligible(
            List<PaletteEntry> candidates
    ) {
        CompletableFuture<List<PaletteEntry>> result = new CompletableFuture<>();
        MinecraftClient.getInstance().execute(() -> {
            try {
                List<PaletteEntry> accepted = new ArrayList<>();
                for (PaletteEntry candidate : candidates) {
                    Object information = information(
                            BlockStateStrings.resolve(candidate.stateString()));
                    if ((boolean) invokePublic(
                            canBeChiseledMethod, eligibilityManager, information)) {
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
            CnbPackedModel packed,
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
        if (packed == null
                || packed.bitsPerBlockSide() != bitsPerBlockSide
                || packed.bitCount() != blueprint.voxelCount()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Prepared Chisels & Bits model does not match the blueprint"));
        }
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
                Object tracker = invokePublic(
                        getChangeTrackerMethod, changeTrackerManager, player);
                job = new PlacementJob(
                        server, world, blueprint, packed, palette, tracker, bitCenters,
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
        List<Object> result = new ArrayList<>(blueprint.palette().size());
        for (var entry : blueprint.palette()) {
            Object information = information(BlockStateStrings.resolveRotated(
                    entry.blockState(), quarterTurns));
            if (!(boolean) invokePublic(
                    canBeChiseledMethod, eligibilityManager, information)) {
                throw new IllegalArgumentException(
                        "Block is not supported by Chisels & Bits: " + entry.blockState());
            }
            result.add(information);
        }
        return List.copyOf(result);
    }

    private Object information(BlockState state) {
        return invokePublic(
                createBlockInformationMethod,
                blockInformationFactory,
                state,
                Optional.empty());
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
                if (active.cancelRequested) {
                    rollback(active, new IllegalStateException("Placement cancelled"));
                    return;
                }
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
            if (active.cancelRequested) {
                rollback(active, new IllegalStateException("Placement cancelled"));
                return;
            }
            active.progress.accept(new CnbPlacementProgress(
                    active.nextHost,
                    active.packed.hostBlocks().size(),
                    active.nextHost < active.packed.hostBlocks().size()
                            ? "Placing miniature"
                            : "Adding hidden lighting"));
            if (active.nextHost >= active.packed.hostBlocks().size()) {
                int sections = 0;
                while (active.nextLightSection < active.lightSections.size()
                        && sections < MAX_LIGHT_SECTIONS_PER_TICK) {
                    if (active.cancelRequested) {
                        rollback(active, new IllegalStateException("Placement cancelled"));
                        return;
                    }
                    placeHiddenLight(
                            active,
                            active.lightSections.get(active.nextLightSection));
                    active.nextLightSection++;
                    sections++;
                }
            }
            if (active.cancelRequested) {
                rollback(active, new IllegalStateException("Placement cancelled"));
                return;
            }
            if (active.nextHost >= active.packed.hostBlocks().size()
                    && active.nextLightSection >= active.lightSections.size()) {
                job = null;
                active.result.complete(new CnbPlacementResult(
                        active.packed.hostBlocks().size(),
                        active.blueprint.voxelCount()));
            }
        } catch (Throwable throwable) {
            rollback(active, throwable);
        }
    }

    private static void placeHiddenLight(
            PlacementJob active,
            CnbHiddenLighting.Section section
    ) {
        for (BlockPos relative : section.candidates()) {
            BlockPos target = active.origin.add(relative);
            if (active.world.isOutOfHeightLimit(target)
                    || !active.world.getWorldBorder().contains(target)
                    || !active.world.isChunkLoaded(target)
                    || !active.world.getBlockState(target).isAir()) {
                continue;
            }
            BlockState light = Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, 15);
            if (active.world.setBlockState(target, light, Block.NOTIFY_ALL)) {
                active.touched.add(target.toImmutable());
                return;
            }
        }
    }

    private void applyHost(PlacementJob active, CnbPackedModel.HostBlock host) {
        BlockPos target = active.origin.add(host.x(), host.y(), host.z());
        validateEmptyTarget(active.world, target);
        active.touched.add(target.toImmutable());
        Object mutator = invokePublic(
                mutatorInMethod, mutatorFactory, active.world, target);
        Object batch = invokePublic(batchMethod, mutator, active.tracker);
        try {
            BlockEntity blockEntity = active.world.getBlockEntity(target);
            if (!multiStateBlockEntityClass.isInstance(blockEntity)) {
                throw new IllegalStateException(
                        "Chisels & Bits did not create a multi-state block entity");
            }
            for (int index = 0; index < host.bitCount(); index++) {
                setInAreaTarget(
                        blockEntity,
                        active.palette.get(host.paletteIndexAt(index)),
                        active.bitCenters.at(host.localIndexAt(index)));
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

    private void setInAreaTarget(
            Object blockEntity,
            Object blockInformation,
            Vec3d target
    ) {
        try {
            setInAreaTargetHandle.invokeExact(blockEntity, blockInformation, target);
        } catch (Throwable throwable) {
            if (throwable instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "Chisels & Bits API call failed: setInAreaTarget", throwable);
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

    private static Method findPublicMethod(
            Class<?> type,
            String name,
            int parameterCount
    ) throws NoSuchMethodException {
        Method result = null;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name)
                    || method.getParameterCount() != parameterCount) {
                continue;
            }
            if (result != null) {
                throw new NoSuchMethodException(
                        "Ambiguous public API method " + type.getName() + "." + name);
            }
            result = method;
        }
        if (result == null) {
            throw new NoSuchMethodException(type.getName() + "." + name);
        }
        return result;
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
        private final CnbBlueprint blueprint;
        private final CnbPackedModel packed;
        private final List<Object> palette;
        private final Object tracker;
        private final CnbBitCenters bitCenters;
        private final BlockPos origin;
        private final Consumer<CnbPlacementProgress> progress;
        private final CompletableFuture<CnbPlacementResult> result;
        private final Set<BlockPos> touched = new LinkedHashSet<>();
        private final List<CnbHiddenLighting.Section> lightSections;
        private int nextHost;
        private int nextLightSection;
        private volatile boolean cancelRequested;

        private PlacementJob(
                MinecraftServer server,
                ServerWorld world,
                CnbBlueprint blueprint,
                CnbPackedModel packed,
                List<Object> palette,
                Object tracker,
                CnbBitCenters bitCenters,
                BlockPos origin,
                Consumer<CnbPlacementProgress> progress,
                CompletableFuture<CnbPlacementResult> result
        ) {
            this.server = server;
            this.world = world;
            this.blueprint = blueprint;
            this.packed = packed;
            this.palette = palette;
            this.tracker = tracker;
            this.bitCenters = bitCenters;
            this.origin = origin;
            this.progress = progress;
            this.result = result;
            this.lightSections = CnbHiddenLighting.plan(packed);
        }
    }
}
