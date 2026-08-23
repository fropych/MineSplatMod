package io.github.yromko.minesplat.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.yromko.minesplat.config.GenerationPreset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripoSplatApiClientTest {
    private HttpServer server;
    private String baseUrl;
    private final List<JsonObject> generationBodies = new ArrayList<>();
    private final List<JsonObject> textGenerationBodies = new ArrayList<>();
    private final List<JsonObject> voxelBodies = new ArrayList<>();
    private boolean rejectTextGeneration;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void generationAlwaysUsesFixed32768GaussianContract() {
        TripoSplatApiClient client = new TripoSplatApiClient(baseUrl);
        client.enqueueGeneration("input-1", 42).join();
        client.enqueueGeneration("input-1", Long.MAX_VALUE).join();

        assertEquals(2, generationBodies.size());
        assertEquals(Set.of(
                        "input_artifact_id", "seed", "steps", "guidance",
                        "num_gaussians", "erode_radius"),
                generationBodies.get(0).keySet());
        for (JsonObject body : generationBodies) {
            assertEquals(32768, body.get("num_gaussians").getAsInt());
            assertEquals(20, body.get("steps").getAsInt());
            assertEquals(3.0, body.get("guidance").getAsDouble());
            assertEquals(1, body.get("erode_radius").getAsInt());
        }
        assertEquals(42, generationBodies.get(0).get("seed").getAsLong());
        assertEquals(Long.MAX_VALUE, generationBodies.get(1).get("seed").getAsLong());
    }

    @Test
    void textGenerationUsesPinnedImageAndGaussianDefaults() {
        TripoSplatApiClient client = new TripoSplatApiClient(baseUrl);
        client.enqueueTextGeneration("  a mossy stone cottage  ", 73).join();

        assertEquals(1, textGenerationBodies.size());
        JsonObject body = textGenerationBodies.get(0);
        assertEquals(Set.of(
                        "prompt", "seed", "width", "height", "image_steps",
                        "steps", "guidance", "num_gaussians", "erode_radius"),
                body.keySet());
        assertEquals("a mossy stone cottage", body.get("prompt").getAsString());
        assertEquals(73, body.get("seed").getAsLong());
        assertEquals(1024, body.get("width").getAsInt());
        assertEquals(1024, body.get("height").getAsInt());
        assertEquals(8, body.get("image_steps").getAsInt());
        assertEquals(20, body.get("steps").getAsInt());
        assertEquals(3.0, body.get("guidance").getAsDouble());
        assertEquals(32768, body.get("num_gaussians").getAsInt());
        assertEquals(1, body.get("erode_radius").getAsInt());

        assertThrows(IllegalArgumentException.class,
                () -> client.enqueueTextGeneration("   ", 42));
        assertThrows(IllegalArgumentException.class,
                () -> client.enqueueTextGeneration("x".repeat(8193), 42));
        assertThrows(IllegalArgumentException.class,
                () -> client.enqueueTextGeneration("\ud800", 42));
    }

    @Test
    void generationPresetsControlImageResolutionAndTripoSplatSteps() {
        TripoSplatApiClient client = new TripoSplatApiClient(baseUrl);

        for (GenerationPreset preset : GenerationPreset.values()) {
            client.enqueueGeneration("input-1", 42, preset).join();
            client.enqueueTextGeneration("cube", 42, preset).join();
        }

        assertEquals(List.of(10, 20, 20), generationBodies.stream()
                .map(body -> body.get("steps").getAsInt())
                .toList());
        assertEquals(List.of(512, 512, 1024), textGenerationBodies.stream()
                .map(body -> body.get("width").getAsInt())
                .toList());
        assertEquals(List.of(512, 512, 1024), textGenerationBodies.stream()
                .map(body -> body.get("height").getAsInt())
                .toList());
        assertEquals(List.of(10, 20, 20), textGenerationBodies.stream()
                .map(body -> body.get("steps").getAsInt())
                .toList());
    }

    @Test
    void explainsWhenRemoteServerPredatesPromptApi() {
        rejectTextGeneration = true;
        TripoSplatApiClient client = new TripoSplatApiClient(baseUrl);

        Throwable thrown = assertThrows(Throwable.class,
                () -> client.enqueueTextGeneration("cottage", 42).join());
        Throwable current = thrown;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        ApiException error = (ApiException) current;
        assertEquals("text_generation_unsupported", error.errorCode());
        assertTrue(error.getMessage().contains("v0.2.0"));
    }

    @Test
    void voxelPresetsDifferOnlyByResolution() {
        TripoSplatApiClient client = new TripoSplatApiClient(baseUrl);
        List<Integer> resolutions = List.of(32, 64, 128, 256, 512, 1024);
        for (int resolution : resolutions) {
            client.enqueueVoxelization("ply-1", resolution).join();
        }
        assertEquals(6, voxelBodies.size());

        Set<String> expectedKeys = Set.of(
                "input_artifact_id", "resolution", "opacity_threshold",
                "color_weight_power", "iso", "tolerance",
                "integration_steps", "chunk_depth");
        JsonObject baseline = voxelBodies.get(0).deepCopy();
        baseline.remove("resolution");
        for (int index = 0; index < voxelBodies.size(); index++) {
            JsonObject body = voxelBodies.get(index);
            assertEquals(expectedKeys, body.keySet());
            assertEquals(resolutions.get(index).intValue(),
                    body.get("resolution").getAsInt());
            JsonObject withoutResolution = body.deepCopy();
            withoutResolution.remove("resolution");
            assertEquals(baseline, withoutResolution);
        }
        assertEquals(0.1, baseline.get("opacity_threshold").getAsDouble());
        assertEquals(0.625, baseline.get("color_weight_power").getAsDouble());
        assertEquals(11.345, baseline.get("iso").getAsDouble());
        assertEquals(0.125, baseline.get("tolerance").getAsDouble());
        assertEquals(10, baseline.get("integration_steps").getAsInt());
        assertEquals(0, baseline.get("chunk_depth").getAsInt());
    }

    @Test
    void validatesApiIdentityAndMalformedJson() {
        TripoSplatApiClient client = new TripoSplatApiClient(baseUrl);
        ApiModels.ConnectionInfo info = client.testConnection().join();
        assertEquals("NVIDIA A100-SXM4-40GB", info.selectedDevice().name());

        CompletionExceptionAssert.assertCause(
                ApiException.class,
                () -> client.getJob("malformed").join());
        assertThrows(IllegalArgumentException.class,
                () -> new TripoSplatApiClient("ftp://example.test"));
    }

    @Test
    void acceptsHostAndPortWithoutSchemeAndRejectsMalformedInputSafely() {
        String withoutScheme = baseUrl.substring("http://".length());
        assertEquals(baseUrl, TripoSplatApiClient.normalizeBaseUrl(withoutScheme));
        assertEquals("http://localhost:8080",
                TripoSplatApiClient.normalizeBaseUrl("localhost:8080"));
        assertThrows(IllegalArgumentException.class,
                () -> TripoSplatApiClient.normalizeBaseUrl("http://"));
        assertThrows(IllegalArgumentException.class,
                () -> TripoSplatApiClient.normalizeBaseUrl("https://example.test/?token=x"));
    }

    @Test
    void sanitizesUploadNamesForMultipartHeaders() {
        String safe = TripoSplatApiClient.safeUploadName(
                "Снимок экрана 2026-08-16.png");

        assertEquals("______________2026-08-16.png", safe);
        assertTrue(safe.chars().allMatch(character -> character < 128));
        assertEquals("input-image.bin", TripoSplatApiClient.safeUploadName("\r\n"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/health")) {
            json(exchange, 200,
                    "{\"status\":\"ok\",\"service\":\"triposplat-vulkan\",\"api_version\":\"v1\"}");
        } else if (path.equals("/v1/devices")) {
            json(exchange, 200, """
                    {"devices":[
                      {"index":0,"name":"NVIDIA A100-SXM4-40GB","selected":true},
                      {"index":1,"name":"NVIDIA A100-SXM4-40GB","selected":false}
                    ]}
                    """);
        } else if (path.equals("/v1/generations")) {
            generationBodies.add(readJson(exchange));
            json(exchange, 202,
                    "{\"job_id\":\"generation-1\",\"status\":\"queued\",\"status_url\":\"/v1/jobs/generation-1\"}");
        } else if (path.equals("/v1/text-generations")) {
            if (rejectTextGeneration) {
                json(exchange, 404,
                        "{\"error\":{\"code\":\"not_found\",\"message\":\"not found\"}}");
                return;
            }
            textGenerationBodies.add(readJson(exchange));
            json(exchange, 202,
                    "{\"job_id\":\"text-1\",\"status\":\"queued\",\"status_url\":\"/v1/jobs/text-1\"}");
        } else if (path.equals("/v1/voxelizations")) {
            voxelBodies.add(readJson(exchange));
            json(exchange, 202,
                    "{\"job_id\":\"voxel-1\",\"status\":\"queued\",\"status_url\":\"/v1/jobs/voxel-1\"}");
        } else if (path.equals("/v1/jobs/malformed")) {
            json(exchange, 200, "{not-json");
        } else {
            json(exchange, 404, "{\"error\":{\"code\":\"not_found\",\"message\":\"not found\"}}");
        }
    }

    private static JsonObject readJson(HttpExchange exchange) throws IOException {
        return JsonParser.parseString(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    private static final class CompletionExceptionAssert {
        static void assertCause(
                Class<? extends Throwable> type,
                org.junit.jupiter.api.function.Executable executable
        ) {
            Throwable thrown = assertThrows(Throwable.class, executable);
            Set<Throwable> seen = new HashSet<>();
            while (thrown != null && seen.add(thrown)) {
                if (type.isInstance(thrown)) {
                    return;
                }
                thrown = thrown.getCause();
            }
            throw new AssertionError("Expected throwable chain to contain " + type.getName());
        }
    }
}
