package io.github.yromko.minesplat.workflow;

import io.github.yromko.minesplat.api.ApiException;
import io.github.yromko.minesplat.api.ApiModels.ConnectionInfo;
import io.github.yromko.minesplat.api.ApiModels.Job;
import io.github.yromko.minesplat.api.TripoSplatApiClient;
import io.github.yromko.minesplat.cnb.CnbBlueprintExporter;
import io.github.yromko.minesplat.cnb.CnbIntegration;
import io.github.yromko.minesplat.cnb.UnavailableCnbIntegration;
import io.github.yromko.minesplat.config.GenerationPreset;
import io.github.yromko.minesplat.config.OutputMode;
import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.config.VoxelPreset;
import io.github.yromko.minesplat.litematica.SchematicExporter;
import io.github.yromko.minesplat.inference.InferenceTarget;
import io.github.yromko.minesplat.inference.PreparedEndpoint;
import io.github.yromko.minesplat.inference.TripoSplatEndpointResolver;
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
import java.util.function.Supplier;

public final class MineSplatController implements AutoCloseable {
    private static final long[] POLL_RETRY_SECONDS = {1, 2, 4};

    private final ExecutorService worker;
    private final ScheduledExecutorService scheduler;
    private final BlockPalette palette;
    private final ColorMatcher colorMatcher = new ColorMatcher();
    private final TsvoxReader tsvoxReader = new TsvoxReader();
    private final SchematicExporter litematica;
    private final CnbBlueprintExporter cnbBlueprints;
    private final CnbIntegration cnb;
    private final TripoSplatEndpointResolver endpoints;
    private final CopyOnWriteArrayList<Consumer<GenerationSnapshot>> listeners =
            new CopyOnWriteArrayList<>();

    private volatile GenerationSnapshot snapshot = GenerationSnapshot.idle();
    private Session session;
    private PausedPoll pausedPoll;

    public MineSplatController(BlockPalette palette, SchematicExporter litematica) {
        this(
                palette,
                litematica,
                null,
                new UnavailableCnbIntegration("Chisels & Bits is not installed"),
                new TripoSplatEndpointResolver(null));
    }

    public MineSplatController(
            BlockPalette palette,
            SchematicExporter litematica,
            CnbBlueprintExporter cnbBlueprints,
            CnbIntegration cnb
    ) {
        this(palette, litematica, cnbBlueprints, cnb,
                new TripoSplatEndpointResolver(null));
    }

