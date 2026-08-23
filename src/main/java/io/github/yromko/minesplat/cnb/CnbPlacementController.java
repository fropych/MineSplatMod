package io.github.yromko.minesplat.cnb;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CnbPlacementController implements AutoCloseable {
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(
            Identifier.of("minesplat", "placement"));
    private static final Identifier HUD_ELEMENT =
            Identifier.of("minesplat", "cnb_placement");
    private static final RenderStateDataKey<PreviewRenderState> PREVIEW_RENDER_STATE =
            RenderStateDataKey.create(() -> "MineSplat C&B preview");
    private static final double ANCHOR_RAY_DISTANCE = 1024.0;
    private static final long VALIDATION_CACHE_TICKS = 5;

    private final MinecraftClient client;
    private final CnbIntegration integration;
    private final CnbBlueprintStore store;
    private final ExecutorService worker;
    private final KeyBinding rotate;
    private final KeyBinding left;
    private final KeyBinding right;
    private final KeyBinding forward;
    private final KeyBinding backward;
    private final KeyBinding up;
    private final KeyBinding down;
    private final KeyBinding cancel;

    private volatile CnbPlacementSnapshot snapshot = CnbPlacementSnapshot.inactive();
    private CnbBlueprint blueprint;
    private CnbPackedModel packed;
    private CnbPreviewBuffer previewBuffer;
    private int quarterTurns;
    private int offsetX;
    private int offsetY;
    private int offsetZ;
    private BlockPos origin;
    private BlockPos lastValidatedOrigin;
    private long lastValidationTick = Long.MIN_VALUE;
    private boolean lastValidationResult;
    private int successTicks;

    public CnbPlacementController(
            MinecraftClient client,
            CnbIntegration integration,
            CnbBlueprintStore store
    ) {
        this.client = client;
        this.integration = integration;
        this.store = store;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MineSplat C&B preview");
            thread.setDaemon(true);
            return thread;
        });
        rotate = key("key.minesplat.cnb.rotate", GLFW.GLFW_KEY_R);
        left = key("key.minesplat.cnb.left", GLFW.GLFW_KEY_LEFT);
        right = key("key.minesplat.cnb.right", GLFW.GLFW_KEY_RIGHT);
        forward = key("key.minesplat.cnb.forward", GLFW.GLFW_KEY_UP);
        backward = key("key.minesplat.cnb.backward", GLFW.GLFW_KEY_DOWN);
        up = key("key.minesplat.cnb.up", GLFW.GLFW_KEY_PAGE_UP);
        down = key("key.minesplat.cnb.down", GLFW.GLFW_KEY_PAGE_DOWN);
        cancel = key("key.minesplat.cnb.cancel", GLFW.GLFW_KEY_ESCAPE);
        registerEvents();
    }

    public CnbIntegration integration() {
        return integration;
    }

    public CnbPlacementSnapshot snapshot() {
        return snapshot;
    }

    public boolean active() {
        return switch (snapshot.state()) {
            case PREPARING, PREVIEW, PLACING, ROLLING_BACK -> true;
            default -> false;
        };
    }

    public CompletableFuture<Void> start(Path blueprintPath) {
        if (!integration.available()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(integration.unavailableReason()));
        }
        update(new CnbPlacementSnapshot(
                CnbPlacementState.PREPARING,
                "Loading blueprint",
                blueprintPath.getFileName().toString(),
                0, 0, 0, 0,
                0, 0, BlockPos.ORIGIN, false));
        int bits = integration.bitsPerBlockSide();
        return CompletableFuture.supplyAsync(() -> {
            try {
                CnbBlueprint loaded = store.read(blueprintPath);
                return prepare(loaded, bits, 0);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, worker).thenAccept(prepared -> client.execute(() -> activate(prepared)))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        client.execute(() -> fail(usefulMessage(failure)));
                    }
                });
    }

    public boolean canPlaceNow() {
        return integration.available()
                && client.isInSingleplayer()
                && client.player != null
                && client.player.isCreative()
                && client.world != null;
    }

    public String placementUnavailableReason() {
        if (!integration.available()) {
            return integration.unavailableReason();
        }
        if (!client.isInSingleplayer()) {
            return "Placement is available only in singleplayer";
        }
        if (client.player == null || client.world == null) {
            return "Open a world before placing a miniature";
        }
        if (!client.player.isCreative()) {
            return "Switch to Creative mode to place a miniature";
        }
        return "";
    }

    public void cancel() {
        CnbPlacementState state = snapshot.state();
        if (state == CnbPlacementState.PLACING
                || state == CnbPlacementState.ROLLING_BACK) {
            integration.cancelPlacement();
            update(copy(CnbPlacementState.ROLLING_BACK, "Rolling placement back", false));
        } else if (state == CnbPlacementState.PREVIEW
                || state == CnbPlacementState.PREPARING) {
            clear(CnbPlacementState.CANCELLED, "Placement cancelled");
        }
    }

    private void registerEvents() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        WorldRenderEvents.END_EXTRACTION.register(this::extractWorld);
        WorldRenderEvents.END_MAIN.register(this::renderWorld);
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.BOSS_BAR, HUD_ELEMENT, this::renderHud);
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() && snapshot.state() == CnbPlacementState.PREVIEW) {
                confirm();
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient() && snapshot.state() == CnbPlacementState.PREVIEW) {
                confirm();
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }

    private KeyBinding key(String translation, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                translation, GLFW.GLFW_KEY_UNKNOWN == code ? -1 : code, CATEGORY));
    }

    private void tick(MinecraftClient minecraft) {
        if (successTicks > 0 && --successTicks == 0
                && (snapshot.state() == CnbPlacementState.SUCCEEDED
                || snapshot.state() == CnbPlacementState.FAILED
                || snapshot.state() == CnbPlacementState.CANCELLED)) {
            snapshot = CnbPlacementSnapshot.inactive();
        }
        if (!active()) {
            return;
        }
        if (minecraft.world == null || minecraft.player == null) {
            cancel();
            return;
        }
        if (cancel.wasPressed()) {
            cancel();
            return;
        }
        if (snapshot.state() != CnbPlacementState.PREVIEW
                || minecraft.currentScreen != null) {
            return;
        }
        boolean changed = false;
        while (rotate.wasPressed()) {
            quarterTurns = Math.floorMod(
                    quarterTurns + (client.isShiftPressed() ? -1 : 1), 4);
            changed = true;
        }
        while (left.wasPressed()) {
            offsetX--;
        }
        while (right.wasPressed()) {
            offsetX++;
        }
        while (forward.wasPressed()) {
            offsetZ--;
        }
        while (backward.wasPressed()) {
            offsetZ++;
        }
        while (up.wasPressed()) {
            offsetY++;
        }
        while (down.wasPressed()) {
            offsetY--;
        }
        if (changed) {
            rebuildRotation();
            return;
        }
        updateTarget();
    }

    private void rebuildRotation() {
        CnbBlueprint current = blueprint;
        int turns = quarterTurns;
        int bits = integration.bitsPerBlockSide();
        update(copy(CnbPlacementState.PREPARING, "Rotating preview", false));
        CompletableFuture.supplyAsync(() -> prepare(current, bits, turns), worker)
                .whenComplete((prepared, failure) -> client.execute(() -> {
                    if (failure != null) {
                        fail(usefulMessage(failure));
                    } else {
                        activate(prepared);
                    }
                }));
    }

    private Prepared prepare(CnbBlueprint value, int bits, int turns) {
        return new Prepared(
                value,
                CnbPacking.pack(value, bits, turns),
                CnbPreviewMesh.build(value, bits, turns),
                turns);
    }

    private void activate(Prepared prepared) {
        if (snapshot.state() != CnbPlacementState.PREPARING) {
            return;
        }
        CnbPreviewBuffer uploaded;
        try {
            uploaded = CnbPreviewBuffer.upload(prepared.mesh());
        } catch (Throwable throwable) {
            fail("Cannot upload preview to GPU: " + usefulMessage(throwable));
            return;
        }
        closePreviewBuffer();
        blueprint = prepared.blueprint();
        packed = prepared.packed();
        previewBuffer = uploaded;
        quarterTurns = prepared.quarterTurns();
        invalidateValidationCache();
        updateTarget();
        if (snapshot.state() == CnbPlacementState.PREPARING) {
            update(copy(CnbPlacementState.PREVIEW, "Choose a position", false));
        }
    }

    private void updateTarget() {
        if (packed == null || blueprint == null) {
            return;
        }
        BlockPos anchor = null;
        BlockHitResult blockHit = distantBlockTarget();
        if (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK) {
            anchor = blockHit.getBlockPos().offset(blockHit.getSide());
        }
        if (anchor == null) {
            origin = null;
            update(new CnbPlacementSnapshot(
                    CnbPlacementState.PREVIEW,
                    "Look at a block face",
                    blueprint.name(),
                    quarterTurns * 90,
                    packed.width(), packed.height(), packed.depth(),
                    0, packed.hostBlocks().size(),
                    BlockPos.ORIGIN,
                    false));
            return;
        }
        origin = anchor.add(
                -packed.width() / 2 + offsetX,
                offsetY,
                -packed.depth() / 2 + offsetZ);
        boolean valid = validClientTarget(origin);
        update(new CnbPlacementSnapshot(
                CnbPlacementState.PREVIEW,
                valid ? "Right-click to place" : "Placement collides or is out of bounds",
                blueprint.name(),
                quarterTurns * 90,
                packed.width(), packed.height(), packed.depth(),
                0, packed.hostBlocks().size(),
                origin,
                valid));
    }

    private boolean validClientTarget(BlockPos placementOrigin) {
        if (!canPlaceNow()) {
            return false;
        }
        long worldTick = client.world.getTime();
        if (placementOrigin.equals(lastValidatedOrigin)
                && worldTick >= lastValidationTick
                && worldTick - lastValidationTick < VALIDATION_CACHE_TICKS) {
            return lastValidationResult;
        }
        boolean valid = true;
        for (CnbPackedModel.HostBlock host : packed.hostBlocks()) {
            BlockPos target = placementOrigin.add(host.x(), host.y(), host.z());
            if (client.world.isOutOfHeightLimit(target)
                    || !client.world.getWorldBorder().contains(target)
                    || !client.world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)
                    || !client.world.getBlockState(target).isAir()) {
                valid = false;
                break;
            }
        }
        lastValidatedOrigin = placementOrigin.toImmutable();
        lastValidationTick = worldTick;
        lastValidationResult = valid;
        return valid;
    }

    private BlockHitResult distantBlockTarget() {
        if (client.player == null || client.world == null) {
            return null;
        }
        Vec3d start = client.player.getCameraPosVec(1.0f);
        Vec3d end = start.add(
                client.player.getRotationVec(1.0f).multiply(ANCHOR_RAY_DISTANCE));
        BlockHitResult hit = client.world.raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                client.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private void confirm() {
        if (snapshot.state() != CnbPlacementState.PREVIEW || origin == null) {
            return;
        }
        if (!snapshot.valid()) {
            update(copy(
                    CnbPlacementState.PREVIEW,
                    "Cannot place: target blocks must all be empty",
                    false));
            return;
        }
        BlockPos requestedOrigin = origin.toImmutable();
        CnbPackedModel requestedPacked = packed;
        closePreviewBuffer();
        update(copy(CnbPlacementState.PLACING, "Placing miniature", false));
        integration.place(
                client,
                blueprint,
                requestedPacked,
                quarterTurns,
                requestedOrigin,
                progress -> update(new CnbPlacementSnapshot(
                        CnbPlacementState.PLACING,
                        progress.message(),
                        blueprint.name(),
                        quarterTurns * 90,
                        requestedPacked.width(), requestedPacked.height(),
                        requestedPacked.depth(),
                        progress.placedHostBlocks(), progress.totalHostBlocks(),
                        requestedOrigin,
                        true)))
                .whenComplete((result, failure) -> client.execute(() -> {
                    if (failure != null) {
                        fail(usefulMessage(failure));
                    } else {
                        update(new CnbPlacementSnapshot(
                                CnbPlacementState.SUCCEEDED,
                                "Miniature placed",
                                blueprint.name(),
                                quarterTurns * 90,
                                requestedPacked.width(), requestedPacked.height(),
                                requestedPacked.depth(),
                                result.hostBlocks(), result.hostBlocks(),
                                requestedOrigin,
                                true));
                        successTicks = 80;
                        if (client.player != null) {
                            client.player.sendMessage(Text.translatable(
                                    "minesplat.cnb.placed",
                                    result.hostBlocks(),
                                    result.bits()), false);
                        }
                    }
                }));
    }

    private void extractWorld(WorldExtractionContext context) {
        CnbPlacementSnapshot current = snapshot;
        CnbPreviewBuffer currentBuffer = previewBuffer;
        BlockPos currentOrigin = origin;
        PreviewRenderState renderState = null;
        if (current.state() == CnbPlacementState.PREVIEW
                && currentOrigin != null && currentBuffer != null) {
            Vec3d camera = context.camera().getCameraPos();
            renderState = new PreviewRenderState(
                    currentBuffer,
                    currentOrigin.getX() - camera.x,
                    currentOrigin.getY() - camera.y,
                    currentOrigin.getZ() - camera.z,
                    current.width(), current.height(), current.depth(),
                    current.valid());
        }
        ((FabricRenderState) context.worldState()).setData(
                PREVIEW_RENDER_STATE, renderState);
    }

    private void renderWorld(WorldRenderContext context) {
        PreviewRenderState current = ((FabricRenderState) context.worldState())
                .getData(PREVIEW_RENDER_STATE);
        if (current == null) {
            return;
        }
        current.buffer().draw(current.x(), current.y(), current.z(), current.valid());
        if (!current.valid()) {
            MatrixStack matrices = context.matrices();
            if (matrices != null) {
                VertexRendering.drawOutline(
                        matrices,
                        context.consumers().getBuffer(RenderLayers.lines()),
                        VoxelShapes.cuboid(
                                -0.002,
                                -0.002,
                                -0.002,
                                current.width() + 0.002,
                                current.height() + 0.002,
                                current.depth() + 0.002),
                        current.x(), current.y(), current.z(),
                        0xffff1a1a,
                        1.0f);
            }
        }
    }

    private void renderHud(DrawContext context, net.minecraft.client.render.RenderTickCounter tick) {
        CnbPlacementSnapshot current = snapshot;
        if (!active() && current.state() != CnbPlacementState.SUCCEEDED
                && current.state() != CnbPlacementState.FAILED) {
            return;
        }
        int x = 8;
        int y = 8;
        int color = current.valid() ? 0x55ff55 : 0xff5555;
        context.drawTextWithShadow(client.textRenderer,
                Text.translatable("minesplat.cnb.hud", current.blueprintName()),
                x, y, 0xffffff);
        context.drawTextWithShadow(client.textRenderer,
                Text.translatable(
                        "minesplat.cnb.hud_details",
                        current.width(), current.height(), current.depth(),
                        current.rotationDegrees()),
                x, y + 12, 0xdddddd);
        context.drawTextWithShadow(client.textRenderer,
                Text.literal(current.message()),
                x, y + 24, color);
        if (current.state() == CnbPlacementState.PLACING) {
            context.drawTextWithShadow(client.textRenderer,
                    Text.translatable(
                            "minesplat.cnb.hud_progress",
                            current.placedHostBlocks(), current.totalHostBlocks()),
                    x, y + 36, 0xffff55);
        } else if (current.state() == CnbPlacementState.PREVIEW) {
            context.drawTextWithShadow(client.textRenderer,
                    Text.translatable("minesplat.cnb.hud_controls"),
                    x, y + 36, 0xaaaaaa);
        }
    }

    private void fail(String message) {
        closePreviewBuffer();
        update(copy(CnbPlacementState.FAILED, message, false));
        successTicks = 100;
    }

    private void clear(CnbPlacementState state, String message) {
        blueprint = null;
        packed = null;
        closePreviewBuffer();
        origin = null;
        invalidateValidationCache();
        update(new CnbPlacementSnapshot(
                state, message, "", 0,
                0, 0, 0, 0, 0,
                BlockPos.ORIGIN, false));
        successTicks = 40;
    }

    private CnbPlacementSnapshot copy(
            CnbPlacementState state,
            String message,
            boolean valid
    ) {
        CnbPlacementSnapshot source = snapshot;
        return new CnbPlacementSnapshot(
                state,
                message,
                source.blueprintName(),
                quarterTurns * 90,
                packed == null ? source.width() : packed.width(),
                packed == null ? source.height() : packed.height(),
                packed == null ? source.depth() : packed.depth(),
                source.placedHostBlocks(),
                packed == null ? source.totalHostBlocks() : packed.hostBlocks().size(),
                origin == null ? BlockPos.ORIGIN : origin,
                valid);
    }

    private void update(CnbPlacementSnapshot value) {
        snapshot = value;
    }

    private void invalidateValidationCache() {
        lastValidatedOrigin = null;
        lastValidationTick = Long.MIN_VALUE;
    }

    private void closePreviewBuffer() {
        CnbPreviewBuffer current = previewBuffer;
        previewBuffer = null;
        if (current != null) {
            current.close();
        }
    }

    private static String usefulMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    @Override
    public void close() {
        cancel();
        closePreviewBuffer();
        worker.shutdownNow();
    }

    private record Prepared(
            CnbBlueprint blueprint,
            CnbPackedModel packed,
            CnbPreviewMesh mesh,
            int quarterTurns
    ) {
    }

    private record PreviewRenderState(
            CnbPreviewBuffer buffer,
            double x,
            double y,
            double z,
            int width,
            int height,
            int depth,
            boolean valid
    ) {
    }
}
