package io.github.yromko.minesplat.inference;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalModelManager implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final long FREE_SPACE_MARGIN = 512L * 1024L * 1024L;
    private static final int MAX_CONCURRENT_DOWNLOADS = 3;
    private static final long PROGRESS_PUBLISH_INTERVAL_NANOS = 100_000_000L;
    private static final Pattern CONTENT_RANGE = Pattern.compile(
            "bytes\\s+(\\d+)-(\\d+)/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final String MANIFEST_RESOURCE =
            "/assets/minesplat/triposplat/model-manifest.json";

    private final HttpClient http;
    private final ExecutorService worker;
    private final ExecutorService downloadWorkers;
    private final Map<LocalModelSet, List<ModelFile>> files =
            new EnumMap<>(LocalModelSet.class);
    private final Map<LocalModelSet, Long> totalBytes =
            new EnumMap<>(LocalModelSet.class);
    private final Map<LocalModelSet, CopyOnWriteArrayList<Consumer<LocalModelSnapshot>>>
            listeners = new EnumMap<>(LocalModelSet.class);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<CompletableFuture<?>> activeRequests = ConcurrentHashMap.newKeySet();
    private final Set<InputStream> activeBodies = ConcurrentHashMap.newKeySet();

    private volatile Path directory;
    private volatile LocalModelSnapshot coreSnapshot;
    private volatile LocalModelSnapshot textSnapshot;
    private volatile CompletableFuture<Void> activeInstall;
    private volatile LocalModelSet activeSet;
    private volatile ModelConverter converter;

    public LocalModelManager(Path directory) {
        this(directory, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build());
    }

    LocalModelManager(Path directory, HttpClient http) {
        this(directory, http, loadManifest());
    }

    LocalModelManager(Path directory, HttpClient http, String manifestJson) {
        this(directory, http, parseManifest(new StringReader(manifestJson)));
    }

    private LocalModelManager(Path directory, HttpClient http, ModelManifest manifest) {
        this.directory = normalize(directory);
        this.http = http;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MineSplat model manager");
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger downloadThread = new AtomicInteger();
        this.downloadWorkers = Executors.newFixedThreadPool(
                MAX_CONCURRENT_DOWNLOADS,
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "MineSplat model download " + downloadThread.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        for (LocalModelSet set : LocalModelSet.values()) {
            listeners.put(set, new CopyOnWriteArrayList<>());
        }
        for (LocalModelSet set : LocalModelSet.values()) {
            List<ModelFile> selected = manifest.files().stream()
                    .filter(file -> file.modelSet() == set)
                    .toList();
            if (selected.isEmpty()) {
                throw new IllegalStateException("Empty TripoSplat model set: " + set.id());
            }
            files.put(set, selected);
            totalBytes.put(set, selected.stream().mapToLong(ModelFile::size).sum());
        }
        coreSnapshot = LocalModelSnapshot.checking(this.directory);
        textSnapshot = LocalModelSnapshot.checking(this.directory);
        refresh();
    }

    public LocalModelSnapshot snapshot() {
        return snapshot(LocalModelSet.CORE);
    }

    public LocalModelSnapshot textSnapshot() {
        return snapshot(LocalModelSet.TEXT);
    }

    public LocalModelSnapshot snapshot(LocalModelSet set) {
        return set == LocalModelSet.CORE ? coreSnapshot : textSnapshot;
    }

    public Path directory() {
        return directory;
    }

    public boolean ready() {
        return ready(LocalModelSet.CORE);
    }

    public boolean textReady() {
        return ready(LocalModelSet.TEXT);
    }

    public boolean ready(LocalModelSet set) {
        return snapshot(set).state() == LocalModelState.READY;
    }

    public boolean installing() {
        return activeInstall != null;
    }

    public LocalModelSet activeSet() {
        return activeSet;
    }

    public AutoCloseable listen(Consumer<LocalModelSnapshot> listener) {
        return listen(LocalModelSet.CORE, listener);
    }

    public AutoCloseable listenText(Consumer<LocalModelSnapshot> listener) {
        return listen(LocalModelSet.TEXT, listener);
    }

    public AutoCloseable listen(
            LocalModelSet set,
            Consumer<LocalModelSnapshot> listener
    ) {
        listeners.get(set).add(listener);
        listener.accept(snapshot(set));
        return () -> listeners.get(set).remove(listener);
    }

    void converter(ModelConverter value) {
        converter = value;
    }

    public synchronized CompletableFuture<Boolean> setDirectory(Path value) {
        if (installing()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Cannot change model directory while installing"));
        }
        directory = normalize(value);
        update(LocalModelSet.CORE, LocalModelSnapshot.checking(directory));
        update(LocalModelSet.TEXT, LocalModelSnapshot.checking(directory));
        return refresh();
    }

    public synchronized CompletableFuture<Boolean> refresh() {
        Path expectedDirectory = directory;
        update(LocalModelSet.CORE, LocalModelSnapshot.checking(expectedDirectory));
        update(LocalModelSet.TEXT, LocalModelSnapshot.checking(expectedDirectory));
        return CompletableFuture.supplyAsync(() -> {
            boolean coreReady = verifyAllInstalled(expectedDirectory, LocalModelSet.CORE);
            boolean textReady = verifyAllInstalled(expectedDirectory, LocalModelSet.TEXT);
            if (!directory.equals(expectedDirectory)) {
                return false;
            }
            publishVerification(expectedDirectory, LocalModelSet.CORE, coreReady);
            publishVerification(expectedDirectory, LocalModelSet.TEXT, textReady);
            return coreReady;
        }, worker).exceptionally(failure -> {
            if (directory.equals(expectedDirectory)) {
                String error = usefulMessage(failure);
                update(LocalModelSet.CORE, failed(expectedDirectory, LocalModelSet.CORE, error));
                update(LocalModelSet.TEXT, failed(expectedDirectory, LocalModelSet.TEXT, error));
            }
            return false;
        });
    }

    public synchronized CompletableFuture<Void> install() {
        return install(LocalModelSet.CORE);
    }

    public synchronized CompletableFuture<Void> installText() {
        return install(LocalModelSet.TEXT);
    }

    public synchronized CompletableFuture<Void> install(LocalModelSet set) {
        if (installing()) {
            if (activeSet == set) {
                return activeInstall;
            }
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Another model set is currently installing"));
        }
        cancelled.set(false);
        activeSet = set;
        Path targetDirectory = directory;
        update(set, verifying(targetDirectory, set, null, approximateStoredBytes(
                targetDirectory, set)));
        CompletableFuture<Void> install = new CompletableFuture<>();
        activeInstall = install;
        try {
            CompletableFuture.runAsync(
                    () -> installAll(targetDirectory, set), worker)
                    .whenComplete((ignored, failure) -> finishInstall(
                            install, targetDirectory, set, failure));
        } catch (RuntimeException failure) {
            finishInstall(install, targetDirectory, set, failure);
        }
        return install;
    }

    private void finishInstall(
            CompletableFuture<Void> install,
            Path targetDirectory,
            LocalModelSet set,
            Throwable failure
    ) {
        Throwable cause = unwrap(failure);
        synchronized (this) {
            if (activeInstall == install) {
                if (directory.equals(targetDirectory)) {
                    if (cause instanceof CancellationException) {
                        publishVerification(targetDirectory, set, false);
                    } else if (cause != null) {
                        update(set, failed(targetDirectory, set, usefulMessage(cause)));
                    } else {
                        publishVerification(targetDirectory, set, true);
                    }
                }
                activeInstall = null;
                activeSet = null;
            }
        }
        if (cause == null) {
            install.complete(null);
        } else {
            install.completeExceptionally(cause);
        }
    }

    public void cancelInstall() {
        cancelActiveTransfers();
    }

    private void installAll(Path targetDirectory, LocalModelSet set) {
        try {
            Files.createDirectories(targetDirectory);
            List<PreparedFile> plan = prepareFiles(targetDirectory, set);
            long required = plan.stream().mapToLong(PreparedFile::requiredDownloadBytes).sum()
                    + conversionScratchBytes(set, plan);
            FileStore store = Files.getFileStore(targetDirectory);
            if (store.getUsableSpace() < required + FREE_SPACE_MARGIN) {
                throw new IOException(
                        "Not enough disk space for TripoSplat " + set.id()
                                + " models; need " + (required + FREE_SPACE_MARGIN)
                                + " free bytes");
            }
            DownloadProgress progress = new DownloadProgress(
                    set, targetDirectory, plan, totalBytes.get(set));
            downloadAll(plan, progress);

            boolean needsConversion = set == LocalModelSet.CORE && plan.stream()
                    .anyMatch(file -> file.file().converted()
                            && file.kind() != PreparedKind.INSTALLED);
            if (needsConversion) {
                ModelConverter selectedConverter = converter;
                if (selectedConverter == null) {
                    throw new IOException("Bundled TripoSplat converter is unavailable");
                }
                update(set, new LocalModelSnapshot(
                        LocalModelState.CONVERTING, targetDirectory, null,
                        totalBytes.get(set), totalBytes.get(set), null));
                selectedConverter.convert(
                        targetDirectory,
                        cancelled::get,
                        line -> update(set, new LocalModelSnapshot(
                                LocalModelState.CONVERTING, targetDirectory, line,
                                totalBytes.get(set), totalBytes.get(set), null)));
                requireNotCancelled();
                verifyConvertedOutputs(set, targetDirectory, plan);
            }
        } catch (CancellationException exception) {
            throw exception;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(
                    "Cannot install TripoSplat " + set.id() + " models", exception);
        }
    }

    private List<PreparedFile> prepareFiles(
            Path targetDirectory,
            LocalModelSet set
    ) throws IOException {
        List<PreparedFile> result = new ArrayList<>();
        long present = 0;
        for (ModelFile file : files.get(set)) {
            requireNotCancelled();
            update(set, verifying(targetDirectory, set, file.path(), present));
            Path destination = resolve(targetDirectory, file.path());
            Path partial = destination.resolveSibling(destination.getFileName() + ".part");
            PreparedKind kind = classifyExisting(destination, file);
            long partialBytes = 0;
            if (kind == PreparedKind.MISSING && Files.isRegularFile(partial)) {
                partialBytes = Files.size(partial);
                if (partialBytes > file.size()) {
                    Files.delete(partial);
                    partialBytes = 0;
                }
            }
            PreparedFile prepared = new PreparedFile(
                    file, destination, partial, kind, partialBytes);
            result.add(prepared);
            present += prepared.initialEquivalentBytes();
            update(set, verifying(targetDirectory, set, file.path(), present));
        }
        return List.copyOf(result);
    }

    private PreparedKind classifyExisting(Path path, ModelFile expected) {
        try {
            if (!Files.isRegularFile(path)) {
                return PreparedKind.MISSING;
            }
            long size = Files.size(path);
            boolean couldBeInstalled = size == expected.effectiveInstalledSize();
            boolean couldBeSource = size == expected.size();
            if (!couldBeInstalled && !couldBeSource) {
                return PreparedKind.MISSING;
            }
            String digest = sha256(path, this::installationCancelled);
            if (couldBeInstalled && digest.equals(expected.effectiveInstalledSha256())) {
                return PreparedKind.INSTALLED;
            }
            if (couldBeSource && digest.equals(expected.sha256())) {
                return expected.converted()
                        ? PreparedKind.SOURCE : PreparedKind.INSTALLED;
            }
            return PreparedKind.MISSING;
        } catch (IOException exception) {
            return PreparedKind.MISSING;
        }
    }

    private void downloadAll(
            List<PreparedFile> plan,
            DownloadProgress progress
    ) throws IOException, InterruptedException {
        List<PreparedFile> missing = plan.stream()
                .filter(PreparedFile::needsDownload)
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        ExecutorCompletionService<Void> completion =
                new ExecutorCompletionService<>(downloadWorkers);
        List<Future<Void>> futures = new ArrayList<>();
        for (PreparedFile prepared : missing) {
            futures.add(completion.submit(() -> {
                download(prepared, progress);
                return null;
            }));
        }
        Throwable firstFailure = null;
        try {
            for (int index = 0; index < futures.size(); index++) {
                try {
                    completion.take().get();
                } catch (ExecutionException | CancellationException exception) {
                    Throwable cause = exception instanceof ExecutionException
                            ? unwrap(exception.getCause()) : exception;
                    if (firstFailure == null) {
                        firstFailure = cause;
                    }
                    cancelActiveTransfers();
                }
            }
            if (firstFailure instanceof CancellationException cancellation) {
                throw cancellation;
            }
            if (firstFailure instanceof IOException io) {
                throw io;
            }
            if (firstFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            if (firstFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (firstFailure != null) {
                throw new IOException("Model download failed", firstFailure);
            }
        } catch (InterruptedException exception) {
            cancelActiveTransfers();
            cancelDownloads(futures);
            throw exception;
        } finally {
            progress.close();
        }
    }

    private static void cancelDownloads(List<Future<Void>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    private void download(
            PreparedFile prepared,
            DownloadProgress progress
    ) throws IOException, InterruptedException {
        ModelFile file = prepared.file();
        Path partial = prepared.partial();
        Files.createDirectories(prepared.destination().getParent());
        long existing = Files.isRegularFile(partial) ? Files.size(partial) : 0;
        if (existing > file.size()) {
            Files.delete(partial);
            existing = 0;
        }
        progress.setBytes(file, existing);

        MessageDigest digest = newSha256();
        if (existing > 0) {
            progress.publishVerifying(file, true);
            digest = digestFile(partial, this::installationCancelled);
            if (existing == file.size()) {
                if (digestHex(digest).equals(file.sha256())) {
                    moveAtomically(partial, prepared.destination());
                    return;
                }
                Files.delete(partial);
                existing = 0;
                progress.setBytes(file, 0);
                progress.publishDownloading(file, true);
                digest = newSha256();
            }
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            requireNotCancelled();
            progress.publishDownloading(file, true);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(file.url()))
                    .timeout(Duration.ofHours(6))
                    .header("User-Agent", "MineSplat")
                    .GET();
            if (existing > 0) {
                builder.header("Range", "bytes=" + existing + "-");
            }
            HttpResponse<InputStream> response = sendRequest(builder.build());
            InputStream body = response.body();
            activeBodies.add(body);
            long written;
            try (InputStream input = body) {
                requireNotCancelled();
                int status = response.statusCode();
                if (status == 416 && existing > 0) {
                    Files.deleteIfExists(partial);
                    existing = 0;
                    progress.setBytes(file, 0);
                    progress.publishDownloading(file, true);
                    digest = newSha256();
                    continue;
                }
                if (status != 200 && status != 206) {
                    throw new IOException("Model download returned HTTP " + status
                            + " for " + file.path());
                }
                if (status == 206) {
                    validateContentRange(response, existing, file);
                }
                boolean append = status == 206 && existing > 0;
                if (!append) {
                    existing = 0;
                    progress.setBytes(file, 0);
                    progress.publishDownloading(file, true);
                    digest = newSha256();
                }
                written = existing;
                StandardOpenOption[] options = append
                        ? new StandardOpenOption[]{StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND}
                        : new StandardOpenOption[]{StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
                try (OutputStream output = Files.newOutputStream(partial, options)) {
                    byte[] buffer = new byte[256 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        requireNotCancelled();
                        if (count == 0) {
                            continue;
                        }
                        if (written + count > file.size()) {
                            throw new IOException("Model download exceeded expected size: "
                                    + file.path());
                        }
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        written += count;
                        progress.setBytes(file, written);
                        progress.publishDownloading(file, false);
                    }
                }
            } catch (IOException exception) {
                if (installationCancelled()) {
                    throw new CancellationException("Model installation cancelled");
                }
                throw exception;
            } finally {
                activeBodies.remove(body);
            }
            requireNotCancelled();
            if (written != file.size()) {
                throw new IOException("Model has unexpected size after download: "
                        + file.path());
            }
            progress.publishVerifying(file, true);
            if (!digestHex(digest).equals(file.sha256())) {
                throw new IOException("Downloaded model failed SHA-256 verification: "
                        + file.path());
            }
            moveAtomically(partial, prepared.destination());
            return;
        }
        throw new IOException("Cannot resume model download: " + file.path());
    }

    private HttpResponse<InputStream> sendRequest(HttpRequest request)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<InputStream>> requestFuture =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        activeRequests.add(requestFuture);
        if (installationCancelled()) {
            requestFuture.cancel(true);
        }
        try {
            return requestFuture.get();
        } catch (CancellationException exception) {
            throw new CancellationException("Model installation cancelled");
        } catch (ExecutionException exception) {
            Throwable cause = unwrap(exception.getCause());
            if (installationCancelled() || cause instanceof CancellationException) {
                throw new CancellationException("Model installation cancelled");
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Model download request failed", cause);
        } catch (InterruptedException exception) {
            requestFuture.cancel(true);
            throw exception;
        } finally {
            activeRequests.remove(requestFuture);
        }
    }

    private void cancelActiveTransfers() {
        cancelled.set(true);
        activeRequests.forEach(request -> request.cancel(true));
        activeBodies.forEach(LocalModelManager::closeQuietly);
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void validateContentRange(
            HttpResponse<?> response,
            long expectedStart,
            ModelFile file
    ) throws IOException {
        String header = response.headers().firstValue("Content-Range")
                .orElseThrow(() -> new IOException(
                        "Missing Content-Range for " + file.path()));
        ContentRange range = parseContentRange(header);
        if (range.start() != expectedStart
                || range.end() < range.start()
                || range.end() != file.size() - 1
                || range.total() != file.size()) {
            throw new IOException("Unexpected Content-Range for " + file.path()
                    + ": " + header);
        }
    }

    static ContentRange parseContentRange(String value) throws IOException {
        Matcher matcher = CONTENT_RANGE.matcher(value == null ? "" : value.trim());
        if (!matcher.matches()) {
            throw new IOException("Invalid Content-Range: " + value);
        }
        try {
            return new ContentRange(
                    Long.parseLong(matcher.group(1)),
                    Long.parseLong(matcher.group(2)),
                    Long.parseLong(matcher.group(3)));
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid Content-Range: " + value, exception);
        }
    }

    private void verifyConvertedOutputs(
            LocalModelSet set,
            Path targetDirectory,
            List<PreparedFile> plan
    ) throws IOException {
        for (PreparedFile prepared : plan) {
            ModelFile file = prepared.file();
            if (!file.converted() || prepared.kind() == PreparedKind.INSTALLED) {
                continue;
            }
            requireNotCancelled();
            update(set, verifying(targetDirectory, set, file.path(), totalBytes.get(set)));
            if (!verifyFile(prepared.destination(), file.effectiveInstalledSize(),
                    file.effectiveInstalledSha256(), this::installationCancelled)) {
                throw new IOException("Converted model failed SHA-256 verification: "
                        + file.path());
            }
        }
    }

    private boolean verifyAllInstalled(Path targetDirectory, LocalModelSet set) {
        for (ModelFile file : files.get(set)) {
            if (!verifyFile(resolve(targetDirectory, file.path()),
                    file.effectiveInstalledSize(), file.effectiveInstalledSha256(),
                    () -> Thread.currentThread().isInterrupted())) {
                return false;
            }
        }
        return true;
    }

    private static long conversionScratchBytes(
            LocalModelSet set,
            List<PreparedFile> plan
    ) {
        if (set != LocalModelSet.CORE) {
            return 0;
        }
        return plan.stream()
                .filter(file -> file.file().converted())
                .filter(file -> file.kind() != PreparedKind.INSTALLED)
                .mapToLong(file -> file.file().effectiveInstalledSize())
                .max().orElse(0);
    }

    private static boolean verifyFile(
            Path path,
            long size,
            String expectedSha256,
            BooleanSupplier cancelled
    ) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) != size) {
                return false;
            }
            return sha256(path, cancelled).equals(expectedSha256);
        } catch (IOException exception) {
            return false;
        }
    }

    static String sha256(Path path, BooleanSupplier cancelled) throws IOException {
        return digestHex(digestFile(path, cancelled));
    }

    private static MessageDigest digestFile(
            Path path,
            BooleanSupplier cancelled
    ) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                requireNotCancelled(cancelled);
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        requireNotCancelled(cancelled);
        return digest;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String digestHex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    private void publishVerification(
            Path targetDirectory,
            LocalModelSet set,
            boolean ready
    ) {
        update(set, new LocalModelSnapshot(
                ready ? LocalModelState.READY : LocalModelState.MISSING,
                targetDirectory,
                null,
                ready ? totalBytes.get(set) : approximateStoredBytes(targetDirectory, set),
                totalBytes.get(set),
                null));
    }

    private LocalModelSnapshot verifying(
            Path targetDirectory,
            LocalModelSet set,
            String file,
            long presentBytes
    ) {
        return new LocalModelSnapshot(
                LocalModelState.VERIFYING, targetDirectory, file,
                Math.min(presentBytes, totalBytes.get(set)), totalBytes.get(set), null);
    }

    private LocalModelSnapshot failed(
            Path targetDirectory,
            LocalModelSet set,
            String error
    ) {
        return new LocalModelSnapshot(
                LocalModelState.FAILED, targetDirectory, null,
                approximateStoredBytes(targetDirectory, set), totalBytes.get(set), error);
    }

    private long approximateStoredBytes(Path targetDirectory, LocalModelSet set) {
        long result = 0;
        for (ModelFile file : files.get(set)) {
            Path destination = resolve(targetDirectory, file.path());
            Path partial = destination.resolveSibling(destination.getFileName() + ".part");
            try {
                if (Files.isRegularFile(destination)) {
                    long size = Files.size(destination);
                    if (size == file.size() || size == file.effectiveInstalledSize()) {
                        result += file.size();
                        continue;
                    }
                }
                if (Files.isRegularFile(partial)) {
                    result += Math.min(Files.size(partial), file.size());
                }
            } catch (IOException ignored) {
            }
        }
        return Math.min(result, totalBytes.get(set));
    }

    private void update(LocalModelSet set, LocalModelSnapshot value) {
        if (set == LocalModelSet.CORE) {
            coreSnapshot = value;
        } else {
            textSnapshot = value;
        }
        for (Consumer<LocalModelSnapshot> listener : listeners.get(set)) {
            try {
                listener.accept(value);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static Path resolve(Path directory, String relative) {
        Path result = directory.resolve(relative).normalize();
        if (!result.startsWith(directory)) {
            throw new IllegalArgumentException("Model path escapes model directory");
        }
        return result;
    }

    private void requireNotCancelled() {
        requireNotCancelled(this::installationCancelled);
    }

    private boolean installationCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    private static void requireNotCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Model installation cancelled");
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ModelManifest loadManifest() {
        try (InputStream input = LocalModelManager.class.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing TripoSplat model manifest");
            }
            return parseManifest(new java.io.InputStreamReader(
                    input, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read TripoSplat model manifest", exception);
        }
    }

    private static ModelManifest parseManifest(Reader reader) {
        try {
            ModelManifest manifest = GSON.fromJson(reader, ModelManifest.class);
            if (manifest == null || manifest.files() == null || manifest.files().isEmpty()
                    || !TripoSplatRuntimeVersion.MODEL_REVISION.equals(manifest.revision())) {
                throw new IllegalStateException("Invalid TripoSplat model manifest");
            }
            manifest.files().forEach(ModelFile::validate);
            for (LocalModelSet set : LocalModelSet.values()) {
                if (manifest.files().stream().noneMatch(file -> file.modelSet() == set)) {
                    throw new IllegalStateException("Empty TripoSplat model set: " + set.id());
                }
            }
            return manifest;
        } catch (JsonParseException exception) {
            throw new IllegalStateException("Cannot read TripoSplat model manifest", exception);
        }
    }

    private static Path normalize(Path value) {
        if (value == null) {
            throw new IllegalArgumentException("Model directory is required");
        }
        return value.toAbsolutePath().normalize();
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof java.util.concurrent.CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String usefulMessage(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause == null) {
            return null;
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }

    @Override
    public synchronized void close() {
        cancelInstall();
        worker.shutdownNow();
        downloadWorkers.shutdownNow();
    }

    @FunctionalInterface
    interface ModelConverter {
        void convert(Path directory, BooleanSupplier cancelled, Consumer<String> output)
                throws IOException, InterruptedException;
    }

    private final class DownloadProgress {
        private final LocalModelSet set;
        private final Path targetDirectory;
        private final long total;
        private final Map<ModelFile, Long> bytesByFile = new HashMap<>();
        private long completed;
        private long lastPublicationNanos;
        private boolean closed;

        private DownloadProgress(
                LocalModelSet set,
                Path targetDirectory,
                List<PreparedFile> plan,
                long total
        ) {
            this.set = set;
            this.targetDirectory = targetDirectory;
            this.total = total;
            for (PreparedFile prepared : plan) {
                long initial = prepared.initialEquivalentBytes();
                bytesByFile.put(prepared.file(), initial);
                completed += initial;
            }
        }

        synchronized void setBytes(ModelFile file, long bytes) {
            long bounded = Math.max(0, Math.min(bytes, file.size()));
            long previous = bytesByFile.getOrDefault(file, 0L);
            bytesByFile.put(file, bounded);
            completed += bounded - previous;
            completed = Math.max(0, Math.min(completed, total));
        }

        synchronized void publishDownloading(ModelFile file, boolean force) {
            if (closed) {
                return;
            }
            long now = System.nanoTime();
            if (!force && now - lastPublicationNanos
                    < PROGRESS_PUBLISH_INTERVAL_NANOS) {
                return;
            }
            lastPublicationNanos = now;
            update(set, new LocalModelSnapshot(
                    LocalModelState.DOWNLOADING, targetDirectory, file.path(),
                    completed, total, null));
        }

        synchronized void publishVerifying(ModelFile file, boolean force) {
            if (closed) {
                return;
            }
            long now = System.nanoTime();
            if (!force && now - lastPublicationNanos
                    < PROGRESS_PUBLISH_INTERVAL_NANOS) {
                return;
            }
            lastPublicationNanos = now;
            update(set, new LocalModelSnapshot(
                    LocalModelState.VERIFYING, targetDirectory, file.path(),
                    completed, total, null));
        }

        synchronized void close() {
            closed = true;
        }
    }

    private enum PreparedKind {
        INSTALLED,
        SOURCE,
        MISSING
    }

    private record PreparedFile(
            ModelFile file,
            Path destination,
            Path partial,
            PreparedKind kind,
            long partialBytes
    ) {
        boolean needsDownload() {
            return kind == PreparedKind.MISSING;
        }

        long initialEquivalentBytes() {
            return needsDownload() ? partialBytes : file.size();
        }

        long requiredDownloadBytes() {
            return needsDownload() ? file.size() - partialBytes : 0;
        }
    }

    record ContentRange(long start, long end, long total) {
    }

    private record ModelManifest(String revision, List<ModelFile> files) {
    }

    private record ModelFile(
            String set,
            String path,
            long size,
            String sha256,
            String url,
            Long installedSize,
            String installedSha256
    ) {
        LocalModelSet modelSet() {
            return LocalModelSet.fromId(set);
        }

        boolean converted() {
            return installedSize != null;
        }

        long effectiveInstalledSize() {
            return installedSize == null ? size : installedSize.longValue();
        }

        String effectiveInstalledSha256() {
            return installedSha256 == null ? sha256 : installedSha256;
        }

        void validate() {
            modelSet();
            if (path == null || path.isBlank() || size <= 0
                    || sha256 == null || !sha256.matches("[0-9a-f]{64}")
                    || url == null || url.isBlank()
                    || (installedSize != null && installedSize <= 0)
                    || (installedSha256 != null
                    && !installedSha256.matches("[0-9a-f]{64}"))
                    || ((installedSize == null) != (installedSha256 == null))) {
                throw new IllegalArgumentException("Invalid model manifest entry");
            }
        }
    }
}
