package io.github.yromko.minesplat.workflow;

import io.github.yromko.minesplat.api.ApiException;
import io.github.yromko.minesplat.api.ApiModels.ConnectionInfo;
import io.github.yromko.minesplat.api.ApiModels.Job;
import io.github.yromko.minesplat.api.TripoSplatApiClient;
import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.config.VoxelPreset;
import io.github.yromko.minesplat.litematica.SchematicExporter;
import io.github.yromko.minesplat.palette.BlockPalette;
import io.github.yromko.minesplat.palette.ColorMatcher;
import io.github.yromko.minesplat.palette.MatchedVoxels;
import io.github.yromko.minesplat.util.ImageFiles;
import io.github.yromko.minesplat.voxel.TsvoxGrid;
import io.github.yromko.minesplat.voxel.TsvoxReader;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class MineSplatController implements AutoCloseable {
    private static final long[] POLL_RETRY_SECONDS = {1, 2, 4};

    private final ExecutorService worker;
    private final ScheduledExecutorService scheduler;
    private final BlockPalette palette;
    private final ColorMatcher colorMatcher = new ColorMatcher();
    private final TsvoxReader tsvoxReader = new TsvoxReader();
    private final SchematicExporter litematica;
    private final CopyOnWriteArrayList<Consumer<GenerationSnapshot>> listeners =
            new CopyOnWriteArrayList<>();

    private volatile GenerationSnapshot snapshot = GenerationSnapshot.idle();
    private Session session;
    private PausedPoll pausedPoll;

    public MineSplatController(BlockPalette palette, SchematicExporter litematica) {
        this.palette = palette;
        this.litematica = litematica;
        this.worker = Executors.newSingleThreadExecutor(daemonFactory("MineSplat worker"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("MineSplat polling"));
    }

    public GenerationSnapshot snapshot() {
        return snapshot;
    }

    public synchronized boolean canReuseGeneration(
            String serverUrl,
            Path image,
            long seed
    ) {
        if (session == null || session.plyArtifactId == null || image == null) {
            return false;
        }
        GenerationRequest previous = session.request;
        try {
            return previous.seed() == seed
                    && previous.image().toAbsolutePath().normalize()
                    .equals(image.toAbsolutePath().normalize())
                    && TripoSplatApiClient.normalizeBaseUrl(previous.serverUrl())
                    .equals(TripoSplatApiClient.normalizeBaseUrl(serverUrl));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public synchronized boolean hasSession() {
        return session != null && session.plyArtifactId != null;
    }

    public AutoCloseable listen(Consumer<GenerationSnapshot> listener) {
        listeners.add(listener);
        listener.accept(snapshot);
        return () -> listeners.remove(listener);
    }

    public CompletableFuture<ConnectionInfo> testConnection(String serverUrl) {
        final TripoSplatApiClient api;
        try {
            api = new TripoSplatApiClient(serverUrl);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return api.testConnection().whenComplete((info, error) -> {
            if (error == null) {
                String device = info.selectedDevice() == null
                        ? "No selected device"
                        : info.selectedDevice().name();
                update(copy(snapshot, snapshot.state(), "API connected", null)
                        .withDevice(device));
            }
        });
    }

    public synchronized CompletableFuture<Path> start(GenerationRequest request) {
        if (snapshot.state().active()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A MineSplat operation is already running"));
        }
        try {
            ImageFiles.validate(request.image());
            if (request.seed() < 0) {
                throw new IllegalArgumentException("Seed must be non-negative");
            }
            TripoSplatApiClient.normalizeBaseUrl(request.serverUrl());
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }

        closeSessionArtifacts();
        Session next;
        try {
            next = new Session(request, new TripoSplatApiClient(request.serverUrl()));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        session = next;
        pausedPoll = null;
        update(new GenerationSnapshot(
                GenerationState.UPLOADING,
                "Uploading image",
                null,
                snapshot.device(),
                null,
                false,
                false,
                request.preset().resolution(),
                0, 0, 0, 0,
                Map.of(),
                null));

        CompletableFuture<Path> flow = next.api.uploadImage(request.image())
                .thenApply(artifact -> {
                    ensureActive(next);
                    next.inputArtifactId = required(artifact.id(), "uploaded input artifact");
                    return next.inputArtifactId;
                })
                .thenCompose(inputId -> next.api.enqueueGeneration(inputId, request.seed()))
                .thenCompose(queued -> {
                    ensureActive(next);
                    next.currentJobId = required(queued.job_id(), "generation job");
                    next.stage = JobStage.GENERATION;
                    next.lastJobWasQueued = true;
                    setJob(next, GenerationState.GENERATION_QUEUED, "Generation queued");
                    return pollUntilTerminal(next);
                })
                .thenCompose(job -> finishGeneration(next, job))
                .thenCompose(ignored -> voxelize(next, request.preset(), request.paletteProfile(),
                        request.blacklistedBlocks(), request.schematicName()));

        attachTerminal(next, flow);
        return flow;
    }

    public synchronized CompletableFuture<Path> rebuildOrRevoxelize(
            String schematicName,
            VoxelPreset preset,
            PaletteProfile profile,
            Set<String> blacklist
    ) {
        Session current = session;
        if (current == null || current.plyArtifactId == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Generate an image before reusing a session"));
        }
        if (snapshot.state().active()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A MineSplat operation is already running"));
        }
        current.cancelled = false;
        pausedPoll = null;

        CompletableFuture<Path> flow;
        if (current.grid != null && current.grid.resolution() == preset.resolution()) {
            flow = convertAndSave(current, schematicName, preset, profile, blacklist, current.grid);
        } else {
            flow = voxelize(current, preset, profile, blacklist, schematicName);
        }
        attachTerminal(current, flow);
        return flow;
    }

    public synchronized void cancel() {
        Session current = session;
        if (current == null || !snapshot.state().active()) {
            return;
        }
        if (current.stage != null
                && !current.lastJobWasQueued
                && (snapshot.state() == GenerationState.GENERATION_RUNNING
                || snapshot.state() == GenerationState.VOXELIZATION_RUNNING)) {
            update(copy(snapshot, snapshot.state(),
                    "The server API cannot cancel a running GPU job", null));
            return;
        }

        current.cancelled = true;
        PausedPoll paused = pausedPoll;
        pausedPoll = null;
        if (paused != null) {
            paused.result().completeExceptionally(new JobFailedException("Cancelled"));
        }
        if (current.currentJobId != null && current.lastJobWasQueued) {
            current.api.cancelJob(current.currentJobId);
        }
        update(copy(snapshot, GenerationState.CANCELLED, "Cancelled", null)
                .withJob(null, false, false));
    }

    public synchronized void resumePolling() {
        PausedPoll paused = pausedPoll;
        if (paused == null || paused.session().cancelled) {
            return;
        }
        pausedPoll = null;
        update(copy(snapshot, snapshot.state(), "Polling resumed", null)
                .withJob(paused.session().currentJobId,
                        paused.session().lastJobWasQueued, false));
        pollAttempt(paused.session(), paused.result(), 0);
    }

    public synchronized void finishSession() {
        if (snapshot.state().active()) {
            throw new IllegalStateException("Cannot finish an active MineSplat session");
        }
        closeSessionArtifacts();
        session = null;
        pausedPoll = null;
        update(GenerationSnapshot.idle());
    }

    private CompletableFuture<Void> finishGeneration(Session current, Job job) {
        ensureActive(current);
        current.plyArtifactId = required(job.artifact("gaussian_ply"), "generation PLY artifact");
        current.splatArtifactId = job.artifact("splat");
        String input = current.inputArtifactId;
        String splat = current.splatArtifactId;
        current.inputArtifactId = null;
        current.splatArtifactId = null;
        current.currentJobId = null;
        current.stage = null;
        return CompletableFuture.allOf(
                current.api.deleteArtifact(input),
                current.api.deleteArtifact(splat));
    }

    private CompletableFuture<Path> voxelize(
            Session current,
            VoxelPreset preset,
            PaletteProfile profile,
            Set<String> blacklist,
            String schematicName
    ) {
        ensureActive(current);
        current.grid = null;
        update(copy(snapshot, GenerationState.VOXELIZATION_QUEUED,
                "Submitting voxelization " + preset.resolution() + "³", null)
                .withResolution(preset.resolution())
                .withResult(0, 0, 0, 0, Map.of(), null));

        return current.api.enqueueVoxelization(current.plyArtifactId, preset.resolution())
                .thenCompose(queued -> {
                    ensureActive(current);
                    current.currentJobId = required(queued.job_id(), "voxelization job");
                    current.stage = JobStage.VOXELIZATION;
                    current.lastJobWasQueued = true;
                    setJob(current, GenerationState.VOXELIZATION_QUEUED, "Voxelization queued");
                    return pollUntilTerminal(current);
                })
                .thenCompose(job -> {
                    ensureActive(current);
                    current.outputArtifactId =
                            required(job.artifact("output"), "TSVOX output artifact");
                    current.currentJobId = null;
                    current.stage = null;
                    transition(GenerationState.DOWNLOADING, "Downloading TSVOX");
                    String output = current.outputArtifactId;
                    return current.api.downloadArtifact(output, preset.resolution())
                            .whenComplete((bytes, error) -> {
                                current.api.deleteArtifact(output);
                                current.outputArtifactId = null;
                            });
                })
                .thenApplyAsync(bytes -> {
                    ensureActive(current);
                    transition(GenerationState.CONVERTING, "Reading TSVOX");
                    TsvoxGrid grid = tsvoxReader.read(bytes, preset.resolution());
                    current.grid = grid;
                    return grid;
                }, worker)
                .thenCompose(grid -> convertAndSave(
                        current, schematicName, preset, profile, blacklist, grid));
    }

    private CompletableFuture<Path> convertAndSave(
            Session current,
            String schematicName,
            VoxelPreset preset,
            PaletteProfile profile,
            Set<String> blacklist,
            TsvoxGrid grid
    ) {
        update(copy(snapshot, GenerationState.CONVERTING,
                "Matching voxel colors to Minecraft blocks", null)
                .withResolution(preset.resolution())
                .withResult(0, 0, 0, 0, Map.of(), null));
        return CompletableFuture.supplyAsync(() -> {
            ensureActive(current);
            return colorMatcher.match(grid, palette.candidates(profile, blacklist));
        }, worker).thenCompose(matched -> {
            ensureActive(current);
            update(copy(snapshot, GenerationState.SAVING, "Creating Litematica schematic", null)
                    .withResult(
                            matched.width(), matched.height(), matched.depth(),
                            matched.size(), matched.materialCounts(), null));
            return litematica.saveAndPlace(
                    schematicName,
                    preset.resolution(),
                    matched,
                    state -> transition(state,
                            state == GenerationState.PLACING
                                    ? "Placing schematic in Litematica"
                                    : "Saving .litematic"));
        });
    }

    private CompletableFuture<Job> pollUntilTerminal(Session current) {
        CompletableFuture<Job> result = new CompletableFuture<>();
        pollAttempt(current, result, 0);
        return result;
    }

    private void pollAttempt(
            Session current,
            CompletableFuture<Job> result,
            int transportRetry
    ) {
        if (result.isDone()) {
            return;
        }
        if (current.cancelled) {
            result.completeExceptionally(new JobFailedException("Cancelled"));
            return;
        }
        current.api.getJob(current.currentJobId).whenComplete((job, failure) -> {
            if (failure != null) {
                Throwable cause = unwrap(failure);
                if (transientNetworkFailure(cause)) {
                    if (transportRetry < POLL_RETRY_SECONDS.length) {
                        long delay = POLL_RETRY_SECONDS[transportRetry];
                        update(copy(snapshot, snapshot.state(),
                                "Network error; retrying polling in " + delay + " s",
                                cause.getMessage()));
                        scheduler.schedule(
                                () -> pollAttempt(current, result, transportRetry + 1),
                                delay,
                                TimeUnit.SECONDS);
                    } else {
                        synchronized (this) {
                            pausedPoll = new PausedPoll(current, result);
                        }
                        update(copy(snapshot, snapshot.state(),
                                "Polling paused; use Continue polling",
                                cause.getMessage())
                                .withJob(current.currentJobId,
                                        current.lastJobWasQueued, true));
                    }
                } else {
                    result.completeExceptionally(cause);
                }
                return;
            }

            String status = job.status();
            if ("queued".equals(status)) {
                current.lastJobWasQueued = true;
                setJob(current,
                        current.stage == JobStage.GENERATION
                                ? GenerationState.GENERATION_QUEUED
                                : GenerationState.VOXELIZATION_QUEUED,
                        current.stage == JobStage.GENERATION
                                ? "Generation queued" : "Voxelization queued");
            } else if ("running".equals(status)) {
                current.lastJobWasQueued = false;
                setJob(current,
                        current.stage == JobStage.GENERATION
                                ? GenerationState.GENERATION_RUNNING
                                : GenerationState.VOXELIZATION_RUNNING,
                        current.stage == JobStage.GENERATION
                                ? "Generation running" : "Voxelization running");
            } else if ("succeeded".equals(status)) {
                result.complete(job);
                return;
            } else if ("failed".equals(status)
                    || "cancelled".equals(status)
                    || "expired".equals(status)) {
                String detail = job.error() == null || job.error().isBlank()
                        ? "Server job " + status
                        : job.error();
                result.completeExceptionally(new JobFailedException(detail));
                return;
            } else {
                result.completeExceptionally(
                        new ApiException(0, "invalid_status", "Unknown server job status: " + status));
                return;
            }
            scheduler.schedule(() -> pollAttempt(current, result, 0), 1, TimeUnit.SECONDS);
        });
    }

    private synchronized void attachTerminal(Session current, CompletableFuture<Path> flow) {
        current.activeFlow = flow;
        flow.whenComplete((path, failure) -> {
            if (session != current) {
                return;
            }
            current.activeFlow = null;
            current.currentJobId = null;
            current.stage = null;
            if (current.cancelled) {
                update(copy(snapshot, GenerationState.CANCELLED, "Cancelled", null)
                        .withJob(null, false, false));
            } else if (failure != null) {
                Throwable cause = unwrap(failure);
                update(copy(snapshot, GenerationState.FAILED, "MineSplat failed",
                        usefulMessage(cause)).withJob(null, false, false));
            } else {
                update(copy(snapshot, GenerationState.SUCCEEDED,
                        "Schematic saved and placed", null)
                        .withJob(null, false, false)
                        .withOutput(path));
            }
        });
    }

    private synchronized void closeSessionArtifacts() {
        Session old = session;
        if (old == null) {
            return;
        }
        old.api.deleteArtifact(old.inputArtifactId);
        old.api.deleteArtifact(old.splatArtifactId);
        old.api.deleteArtifact(old.outputArtifactId);
        old.api.deleteArtifact(old.plyArtifactId);
        old.inputArtifactId = null;
        old.splatArtifactId = null;
        old.outputArtifactId = null;
        old.plyArtifactId = null;
    }

    private void ensureActive(Session expected) {
        if (session != expected || expected.cancelled) {
            throw new JobFailedException("Cancelled");
        }
    }

    private void setJob(Session current, GenerationState state, String message) {
        update(copy(snapshot, state, message, null)
                .withJob(current.currentJobId, current.lastJobWasQueued, false));
    }

    private void transition(GenerationState state, String message) {
        update(copy(snapshot, state, message, null));
    }

    private void update(SnapshotBuilder builder) {
        update(builder.build());
    }

    private void update(GenerationSnapshot value) {
        snapshot = value;
        for (Consumer<GenerationSnapshot> listener : listeners) {
            try {
                listener.accept(value);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static SnapshotBuilder copy(
            GenerationSnapshot source,
            GenerationState state,
            String message,
            String error
    ) {
        return new SnapshotBuilder(source, state, message, error);
    }

    private static String required(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new ApiException(0, "missing_id", "Server omitted " + description);
        }
        return value;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean transientNetworkFailure(Throwable throwable) {
        return throwable instanceof IOException
                || throwable instanceof ConnectException
                || throwable instanceof HttpTimeoutException;
    }

    private static String usefulMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public synchronized void close() {
        if (session != null) {
            session.cancelled = true;
        }
        closeSessionArtifacts();
        worker.shutdownNow();
        scheduler.shutdownNow();
    }

    private enum JobStage {
        GENERATION,
        VOXELIZATION
    }

    private static final class Session {
        private final GenerationRequest request;
        private final TripoSplatApiClient api;
        private boolean cancelled;
        private boolean lastJobWasQueued;
        private JobStage stage;
        private String currentJobId;
        private String inputArtifactId;
        private String plyArtifactId;
        private String splatArtifactId;
        private String outputArtifactId;
        private TsvoxGrid grid;
        private CompletableFuture<Path> activeFlow;

        private Session(GenerationRequest request, TripoSplatApiClient api) {
            this.request = request;
            this.api = api;
        }
    }

    private record PausedPoll(Session session, CompletableFuture<Job> result) {
    }

    private static final class SnapshotBuilder {
        private GenerationState state;
        private String message;
        private String error;
        private String device;
        private String jobId;
        private boolean queued;
        private boolean pollingPaused;
        private int resolution;
        private int width;
        private int height;
        private int depth;
        private int blockCount;
        private Map<String, Integer> materials;
        private Path outputFile;

        private SnapshotBuilder(
                GenerationSnapshot source,
                GenerationState state,
                String message,
                String error
        ) {
            this.state = state;
            this.message = message;
            this.error = error;
            this.device = source.device();
            this.jobId = source.jobId();
            this.queued = source.queued();
            this.pollingPaused = source.pollingPaused();
            this.resolution = source.resolution();
            this.width = source.width();
            this.height = source.height();
            this.depth = source.depth();
            this.blockCount = source.blockCount();
            this.materials = source.materials();
            this.outputFile = source.outputFile();
        }

        private SnapshotBuilder withDevice(String value) {
            device = value;
            return this;
        }

        private SnapshotBuilder withJob(String id, boolean isQueued, boolean isPollingPaused) {
            jobId = id;
            queued = isQueued;
            pollingPaused = isPollingPaused;
            return this;
        }

        private SnapshotBuilder withResolution(int value) {
            resolution = value;
            return this;
        }

        private SnapshotBuilder withResult(
                int resultWidth,
                int resultHeight,
                int resultDepth,
                int resultBlocks,
                Map<String, Integer> resultMaterials,
                Path path
        ) {
            width = resultWidth;
            height = resultHeight;
            depth = resultDepth;
            blockCount = resultBlocks;
            materials = resultMaterials;
            outputFile = path;
            return this;
        }

        private SnapshotBuilder withOutput(Path path) {
            outputFile = path;
            return this;
        }

        private GenerationSnapshot build() {
            return new GenerationSnapshot(
                    state, message, error, device, jobId, queued, pollingPaused,
                    resolution, width, height, depth, blockCount, materials, outputFile);
        }
    }
}
