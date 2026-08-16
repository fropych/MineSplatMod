package io.github.yromko.minesplat.inference;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class LocalModelManager implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final long FREE_SPACE_MARGIN = 512L * 1024L * 1024L;
    private static final String MANIFEST_RESOURCE =
            "/assets/minesplat/triposplat/model-manifest.json";

    private final HttpClient http;
    private final ExecutorService worker;
    private final Map<LocalModelSet, List<ModelFile>> files =
            new EnumMap<>(LocalModelSet.class);
    private final Map<LocalModelSet, Long> totalBytes =
            new EnumMap<>(LocalModelSet.class);
    private final Map<LocalModelSet, CopyOnWriteArrayList<Consumer<LocalModelSnapshot>>>
            listeners = new EnumMap<>(LocalModelSet.class);
    private final AtomicBoolean cancelled = new AtomicBoolean();

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
        this.directory = normalize(directory);
        this.http = http;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MineSplat model manager");
            thread.setDaemon(true);
            return thread;
        });
        for (LocalModelSet set : LocalModelSet.values()) {
            listeners.put(set, new CopyOnWriteArrayList<>());
        }
        ModelManifest manifest = loadManifest();
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
        CompletableFuture<Void> install = activeInstall;
        return install != null && !install.isDone();
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
        update(set, downloading(targetDirectory, set, null));
        CompletableFuture<Void> install = CompletableFuture.runAsync(
                () -> installAll(targetDirectory, set), worker);
        activeInstall = install;
        install.whenComplete((ignored, failure) -> {
            synchronized (this) {
                activeInstall = null;
                activeSet = null;
            }
            if (!directory.equals(targetDirectory)) {
                return;
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof CancellationException) {
                publishVerification(targetDirectory, set,
                        verifyAllInstalled(targetDirectory, set));
            } else if (cause != null) {
                update(set, failed(targetDirectory, set, usefulMessage(cause)));
            } else {
                publishVerification(targetDirectory, set, true);
            }
        });
        return install;
    }

    public void cancelInstall() {
        cancelled.set(true);
    }

    private void installAll(Path targetDirectory, LocalModelSet set) {
        try {
            Files.createDirectories(targetDirectory);
            long present = existingBytes(targetDirectory, set);
            long required = requiredDownloadBytes(targetDirectory, set)
                    + conversionScratchBytes(targetDirectory, set);
            FileStore store = Files.getFileStore(targetDirectory);
            if (store.getUsableSpace() < required + FREE_SPACE_MARGIN) {
                throw new IOException(
                        "Not enough disk space for TripoSplat " + set.id()
                                + " models; need " + (required + FREE_SPACE_MARGIN)
                                + " free bytes");
            }
            for (ModelFile file : files.get(set)) {
                requireNotCancelled();
                Path destination = resolve(targetDirectory, file.path());
                if (matchesInstalled(destination, file) || matchesSource(destination, file)) {
                    continue;
                }
                Files.createDirectories(destination.getParent());
                Path partial = destination.resolveSibling(destination.getFileName() + ".part");
                present = download(set, file, partial, present);
                if (!matchesSource(partial, file)) {
                    throw new IOException("Downloaded model failed SHA-256 verification: "
                            + file.path());
                }
                moveAtomically(partial, destination);
            }
            if (set == LocalModelSet.CORE) {
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
            }
            if (!verifyAllInstalled(targetDirectory, set)) {
                throw new IOException("TripoSplat " + set.id()
                        + " model installation is incomplete");
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

    private long download(
            LocalModelSet set,
            ModelFile file,
            Path partial,
            long completedBefore
    ) throws IOException, InterruptedException {
        long existing = Files.isRegularFile(partial) ? Files.size(partial) : 0;
        if (existing > file.size()) {
            Files.delete(partial);
            existing = 0;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(file.url()))
                    .timeout(Duration.ofHours(6))
                    .header("User-Agent", "MineSplat/0.4.0")
                    .GET();
            if (existing > 0) {
                builder.header("Range", "bytes=" + existing + "-");
            }
            HttpResponse<InputStream> response = http.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status == 416 && existing > 0) {
                response.body().close();
                Files.deleteIfExists(partial);
                existing = 0;
                continue;
            }
            if (status != 200 && status != 206) {
                response.body().close();
                throw new IOException("Model download returned HTTP " + status
                        + " for " + file.path());
            }
            boolean append = status == 206 && existing > 0;
            if (!append) {
                existing = 0;
            }
            long written = existing;
            StandardOpenOption[] options = append
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
            try (InputStream input = response.body();
                 OutputStream output = Files.newOutputStream(partial, options)) {
                byte[] buffer = new byte[256 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    requireNotCancelled();
                    if (count == 0) {
                        continue;
                    }
                    output.write(buffer, 0, count);
                    written += count;
                    update(set, new LocalModelSnapshot(
                            LocalModelState.DOWNLOADING, directory, file.path(),
                            completedBefore + written, totalBytes.get(set), null));
                }
            }
            if (written != file.size()) {
                throw new IOException("Model has unexpected size after download: "
                        + file.path());
            }
            return completedBefore + file.size();
        }
        throw new IOException("Cannot resume model download: " + file.path());
    }

    private boolean verifyAllInstalled(Path targetDirectory, LocalModelSet set) {
        for (ModelFile file : files.get(set)) {
            if (!matchesInstalled(resolve(targetDirectory, file.path()), file)) {
                return false;
            }
        }
        return true;
    }

    private long existingBytes(Path targetDirectory, LocalModelSet set) {
        long total = 0;
        for (ModelFile file : files.get(set)) {
            Path destination = resolve(targetDirectory, file.path());
            Path partial = destination.resolveSibling(destination.getFileName() + ".part");
            try {
                if (matchesInstalled(destination, file) || matchesSource(destination, file)) {
                    total += file.size();
                } else if (Files.isRegularFile(partial)) {
                    total += Math.min(Files.size(partial), file.size());
                }
            } catch (IOException ignored) {
            }
        }
        return total;
    }

    private long requiredDownloadBytes(Path targetDirectory, LocalModelSet set) {
        long required = 0;
        for (ModelFile file : files.get(set)) {
            Path destination = resolve(targetDirectory, file.path());
            if (matchesInstalled(destination, file) || matchesSource(destination, file)) {
                continue;
            }
            Path partial = destination.resolveSibling(destination.getFileName() + ".part");
            long partialBytes = 0;
            try {
                if (Files.isRegularFile(partial)) {
                    partialBytes = Math.min(Files.size(partial), file.size());
                }
            } catch (IOException ignored) {
            }
            required += file.size() - partialBytes;
        }
        return required;
    }

    private long conversionScratchBytes(Path targetDirectory, LocalModelSet set) {
        if (set != LocalModelSet.CORE) {
            return 0;
        }
        long largest = 0;
        for (ModelFile file : files.get(set)) {
            Path destination = resolve(targetDirectory, file.path());
            if (file.converted() && !matchesInstalled(destination, file)) {
                largest = Math.max(largest, file.effectiveInstalledSize());
            }
        }
        return largest;
    }

    private static boolean matchesSource(Path path, ModelFile expected) {
        return verifyFile(path, expected.size(), expected.sha256());
    }

    private static boolean matchesInstalled(Path path, ModelFile expected) {
        return verifyFile(path, expected.effectiveInstalledSize(),
                expected.effectiveInstalledSha256());
    }

    private static boolean verifyFile(Path path, long size, String sha256) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) != size) {
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
            return HexFormat.of().formatHex(digest.digest()).equals(sha256);
        } catch (IOException | NoSuchAlgorithmException exception) {
            return false;
        }
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
                ready ? totalBytes.get(set) : existingBytes(targetDirectory, set),
                totalBytes.get(set),
                null));
    }

    private LocalModelSnapshot downloading(
            Path targetDirectory,
            LocalModelSet set,
            String file
    ) {
        return new LocalModelSnapshot(
                LocalModelState.DOWNLOADING, targetDirectory, file,
                existingBytes(targetDirectory, set), totalBytes.get(set), null);
    }

    private LocalModelSnapshot failed(
            Path targetDirectory,
            LocalModelSet set,
            String error
    ) {
        return new LocalModelSnapshot(
                LocalModelState.FAILED, targetDirectory, null,
                existingBytes(targetDirectory, set), totalBytes.get(set), error);
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
        if (cancelled.get()) {
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
            ModelManifest manifest = GSON.fromJson(
                    new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8),
                    ModelManifest.class);
            if (manifest == null || manifest.files() == null || manifest.files().isEmpty()
                    || !TripoSplatRuntimeVersion.MODEL_REVISION.equals(manifest.revision())) {
                throw new IllegalStateException("Invalid TripoSplat model manifest");
            }
            manifest.files().forEach(ModelFile::validate);
            return manifest;
        } catch (IOException | JsonParseException exception) {
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
    }

    @FunctionalInterface
    interface ModelConverter {
        void convert(Path directory, BooleanSupplier cancelled, Consumer<String> output)
                throws IOException, InterruptedException;
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