    public MineSplatController(
            BlockPalette palette,
            SchematicExporter litematica,
            CnbBlueprintExporter cnbBlueprints,
            CnbIntegration cnb,
            TripoSplatEndpointResolver endpoints
    ) {
        this.palette = palette;
        this.litematica = litematica;
        this.cnbBlueprints = cnbBlueprints;
        this.cnb = cnb;
        this.endpoints = endpoints;
        this.worker = Executors.newSingleThreadExecutor(daemonFactory("MineSplat worker"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("MineSplat polling"));
    }

    public GenerationSnapshot snapshot() {
        return snapshot;
    }

    public synchronized boolean canReuseGeneration(
            InferenceTarget target,
            GenerationSource source,
            long seed
    ) {
        return canReuseGeneration(target, source, seed, GenerationPreset.XHIGH);
    }

    public synchronized boolean canReuseGeneration(
            InferenceTarget target,
            GenerationSource source,
            long seed,
            GenerationPreset generationPreset
    ) {
        if (session == null || session.plyArtifactId == null || source == null) {
            return false;
        }
        GenerationRequest previous = session.request;
        try {
            return previous.seed() == seed
                    && previous.source().equals(source)
                    && previous.generationPreset() == generationPreset
                    && session.backendIdentity != null
                    && session.backendIdentity.equals(target.identity());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public synchronized boolean canReuseGeneration(
            InferenceTarget target,
            Path image,
            long seed
    ) {
        return image != null && canReuseGeneration(
                target, new GenerationSource.Image(image), seed, GenerationPreset.XHIGH);
    }

    public synchronized boolean canReuseGeneration(
            InferenceTarget target,
            Path image,
            long seed,
            GenerationPreset generationPreset
    ) {
        return image != null && canReuseGeneration(
                target, new GenerationSource.Image(image), seed, generationPreset);
    }

    public synchronized boolean hasSession() {
        return session != null && session.plyArtifactId != null;
    }

    public AutoCloseable listen(Consumer<GenerationSnapshot> listener) {
        listeners.add(listener);
        listener.accept(snapshot);
        return () -> listeners.remove(listener);
    }

    public CompletableFuture<ConnectionInfo> testConnection(InferenceTarget target) {
        return endpoints.test(target).whenComplete((info, error) -> {
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
            if (request.source() instanceof GenerationSource.Image image) {
                ImageFiles.validate(image.path());
            }
            if (request.seed() < 0) {
                throw new IllegalArgumentException("Seed must be non-negative");
            }
            request.target().identity();
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }

        closeSessionArtifacts();
        Session next = new Session(request);
        session = next;
        pausedPoll = null;
        update(new GenerationSnapshot(
                GenerationState.UPLOADING,
                "Preparing inference backend",
                null,
                snapshot.device(),
                null,
                false,
                false,
                request.preset().resolution(),
                0, 0, 0, 0,
                Map.of(),
                null,
                request.outputMode(),
                -1.0,
                -1.0));
        next.outputMode = request.outputMode();

        boolean prompt = request.source() instanceof GenerationSource.Prompt;
        CompletableFuture<Path> flow = endpoints.prepare(request.target(), prompt)
                .thenCompose(endpoint -> {
                    ensureActive(next);
                    next.attach(endpoint);
                    if (request.source() instanceof GenerationSource.Image image) {
                        transition(GenerationState.UPLOADING, "Uploading image");
                        return next.api.uploadImage(image.path())
                                .thenCompose(artifact -> {
                                    ensureActive(next);
                                    next.inputArtifactId = required(
                                            artifact.id(), "uploaded input artifact");
                                    return next.api.enqueueGeneration(
                                            next.inputArtifactId, request.seed(),
                                            request.generationPreset());
                                });
                    }
                    GenerationSource.Prompt text = (GenerationSource.Prompt) request.source();
                    transition(GenerationState.UPLOADING, "Submitting prompt");
                    return next.api.enqueueTextGeneration(
                            text.text(), request.seed(), request.generationPreset());
                })
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
                        request.blacklistedBlocks(), request.schematicName(),
                        request.outputMode()));

        attachTerminal(next, flow);
        return flow;
    }

    public synchronized CompletableFuture<Path> rebuildOrRevoxelize(
            String schematicName,
            VoxelPreset preset,
            PaletteProfile profile,
            Set<String> blacklist,
            OutputMode outputMode
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
        current.outputMode = outputMode;
        pausedPoll = null;

        CompletableFuture<Path> flow;
        if (current.grid != null && current.grid.resolution() == preset.resolution()) {
            flow = convertAndSave(
                    current, schematicName, preset, profile, blacklist, current.grid, outputMode);
        } else {
            flow = voxelize(current, preset, profile, blacklist, schematicName, outputMode);
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
        boolean prompt = current.request.source() instanceof GenerationSource.Prompt;
        double imageSeconds = prompt
                ? metric(job, "image_elapsed_seconds") : -1.0;
        double modelSeconds = metric(
                job, prompt ? "triposplat_elapsed_seconds" : "elapsed_seconds");
        update(copy(snapshot, snapshot.state(), snapshot.message(), snapshot.error())
                .withGenerationTimes(imageSeconds, modelSeconds));
        current.plyArtifactId = required(job.artifact("gaussian_ply"), "generation PLY artifact");
        current.splatArtifactId = job.artifact("splat");
        if (prompt) {
            current.generatedImageArtifactId = required(
                    job.artifact("image"), "generated image artifact");
        }
        String input = current.inputArtifactId;
        String splat = current.splatArtifactId;
        String generatedImage = current.generatedImageArtifactId;
        current.inputArtifactId = null;
        current.splatArtifactId = null;
        current.generatedImageArtifactId = null;
        current.currentJobId = null;
        current.stage = null;
        return CompletableFuture.allOf(
                current.api.deleteArtifact(input),
                current.api.deleteArtifact(splat),
                current.api.deleteArtifact(generatedImage));
    }

    private CompletableFuture<Path> voxelize(
            Session current,
            VoxelPreset preset,
            PaletteProfile profile,
            Set<String> blacklist,
            String schematicName,
            OutputMode outputMode
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
                        current, schematicName, preset, profile, blacklist, grid, outputMode));
    }

    private CompletableFuture<Path> convertAndSave(
            Session current,
            String schematicName,
            VoxelPreset preset,
            PaletteProfile profile,
            Set<String> blacklist,
            TsvoxGrid grid,
            OutputMode outputMode
    ) {
        update(copy(snapshot, GenerationState.CONVERTING,
                "Matching voxel colors to Minecraft blocks", null)
                .withResolution(preset.resolution())
                .withResult(0, 0, 0, 0, Map.of(), null));
        var baseCandidates = palette.candidates(profile, blacklist);
        CompletableFuture<java.util.List<io.github.yromko.minesplat.palette.PaletteEntry>>
                candidates = outputMode == OutputMode.CHISELS_AND_BITS
                ? cnb.filterEligible(baseCandidates)
                : CompletableFuture.completedFuture(baseCandidates);
        return candidates.thenCompose(eligible -> CompletableFuture.supplyAsync(() -> {
            ensureActive(current);
            return colorMatcher.match(grid, eligible);
        }, worker)).thenCompose(matched -> {
            ensureActive(current);
            String message = outputMode == OutputMode.CHISELS_AND_BITS
                    ? "Saving Chisels & Bits blueprint"
                    : "Creating Litematica schematic";
            update(copy(snapshot, GenerationState.SAVING, message, null)
                    .withOutputMode(outputMode)
                    .withResult(
                            matched.width(), matched.height(), matched.depth(),
                            matched.size(), matched.materialCounts(), null));
            if (outputMode == OutputMode.CHISELS_AND_BITS) {
                if (cnbBlueprints == null) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException(cnb.unavailableReason()));
                }
                return cnbBlueprints.save(
                        schematicName, preset.resolution(), profile, matched);
            }
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
                String runtimeFailure = current.runtimeFailure.get();
                if (runtimeFailure != null && !runtimeFailure.isBlank()) {
                    result.completeExceptionally(new JobFailedException(runtimeFailure));
                    return;
                }
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
                String message = current.outputMode == OutputMode.CHISELS_AND_BITS
                        ? "Chisels & Bits blueprint saved"
                        : "Schematic saved and placed";
                update(copy(snapshot, GenerationState.SUCCEEDED,
                        message, null)
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
        if (old.api == null) {
            return;
        }
        old.api.deleteArtifact(old.inputArtifactId);
        old.api.deleteArtifact(old.generatedImageArtifactId);
        old.api.deleteArtifact(old.splatArtifactId);
        old.api.deleteArtifact(old.outputArtifactId);
        old.api.deleteArtifact(old.plyArtifactId);
        old.inputArtifactId = null;
        old.generatedImageArtifactId = null;
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

    private static double metric(Job job, String name) {
        if (job.metrics() == null) {
            return -1.0;
        }
        Object value = job.metrics().get(name);
        if (!(value instanceof Number number)) {
            return -1.0;
        }
        double seconds = number.doubleValue();
        return Double.isFinite(seconds) && seconds >= 0.0 ? seconds : -1.0;
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
        endpoints.close();
    }

    private enum JobStage {
        GENERATION,
        VOXELIZATION
    }

    private static final class Session {
        private final GenerationRequest request;
        private TripoSplatApiClient api;
        private String backendIdentity;
        private Supplier<String> runtimeFailure = () -> null;
        private boolean cancelled;
        private boolean lastJobWasQueued;
        private JobStage stage;
        private String currentJobId;
        private String inputArtifactId;
        private String generatedImageArtifactId;
        private String plyArtifactId;
        private String splatArtifactId;
        private String outputArtifactId;
        private TsvoxGrid grid;
        private CompletableFuture<Path> activeFlow;
        private OutputMode outputMode = OutputMode.LITEMATICA;

        private Session(GenerationRequest request) {
            this.request = request;
        }

        private void attach(PreparedEndpoint endpoint) {
            api = endpoint.api();
            backendIdentity = endpoint.identity();
            runtimeFailure = endpoint.runtimeFailure();
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
        private OutputMode outputMode;
        private double imageGenerationSeconds;
        private double modelGenerationSeconds;

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
            this.outputMode = source.outputMode();
            this.imageGenerationSeconds = source.imageGenerationSeconds();
            this.modelGenerationSeconds = source.modelGenerationSeconds();
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

        private SnapshotBuilder withOutputMode(OutputMode value) {
            outputMode = value;
            return this;
        }

        private SnapshotBuilder withGenerationTimes(
                double imageSeconds,
                double modelSeconds
        ) {
            imageGenerationSeconds = imageSeconds;
            modelGenerationSeconds = modelSeconds;
            return this;
        }

        private GenerationSnapshot build() {
            return new GenerationSnapshot(
                    state, message, error, device, jobId, queued, pollingPaused,
                    resolution, width, height, depth, blockCount, materials, outputFile,
                    outputMode, imageGenerationSeconds, modelGenerationSeconds);
        }
    }
}
