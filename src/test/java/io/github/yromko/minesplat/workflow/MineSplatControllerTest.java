package io.github.yromko.minesplat.workflow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.yromko.minesplat.config.GenerationPreset;
import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.config.OutputMode;
import io.github.yromko.minesplat.config.VoxelPreset;
import io.github.yromko.minesplat.cnb.CnbBlueprint;
import io.github.yromko.minesplat.cnb.CnbBlueprintExporter;
import io.github.yromko.minesplat.cnb.CnbBlueprintStore;
import io.github.yromko.minesplat.cnb.CnbIntegration;
import io.github.yromko.minesplat.cnb.CnbPlacementProgress;
import io.github.yromko.minesplat.cnb.CnbPlacementResult;
import io.github.yromko.minesplat.palette.BlockPalette;
import io.github.yromko.minesplat.palette.PaletteEntry;
import io.github.yromko.minesplat.inference.InferenceTarget;
import io.github.yromko.minesplat.testutil.TsvoxFixtures;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineSplatControllerTest {
    @TempDir
    Path temporary;

    private HttpServer server;
    private String baseUrl;
    private MineSplatController controller;
    private final List<String> deletes = new CopyOnWriteArrayList<>();
    private final List<GenerationState> states = new CopyOnWriteArrayList<>();
    private final AtomicInteger generationPolls = new AtomicInteger();
    private final AtomicInteger voxelizations = new AtomicInteger();
    private JsonObject generationBody;
    private JsonObject textGenerationBody;
    private JsonObject voxelBody;
    private String uploadContentLength;
    private int uploadBodyLength;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        controller = new MineSplatController(
                BlockPalette.loadDefault(),
                (name, resolution, voxels, state) -> {
                    state.accept(GenerationState.SAVING);
                    state.accept(GenerationState.PLACING);
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            temporary.resolve(name + "-r" + resolution + ".litematic"));
                },
                new CnbBlueprintExporter(
                        new CnbBlueprintStore(temporary.resolve("blueprints")),
                        () -> "tester",
                        () -> 4189,
                        Runnable::run),
                new TestCnbIntegration());
        controller.listen(snapshot -> states.add(snapshot.state()));
    }

    @AfterEach
    void stopServer() {
        controller.close();
        server.stop(0);
    }

    @Test
    void performsFullFlowAndCleansArtifacts() throws Exception {
        Path image = temporary.resolve("input.png");
        Files.write(image, new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3
        });
        Path output = controller.start(new GenerationRequest(
                        new InferenceTarget.Remote(baseUrl),
                        image,
                        "test",
                        42,
                        GenerationPreset.BASE,
                        VoxelPreset.STANDARD,
                        PaletteProfile.SURVIVAL,
                        Set.of(),
                        OutputMode.LITEMATICA))
                .get(15, TimeUnit.SECONDS);

        assertEquals(temporary.resolve("test-r64.litematic"), output);
        assertEquals(GenerationState.SUCCEEDED, controller.snapshot().state());
        assertEquals(1, controller.snapshot().blockCount());
        assertFalse(controller.snapshot().hasImageGenerationTime());
        assertEquals(12.5, controller.snapshot().modelGenerationSeconds(), 0.001);
        assertTrue(states.containsAll(List.of(
                GenerationState.UPLOADING,
                GenerationState.GENERATION_QUEUED,
                GenerationState.GENERATION_RUNNING,
                GenerationState.VOXELIZATION_QUEUED,
                GenerationState.DOWNLOADING,
                GenerationState.CONVERTING,
                GenerationState.SAVING,
                GenerationState.PLACING,
                GenerationState.SUCCEEDED)));

        assertEquals(32768, generationBody.get("num_gaussians").getAsInt());
        assertEquals(42, generationBody.get("seed").getAsLong());
        assertEquals(10, generationBody.get("steps").getAsInt());
        assertEquals(64, voxelBody.get("resolution").getAsInt());
        assertEquals(0.1, voxelBody.get("opacity_threshold").getAsDouble());
        assertNotNull(uploadContentLength);
        assertEquals(uploadBodyLength, Integer.parseInt(uploadContentLength));
        waitForDelete("input-1");
        waitForDelete("splat-1");
        waitForDelete("tsvox-1");
        assertFalse(deletes.contains("ply-1"));

        assertTrue(controller.canReuseGeneration(
                new InferenceTarget.Remote(baseUrl), image, 42, GenerationPreset.BASE));
        assertFalse(controller.canReuseGeneration(
                new InferenceTarget.Remote(baseUrl), image, 42, GenerationPreset.HIGH));
        Path blueprint = controller.rebuildOrRevoxelize(
                        "test",
                        VoxelPreset.STANDARD,
                        PaletteProfile.SURVIVAL,
                        Set.of(),
                        OutputMode.CHISELS_AND_BITS)
                .get(10, TimeUnit.SECONDS);
        assertTrue(blueprint.getFileName().toString().endsWith(".msbp"));
        assertEquals(OutputMode.CHISELS_AND_BITS, controller.snapshot().outputMode());
        assertEquals(1, voxelizations.get());
        controller.finishSession();
        waitForDelete("ply-1");
    }

    @Test
    void performsPromptFlowAndDeletesGeneratedImage() throws Exception {
        GenerationSource.Prompt prompt = new GenerationSource.Prompt("a mossy stone cottage");
        Path output = controller.start(new GenerationRequest(
                        new InferenceTarget.Remote(baseUrl),
                        prompt,
                        "cottage",
                        73,
                        GenerationPreset.BASE,
                        VoxelPreset.STANDARD,
                        PaletteProfile.SURVIVAL,
                        Set.of(),
                        OutputMode.LITEMATICA))
                .get(15, TimeUnit.SECONDS);

        assertEquals(temporary.resolve("cottage-r64.litematic"), output);
        assertEquals("a mossy stone cottage",
                textGenerationBody.get("prompt").getAsString());
        assertEquals(512, textGenerationBody.get("width").getAsInt());
        assertEquals(8, textGenerationBody.get("image_steps").getAsInt());
        assertEquals(10, textGenerationBody.get("steps").getAsInt());
        assertEquals(3.25, controller.snapshot().imageGenerationSeconds(), 0.001);
        assertEquals(7.5, controller.snapshot().modelGenerationSeconds(), 0.001);
        waitForDelete("image-text-1");
        waitForDelete("splat-text-1");
        assertFalse(deletes.contains("ply-text-1"));
        assertTrue(controller.canReuseGeneration(
                new InferenceTarget.Remote(baseUrl), prompt, 73, GenerationPreset.BASE));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (method.equals("POST") && path.equals("/v1/artifacts")) {
            byte[] uploadBody = exchange.getRequestBody().readAllBytes();
            uploadContentLength = exchange.getRequestHeaders().getFirst("Content-Length");
            uploadBodyLength = uploadBody.length;
            String body = new String(uploadBody, StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("input_image"));
            json(exchange, 201, """
                    {"id":"input-1","type":"input_image","state":"ready",
                     "original_name":"input.png","size_bytes":11,
                     "content_url":"/v1/artifacts/input-1/content"}
                    """);
        } else if (method.equals("POST") && path.equals("/v1/generations")) {
            generationBody = readJson(exchange);
            json(exchange, 202,
                    "{\"job_id\":\"gen-1\",\"status\":\"queued\",\"status_url\":\"/v1/jobs/gen-1\"}");
        } else if (method.equals("POST") && path.equals("/v1/text-generations")) {
            textGenerationBody = readJson(exchange);
            json(exchange, 202,
                    "{\"job_id\":\"text-1\",\"status\":\"queued\",\"status_url\":\"/v1/jobs/text-1\"}");
        } else if (method.equals("GET") && path.equals("/v1/jobs/gen-1")) {
            int poll = generationPolls.getAndIncrement();
            if (poll == 0) {
                job(exchange, "gen-1", "queued", "{}");
            } else if (poll == 1) {
                job(exchange, "gen-1", "running", "{}");
            } else {
                job(exchange, "gen-1", "succeeded",
                        "{\"gaussian_ply\":\"ply-1\",\"splat\":\"splat-1\"}",
                        "{\"elapsed_seconds\":12.5}");
            }
        } else if (method.equals("GET") && path.equals("/v1/jobs/text-1")) {
            job(exchange, "text-1", "succeeded",
                    "{\"image\":\"image-text-1\",\"gaussian_ply\":\"ply-text-1\","
                            + "\"splat\":\"splat-text-1\"}",
                    "{\"image_elapsed_seconds\":3.25,"
                            + "\"triposplat_elapsed_seconds\":7.5,"
                            + "\"elapsed_seconds\":10.75}");
        } else if (method.equals("POST") && path.equals("/v1/voxelizations")) {
            voxelizations.incrementAndGet();
            voxelBody = readJson(exchange);
            json(exchange, 202,
                    "{\"job_id\":\"vox-1\",\"status\":\"queued\",\"status_url\":\"/v1/jobs/vox-1\"}");
        } else if (method.equals("GET") && path.equals("/v1/jobs/vox-1")) {
            job(exchange, "vox-1", "succeeded", "{\"output\":\"tsvox-1\"}");
        } else if (method.equals("GET")
                && path.equals("/v1/artifacts/tsvox-1/content")) {
            bytes(exchange, 200, TsvoxFixtures.valid(
                    64, new int[]{0}, new byte[]{(byte) 255, (byte) 255, (byte) 255}));
        } else if (method.equals("DELETE") && path.startsWith("/v1/artifacts/")) {
            deletes.add(path.substring("/v1/artifacts/".length()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        } else {
            json(exchange, 404, "{\"error\":{\"code\":\"not_found\",\"message\":\"not found\"}}");
        }
    }

    private static void job(
            HttpExchange exchange,
            String id,
            String status,
            String artifacts
    ) throws IOException {
        job(exchange, id, status, artifacts, "{}");
    }

    private static void job(
            HttpExchange exchange,
            String id,
            String status,
            String artifacts,
            String metrics
    ) throws IOException {
        json(exchange, 200, """
                {"id":"%s","type":"generation","status":"%s","error":null,
                 "input_artifact_id":"input-1","artifacts":%s,"metrics":%s}
                """.formatted(id, status, artifacts, metrics));
    }

    private static JsonObject readJson(HttpExchange exchange) throws IOException {
        return JsonParser.parseString(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        bytes(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(HttpExchange exchange, int status, byte[] data) throws IOException {
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    private void waitForDelete(String artifact) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!deletes.contains(artifact) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(deletes.contains(artifact), () -> artifact + " was not deleted: " + deletes);
    }

    private static final class TestCnbIntegration implements CnbIntegration {
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
            return 16;
        }

        @Override
        public CompletableFuture<List<PaletteEntry>> filterEligible(
                List<PaletteEntry> candidates
        ) {
            return CompletableFuture.completedFuture(candidates);
        }

        @Override
        public CompletableFuture<CnbPlacementResult> place(
                MinecraftClient client,
                CnbBlueprint blueprint,
                int quarterTurns,
                BlockPos origin,
                java.util.function.Consumer<CnbPlacementProgress> progress
        ) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("not used in controller test"));
        }

        @Override
        public void cancelPlacement() {
        }
    }
}
