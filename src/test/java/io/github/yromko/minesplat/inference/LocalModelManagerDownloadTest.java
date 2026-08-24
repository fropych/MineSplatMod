package io.github.yromko.minesplat.inference;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModelManagerDownloadTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path temporary;

    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void streamsAndVerifiesFreshDownloadWithoutProgressPastTotal() throws Exception {
        byte[] payload = "fresh model payload".repeat(4096).getBytes();
        startServer(exchange -> respond(exchange, 200, payload, null));
        ModelSpec core = core("core.bin", payload, url("/core"));

        try (LocalModelManager models = manager(List.of(core))) {
            awaitInitialCheck(models);
            List<LocalModelSnapshot> snapshots = new CopyOnWriteArrayList<>();
            try (AutoCloseable ignored = models.listen(snapshots::add)) {
                models.install().get(5, TimeUnit.SECONDS);
            }

            assertArrayEquals(payload, Files.readAllBytes(temporary.resolve("models/core.bin")));
            assertTrue(snapshots.stream().anyMatch(snapshot ->
                    snapshot.state() == LocalModelState.DOWNLOADING));
            assertTrue(snapshots.stream().anyMatch(snapshot ->
                    snapshot.state() == LocalModelState.VERIFYING
                            && snapshot.downloadedBytes() == payload.length));
            assertTrue(snapshots.stream().allMatch(snapshot ->
                    snapshot.downloadedBytes() >= 0
                            && snapshot.downloadedBytes() <= snapshot.totalBytes()));
            assertEquals(LocalModelState.READY, models.snapshot().state());
        }
    }

    @Test
    void resumesAtExactRangeWithoutDoubleCountingPartialBytes() throws Exception {
        byte[] payload = "0123456789abcdef".getBytes();
        int prefixLength = 5;
        AtomicReference<String> range = new AtomicReference<>();
        startServer(exchange -> {
            range.set(exchange.getRequestHeaders().getFirst("Range"));
            byte[] suffix = java.util.Arrays.copyOfRange(payload, prefixLength, payload.length);
            respond(exchange, 206, suffix,
                    "bytes " + prefixLength + "-" + (payload.length - 1)
                            + "/" + payload.length);
        });
        Path modelsDirectory = temporary.resolve("models");
        Files.createDirectories(modelsDirectory);
        Files.write(modelsDirectory.resolve("core.bin.part"),
                java.util.Arrays.copyOf(payload, prefixLength));

        try (LocalModelManager models = manager(List.of(
                core("core.bin", payload, url("/core"))))) {
            awaitInitialCheck(models);
            List<LocalModelSnapshot> snapshots = new CopyOnWriteArrayList<>();
            try (AutoCloseable ignored = models.listen(snapshots::add)) {
                models.install().get(5, TimeUnit.SECONDS);
            }

            assertEquals("bytes=" + prefixLength + "-", range.get());
            assertArrayEquals(payload, Files.readAllBytes(modelsDirectory.resolve("core.bin")));
            assertTrue(snapshots.stream().allMatch(snapshot ->
                    snapshot.downloadedBytes() <= snapshot.totalBytes()));
        }
    }

    @Test
    void resetsProgressWhenServerIgnoresRange() throws Exception {
        byte[] payload = "server sends the complete file".getBytes();
        int prefixLength = 7;
        AtomicReference<String> range = new AtomicReference<>();
        startServer(exchange -> {
            range.set(exchange.getRequestHeaders().getFirst("Range"));
            respond(exchange, 200, payload, null);
        });
        Path modelsDirectory = temporary.resolve("models");
        Files.createDirectories(modelsDirectory);
        Files.write(modelsDirectory.resolve("core.bin.part"),
                java.util.Arrays.copyOf(payload, prefixLength));

        try (LocalModelManager models = manager(List.of(
                core("core.bin", payload, url("/core"))))) {
            awaitInitialCheck(models);
            List<LocalModelSnapshot> snapshots = new CopyOnWriteArrayList<>();
            try (AutoCloseable ignored = models.listen(snapshots::add)) {
                models.install().get(5, TimeUnit.SECONDS);
            }

            assertEquals("bytes=" + prefixLength + "-", range.get());
            assertTrue(snapshots.stream().anyMatch(snapshot ->
                    snapshot.state() == LocalModelState.DOWNLOADING
                            && snapshot.downloadedBytes() == 0));
            assertTrue(snapshots.stream().allMatch(snapshot ->
                    snapshot.downloadedBytes() <= snapshot.totalBytes()));
            assertArrayEquals(payload, Files.readAllBytes(modelsDirectory.resolve("core.bin")));
        }
    }

    @Test
    void rejectsMismatchedContentRangeBeforeAppending() throws Exception {
        byte[] payload = "range payload".getBytes();
        int prefixLength = 3;
        startServer(exchange -> respond(exchange, 206,
                java.util.Arrays.copyOfRange(payload, prefixLength, payload.length),
                "bytes 0-" + (payload.length - prefixLength - 1) + "/" + payload.length));
        Path modelsDirectory = temporary.resolve("models");
        Files.createDirectories(modelsDirectory);
        byte[] prefix = java.util.Arrays.copyOf(payload, prefixLength);
        Path partial = modelsDirectory.resolve("core.bin.part");
        Files.write(partial, prefix);

        try (LocalModelManager models = manager(List.of(
                core("core.bin", payload, url("/core"))))) {
            awaitInitialCheck(models);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> models.install().get(5, TimeUnit.SECONDS));

            assertTrue(rootMessage(failure).contains("Unexpected Content-Range"));
            assertArrayEquals(prefix, Files.readAllBytes(partial));
            assertFalse(Files.exists(modelsDirectory.resolve("core.bin")));
        }
    }

    @Test
    void capsConcurrentDownloadsAtThree() throws Exception {
        CountDownLatch firstThree = new CountDownLatch(3);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        byte[] payload = "parallel".getBytes();
        startServer(exchange -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            firstThree.countDown();
            try {
                if (!firstThree.await(3, TimeUnit.SECONDS)) {
                    respond(exchange, 500, new byte[0], null);
                    return;
                }
                Thread.sleep(40);
                respond(exchange, 200, payload, null);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Test server interrupted", exception);
            } finally {
                active.decrementAndGet();
            }
        });
        List<ModelSpec> core = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> core(
                        "core-" + index + ".bin", payload, url("/core-" + index)))
                .toList();

        try (LocalModelManager models = manager(core)) {
            awaitInitialCheck(models);
            models.install().get(5, TimeUnit.SECONDS);

            assertEquals(3, maximum.get());
            assertEquals(LocalModelState.READY, models.snapshot().state());
        }
    }

    @Test
    void verifiesOnlyNecessaryConverterOutputBeforeReady() throws Exception {
        byte[] source = "source weights".getBytes();
        byte[] installed = "converted weights".getBytes();
        startServer(exchange -> respond(exchange, 200, source, null));
        ModelSpec core = new ModelSpec(
                "core", "core.bin", source, url("/core"), installed);

        try (LocalModelManager models = manager(List.of(core))) {
            awaitInitialCheck(models);
            models.converter((directory, cancelled, output) ->
                    Files.write(directory.resolve("core.bin"), installed));
            models.install().get(5, TimeUnit.SECONDS);

            assertEquals(LocalModelState.READY, models.snapshot().state());
            assertArrayEquals(installed,
                    Files.readAllBytes(temporary.resolve("models/core.bin")));
        }
    }

    @Test
    void hashingHonorsCancellationAndContentRangeParserIsStrict() throws Exception {
        Path file = temporary.resolve("hash.bin");
        Files.writeString(file, "content");

        assertThrows(CancellationException.class,
                () -> LocalModelManager.sha256(file, () -> true));
        assertEquals(new LocalModelManager.ContentRange(5, 9, 10),
                LocalModelManager.parseContentRange("bytes 5-9/10"));
        assertThrows(IOException.class,
                () -> LocalModelManager.parseContentRange("bytes 5-9/*"));
    }

    @Test
    void cancelClosesAResponseBodyThatStoppedSendingData() throws Exception {
        byte[] payload = "stalled response body".repeat(256).getBytes();
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            if (requests.incrementAndGet() > 1) {
                respond(exchange, 200, payload, null);
                return;
            }
            try (exchange) {
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload, 0, 1);
                exchange.getResponseBody().flush();
                bodyStarted.countDown();
                try {
                    releaseServer.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    exchange.getResponseBody().write(payload, 1, payload.length - 1);
                } catch (IOException ignored) {
                    // The client is expected to close this stalled response on cancellation.
                }
            }
        });

        try (LocalModelManager models = manager(List.of(
                core("core.bin", payload, url("/core"))))) {
            awaitInitialCheck(models);
            CompletableFuture<Void> installation = models.install();
            assertTrue(bodyStarted.await(3, TimeUnit.SECONDS));
            assertTrue(waitForSize(
                    temporary.resolve("models/core.bin.part"), 1, 2_000));

            models.cancelInstall();
            Exception failure = assertThrows(Exception.class,
                    () -> installation.get(2, TimeUnit.SECONDS));

            assertTrue(hasCause(failure, CancellationException.class),
                    () -> "Expected cancellation but got: " + failure);
            assertFalse(models.installing());
            assertEquals(LocalModelState.MISSING, models.snapshot().state());

            releaseServer.countDown();
            models.install().get(3, TimeUnit.SECONDS);
            assertEquals(2, requests.get());
            assertEquals(LocalModelState.READY, models.snapshot().state());
        } finally {
            releaseServer.countDown();
        }
    }

    @Test
    void cancelAbortsARequestThatNeverReceivesHeaders() throws Exception {
        byte[] payload = "headers never arrive".repeat(64).getBytes();
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        startServer(exchange -> {
            try (exchange) {
                requestReceived.countDown();
                try {
                    releaseServer.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try (LocalModelManager models = manager(List.of(
                core("core.bin", payload, url("/core"))))) {
            awaitInitialCheck(models);
            CompletableFuture<Void> installation = models.install();
            assertTrue(requestReceived.await(3, TimeUnit.SECONDS));

            models.cancelInstall();
            Exception failure = assertThrows(Exception.class,
                    () -> installation.get(2, TimeUnit.SECONDS));

            assertTrue(hasCause(failure, CancellationException.class),
                    () -> "Expected cancellation but got: " + failure);
            assertFalse(models.installing());
            assertEquals(LocalModelState.MISSING, models.snapshot().state());
        } finally {
            releaseServer.countDown();
        }
    }

    private LocalModelManager manager(List<ModelSpec> core) {
        return new LocalModelManager(
                temporary.resolve("models"),
                HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(2))
                        .build(),
                manifest(core));
    }

    private static void awaitInitialCheck(LocalModelManager models) throws Exception {
        models.refresh().get(5, TimeUnit.SECONDS);
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", handler);
        server.start();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            byte[] body,
            String contentRange
    ) throws IOException {
        try (exchange) {
            if (contentRange != null) {
                exchange.getResponseHeaders().set("Content-Range", contentRange);
            }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private String manifest(List<ModelSpec> core) {
        JsonObject root = new JsonObject();
        root.addProperty("revision", TripoSplatRuntimeVersion.MODEL_REVISION);
        JsonArray files = new JsonArray();
        core.forEach(spec -> files.add(json(spec)));
        files.add(json(new ModelSpec(
                "text", "text.bin", "unused".getBytes(), url("/unused"), null)));
        root.add("files", files);
        return GSON.toJson(root);
    }

    private static JsonObject json(ModelSpec spec) {
        JsonObject result = new JsonObject();
        result.addProperty("set", spec.set());
        result.addProperty("path", spec.path());
        result.addProperty("size", spec.source().length);
        result.addProperty("sha256", sha256(spec.source()));
        result.addProperty("url", spec.url());
        if (spec.installed() != null) {
            result.addProperty("installedSize", spec.installed().length);
            result.addProperty("installedSha256", sha256(spec.installed()));
        }
        return result;
    }

    private static ModelSpec core(String path, byte[] payload, String url) {
        return new ModelSpec("core", path, payload, url, null);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean waitForSize(Path path, long expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            try {
                if (Files.isRegularFile(path) && Files.size(path) == expected) {
                    return true;
                }
            } catch (IOException ignored) {
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private record ModelSpec(
            String set,
            String path,
            byte[] source,
            String url,
            byte[] installed
    ) {
    }
}
