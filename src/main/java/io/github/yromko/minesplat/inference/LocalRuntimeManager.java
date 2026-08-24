package io.github.yromko.minesplat.inference;

import com.google.gson.Gson;
import io.github.yromko.minesplat.api.ApiModels.ConnectionInfo;
import io.github.yromko.minesplat.api.TripoSplatApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

public final class LocalRuntimeManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineSplat Local Runtime");
    private static final Gson GSON = new Gson();
    private static final String MANIFEST_RESOURCE =
            "/assets/minesplat/triposplat/runtime-manifest.json";
    private static final int START_ATTEMPTS = 5;
    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);

    private final Path runtimeRoot;
    private final Path artifactDirectory;
    private final LocalModelManager models;
    private final ExecutorService worker;
    private final RuntimeManifest manifest;
    private final String platform;
    private final AutoCloseable modelSubscription;
    private final CopyOnWriteArrayList<Consumer<LocalRuntimeSnapshot>> listeners =
            new CopyOnWriteArrayList<>();
    private final Deque<String> recentOutput = new ArrayDeque<>();

    private volatile LocalRuntimeSnapshot snapshot;
    private Process process;
    private int runningDevice = -1;
    private boolean stopping;
    private CompletableFuture<Endpoint> activePrepare;

    public LocalRuntimeManager(Path gameDirectory, LocalModelManager models) {
        this.runtimeRoot = gameDirectory.resolve("minesplat/runtime")
                .resolve(TripoSplatRuntimeVersion.SOURCE_COMMIT.substring(0, 8));
        this.artifactDirectory = gameDirectory.resolve("minesplat/local-artifacts");
        this.models = models;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MineSplat local runtime");
            thread.setDaemon(true);
            return thread;
        });
        this.manifest = loadManifest();
        this.platform = detectPlatform();
        this.models.converter(this::convertModels);
        if (platform == null || platformEntry() == null) {
            snapshot = new LocalRuntimeSnapshot(
                    LocalRuntimeState.UNSUPPORTED, platformName(),
                    "Local inference is not available on this platform",
                    null, null, 0, null);
        } else if (!models.ready()) {
            snapshot = new LocalRuntimeSnapshot(
                    LocalRuntimeState.MISSING_MODELS, platform,
                    "Install TripoSplat models", null, null, 0, null);
        } else {
            snapshot = LocalRuntimeSnapshot.stopped(platform);
        }
        this.modelSubscription = models.listen(this::modelsChanged);
    }

    public LocalRuntimeSnapshot snapshot() {
        return snapshot;
    }

    public String platform() {
        return platformName();
    }

    public boolean supported() {
        return platform != null && platformEntry() != null;
    }

    public synchronized boolean running() {
        return process != null && process.isAlive()
                && snapshot.state() == LocalRuntimeState.READY;
    }

    public synchronized String failureMessage() {
        return snapshot.state() == LocalRuntimeState.FAILED
                ? (snapshot.error() == null ? snapshot.message() : snapshot.error())
                : null;
    }

    public AutoCloseable listen(Consumer<LocalRuntimeSnapshot> listener) {
        listeners.add(listener);
        listener.accept(snapshot);
        return () -> listeners.remove(listener);
    }

    public synchronized CompletableFuture<Endpoint> prepare(int deviceIndex) {
        return prepare(deviceIndex, false);
    }

    public synchronized CompletableFuture<Endpoint> prepare(
            int deviceIndex,
            boolean requireTextModels
    ) {
        if (!supported()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Local inference is unsupported on " + platformName()));
        }
        if (!models.ready()) {
            update(new LocalRuntimeSnapshot(
                    LocalRuntimeState.MISSING_MODELS, platform,
                    "Install and verify TripoSplat models first",
                    models.snapshot().error(), null, deviceIndex, null));
            return CompletableFuture.failedFuture(
                    new IllegalStateException("TripoSplat models are not installed"));
        }
        if (requireTextModels && !models.textReady()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Install prompt generation models first"));
        }
        if (running() && runningDevice == deviceIndex) {
            return CompletableFuture.completedFuture(new Endpoint(
                    snapshot.baseUrl(), snapshot.device(), deviceIndex));
        }
        if (activePrepare != null && !activePrepare.isDone()) {
            return activePrepare.thenCompose(endpoint -> endpoint.deviceIndex() == deviceIndex
                    ? CompletableFuture.completedFuture(endpoint)
                    : prepare(deviceIndex));
        }
        activePrepare = CompletableFuture.supplyAsync(() -> start(deviceIndex), worker);
        activePrepare.whenComplete((endpoint, failure) -> {
            synchronized (this) {
                activePrepare = null;
            }
            if (failure != null && snapshot.state() != LocalRuntimeState.FAILED) {
                update(new LocalRuntimeSnapshot(
                        LocalRuntimeState.FAILED, platform,
                        "Cannot start local TripoSplat runtime",
                        usefulMessage(failure), null, deviceIndex, null));
            }
        });
        return activePrepare;
    }

    private void convertModels(
            Path modelDirectory,
            BooleanSupplier cancelled,
            Consumer<String> output
    ) throws IOException, InterruptedException {
        if (!supported()) {
            throw new IOException("Local inference is unsupported on " + platformName());
        }
        Path executable = extract(platformEntry());
        ProcessBuilder builder = new ProcessBuilder(
                executable.toString(),
                "download",
                "--model-dir", modelDirectory.toString(),
                "--repo", "VAST-AI/TripoSplat",
                "--revision", TripoSplatRuntimeVersion.MODEL_REVISION);
        builder.directory(executable.getParent().toFile());
        builder.redirectErrorStream(true);
        Process conversion = builder.start();
        StringBuilder captured = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                conversion.getInputStream(), StandardCharsets.UTF_8))) {
            while (conversion.isAlive()) {
                if (cancelled.getAsBoolean()) {
                    conversion.destroy();
                    if (!conversion.waitFor(2, TimeUnit.SECONDS)) {
                        conversion.destroyForcibly();
                    }
                    throw new CancellationException("Model conversion cancelled");
                }
                while (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    output.accept(line);
                    if (captured.length() < 8192) {
                        captured.append(line).append('\n');
                    }
                }
                conversion.waitFor(100, TimeUnit.MILLISECONDS);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                output.accept(line);
                if (captured.length() < 8192) {
                    captured.append(line).append('\n');
                }
            }
        } finally {
            if (conversion.isAlive()) {
                conversion.destroyForcibly();
            }
        }
        if (conversion.exitValue() != 0) {
            String detail = captured.toString().trim();
            throw new IOException(detail.isBlank()
                    ? "TripoSplat model conversion failed"
                    : detail);
        }
    }

    public synchronized void stop() {
        stopInternal();
        if (supported()) {
            update(models.ready()
                    ? LocalRuntimeSnapshot.stopped(platform)
                    : new LocalRuntimeSnapshot(
                    LocalRuntimeState.MISSING_MODELS, platform,
                    "Install TripoSplat models", null, null, 0, null));
        }
    }

    private Endpoint start(int deviceIndex) {
        synchronized (this) {
            stopInternal();
        }
        update(new LocalRuntimeSnapshot(
                LocalRuntimeState.STARTING, platform,
                "Extracting local TripoSplat runtime",
                null, null, deviceIndex, null));
        PlatformRuntime runtime = platformEntry();
        Path executable = extract(runtime);
        for (int attempt = 0; attempt < START_ATTEMPTS; attempt++) {
            int port = availablePort();
            String baseUrl = "http://127.0.0.1:" + port;
            update(new LocalRuntimeSnapshot(
                    LocalRuntimeState.STARTING, platform,
                    "Starting local TripoSplat REST API",
                    null, null, deviceIndex, baseUrl));
            Process candidate = launch(executable, port, deviceIndex);
            synchronized (this) {
                process = candidate;
                runningDevice = deviceIndex;
                stopping = false;
                recentOutput.clear();
            }
            drain(candidate);
            ConnectionInfo info = awaitReady(candidate, baseUrl);
            if (info != null) {
                String device = info.selectedDevice() == null
                        ? "Vulkan device " + deviceIndex : info.selectedDevice().name();
                update(new LocalRuntimeSnapshot(
                        LocalRuntimeState.READY, platform,
                        "Local TripoSplat REST API is ready",
                        null, device, deviceIndex, baseUrl));
                return new Endpoint(baseUrl, device, deviceIndex);
            }
            String output = recentOutput();
            synchronized (this) {
                stopInternal();
            }
            if (!output.contains("failed to start REST server")) {
                throw new IllegalStateException(output.isBlank()
                        ? "TripoSplat process exited before becoming ready" : output);
            }
        }
        throw new IllegalStateException("Cannot bind local TripoSplat REST API port");
    }

    private Process launch(Path executable, int port, int deviceIndex) {
        try {
            Files.createDirectories(artifactDirectory);
            ProcessBuilder builder = new ProcessBuilder(
                    executable.toString(),
                    "serve",
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(port),
                    "--model-dir", models.directory().toString(),
                    "--artifact-dir", artifactDirectory.toString(),
                    "--artifact-ttl", "24h",
                    "--max-artifacts", "20",
                    "--max-artifact-bytes", "10GiB",
                    "--cleanup-interval", "60s",
                    "--device", Integer.toString(deviceIndex));
            builder.directory(executable.getParent().toFile());
            builder.redirectErrorStream(true);
            return builder.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot launch TripoSplat runtime", exception);
        }
    }

    private ConnectionInfo awaitReady(Process candidate, String baseUrl) {
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        TripoSplatApiClient api = new TripoSplatApiClient(baseUrl);
        while (candidate.isAlive() && System.nanoTime() < deadline) {
            try {
                return api.testConnection().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Local runtime startup interrupted", exception);
                }
            }
        }
        if (candidate.isAlive()) {
            throw new IllegalStateException("Local TripoSplat startup timed out");
        }
        return null;
    }

    private Path extract(PlatformRuntime runtime) {
        try {
            Path destinationRoot = runtimeRoot.resolve(runtime.platform());
            for (RuntimeFile file : runtime.files()) {
                Path destination = destinationRoot.resolve(file.path()).normalize();
                if (!destination.startsWith(destinationRoot)) {
                    throw new IllegalStateException("Runtime manifest path escapes destination");
                }
                if (validFile(destination, file)) {
                    continue;
                }
                Files.createDirectories(destination.getParent());
                Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
                try (InputStream input = LocalRuntimeManager.class
                        .getResourceAsStream(file.resource())) {
                    if (input == null) {
                        throw new IOException("Missing bundled runtime resource " + file.resource());
                    }
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!validFile(temporary, file)) {
                    Files.deleteIfExists(temporary);
                    throw new IOException("Bundled runtime failed SHA-256 verification: "
                            + file.path());
                }
                moveAtomically(temporary, destination);
            }
            Path executable = destinationRoot.resolve(runtime.executable());
            if (runtime.platform().startsWith("linux")) {
                makeExecutable(executable);
            }
            return executable;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot extract local TripoSplat runtime", exception);
        }
    }

    private void drain(Process candidate) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    candidate.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.info("[TripoSplat] {}", line);
                    synchronized (this) {
                        recentOutput.addLast(line);
                        while (recentOutput.size() > 40) {
                            recentOutput.removeFirst();
                        }
                    }
                }
            } catch (IOException exception) {
                LOGGER.debug("TripoSplat output reader stopped", exception);
            } finally {
                processExited(candidate);
            }
        }, "MineSplat TripoSplat output");
        thread.setDaemon(true);
        thread.start();
    }

    private synchronized void processExited(Process candidate) {
        if (process != candidate) {
            return;
        }
        process = null;
        runningDevice = -1;
        if (!stopping && snapshot.state() == LocalRuntimeState.READY) {
            update(new LocalRuntimeSnapshot(
                    LocalRuntimeState.FAILED, platform,
                    "Local TripoSplat process exited",
                    recentOutput(), null, snapshot.deviceIndex(), null));
        }
    }

    private synchronized void stopInternal() {
        Process current = process;
        if (current == null) {
            runningDevice = -1;
            return;
        }
        stopping = true;
        current.destroy();
        try {
            if (!current.waitFor(2, TimeUnit.SECONDS)) {
                current.destroyForcibly();
                current.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
        if (process == current) {
            process = null;
        }
        runningDevice = -1;
        stopping = false;
    }

    private void modelsChanged(LocalModelSnapshot modelSnapshot) {
        if (!supported() || running()) {
            return;
        }
        if (modelSnapshot.state() == LocalModelState.READY
                && snapshot.state() == LocalRuntimeState.MISSING_MODELS) {
            update(LocalRuntimeSnapshot.stopped(platform));
        } else if (modelSnapshot.state() != LocalModelState.READY) {
            if (snapshot.state() != LocalRuntimeState.MISSING_MODELS
                    || !Objects.equals(snapshot.error(), modelSnapshot.error())) {
                update(new LocalRuntimeSnapshot(
                        LocalRuntimeState.MISSING_MODELS, platform,
                        "Install TripoSplat models", modelSnapshot.error(),
                        null, 0, null));
            }
        }
    }

    private PlatformRuntime platformEntry() {
        if (platform == null || manifest.platforms() == null) {
            return null;
        }
        return manifest.platforms().stream()
                .filter(value -> platform.equals(value.platform()))
                .findFirst().orElse(null);
    }

    static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = architecture.equals("amd64") || architecture.equals("x86_64");
        if (!x64) {
            return null;
        }
        if (os.contains("win")) {
            return "windows-x86_64";
        }
        if (os.contains("linux")) {
            return "linux-x86_64";
        }
        return null;
    }

    private String platformName() {
        return platform == null
                ? System.getProperty("os.name", "unknown") + "/"
                + System.getProperty("os.arch", "unknown")
                : platform;
    }

    private static RuntimeManifest loadManifest() {
        try (InputStream input = LocalRuntimeManager.class.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing TripoSplat runtime manifest");
            }
            RuntimeManifest value = GSON.fromJson(new InputStreamReader(
                    input, StandardCharsets.UTF_8), RuntimeManifest.class);
            if (value == null
                    || !TripoSplatRuntimeVersion.RELEASE.equals(value.release())
                    || !TripoSplatRuntimeVersion.SOURCE_COMMIT.equals(value.sourceCommit())
                    || value.platforms() == null || value.platforms().isEmpty()) {
                throw new IllegalStateException("Invalid TripoSplat runtime manifest");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read TripoSplat runtime manifest", exception);
        }
    }

    private static boolean validFile(Path path, RuntimeFile file) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) != file.size()) {
                return false;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) {
                        digest.update(buffer, 0, count);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest()).equals(file.sha256());
        } catch (Exception exception) {
            return false;
        }
    }

    private static void makeExecutable(Path executable) throws IOException {
        try {
            Files.setPosixFilePermissions(executable, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException exception) {
            if (!executable.toFile().setExecutable(true, false)) {
                throw new IOException("Cannot make TripoSplat runtime executable");
            }
        }
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0));
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot allocate local REST API port", exception);
        }
    }

    private synchronized String recentOutput() {
        return String.join(" | ", recentOutput);
    }

    private void update(LocalRuntimeSnapshot value) {
        snapshot = value;
        for (Consumer<LocalRuntimeSnapshot> listener : listeners) {
            try {
                listener.accept(value);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String usefulMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof java.util.concurrent.CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }

    @Override
    public synchronized void close() {
        stopInternal();
        worker.shutdownNow();
        try {
            modelSubscription.close();
        } catch (Exception ignored) {
        }
    }

    public record Endpoint(String baseUrl, String device, int deviceIndex) {
    }

    private record RuntimeManifest(
            String release,
            String sourceCommit,
            List<PlatformRuntime> platforms
    ) {
    }

    private record PlatformRuntime(
            String platform,
            String executable,
            List<RuntimeFile> files
    ) {
    }

    private record RuntimeFile(String path, String resource, long size, String sha256) {
    }
}
