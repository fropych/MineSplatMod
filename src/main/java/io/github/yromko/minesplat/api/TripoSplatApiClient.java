package io.github.yromko.minesplat.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.github.yromko.minesplat.api.ApiModels.Artifact;
import io.github.yromko.minesplat.api.ApiModels.ConnectionInfo;
import io.github.yromko.minesplat.api.ApiModels.DeviceList;
import io.github.yromko.minesplat.api.ApiModels.Health;
import io.github.yromko.minesplat.api.ApiModels.Job;
import io.github.yromko.minesplat.api.ApiModels.QueuedJob;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TripoSplatApiClient {
    public static final long MAX_IMAGE_BYTES = 25L * 1024L * 1024L;
    private static final Gson GSON = new Gson();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final ExecutorService HTTP_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "MineSplat HTTP");
        thread.setDaemon(true);
        return thread;
    });

    private final HttpClient http;
    private final String baseUrl;

    public TripoSplatApiClient(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.http = HttpClient.newBuilder()
                .executor(HTTP_EXECUTOR)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static String normalizeBaseUrl(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("API base URL is empty");
        }
        String candidate = input.trim();
        if (!candidate.contains("://")) {
            candidate = "http://" + candidate;
        }
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Enter a valid HTTP or HTTPS API URL", exception);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("API URL must use HTTP or HTTPS");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("API URL has no host");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("API base URL must not contain a query or fragment");
        }
        String value = uri.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public CompletableFuture<ConnectionInfo> testConnection() {
        return getJson("/health", Health.class).thenCompose(health -> {
            if (!health.compatible()) {
                return CompletableFuture.failedFuture(
                        new ApiException(0, "incompatible_api", "Server is not a compatible TripoSplat API v1"));
            }
            return getJson("/v1/devices", DeviceList.class)
                    .thenApply(devices -> new ConnectionInfo(health, devices.selectedDevice()));
        });
    }

    public CompletableFuture<Artifact> uploadImage(Path image) {
        try {
            long size = Files.size(image);
            if (size <= 0 || size > MAX_IMAGE_BYTES) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Image must be between 1 byte and 25 MiB"));
            }
            byte[] bytes = Files.readAllBytes(image);
            String boundary = "MineSplat-" + UUID.randomUUID();
            String prefix = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"type\"\r\n\r\n"
                    + "input_image\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + safeUploadName(image.getFileName().toString()) + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
            String suffix = "\r\n--" + boundary + "--\r\n";
            byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
            byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
            HttpRequest request = request("/v1/artifacts")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.concat(
                            HttpRequest.BodyPublishers.ofByteArray(prefixBytes),
                            HttpRequest.BodyPublishers.ofByteArray(bytes),
                            HttpRequest.BodyPublishers.ofByteArray(suffixBytes)))
                    .build();
            return sendJson(request, Artifact.class);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(
                    new ApiException("Cannot read input image", exception));
        }
    }

    public CompletableFuture<QueuedJob> enqueueGeneration(String inputArtifactId, long seed) {
        JsonObject body = new JsonObject();
        body.addProperty("input_artifact_id", inputArtifactId);
        body.addProperty("seed", seed);
        body.addProperty("steps", 20);
        body.addProperty("guidance", 3.0);
        body.addProperty("num_gaussians", 32768);
        body.addProperty("erode_radius", 1);
        return postJson("/v1/generations", body, QueuedJob.class);
    }

    public CompletableFuture<QueuedJob> enqueueVoxelization(String plyArtifactId, int resolution) {
        if (resolution != 32 && resolution != 64 && resolution != 128) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unsupported voxel resolution: " + resolution));
        }
        JsonObject body = new JsonObject();
        body.addProperty("input_artifact_id", plyArtifactId);
        body.addProperty("resolution", resolution);
        body.addProperty("opacity_threshold", 0.1);
        body.addProperty("color_weight_power", 0.625);
        body.addProperty("iso", 11.345);
        body.addProperty("tolerance", 0.125);
        body.addProperty("integration_steps", 10);
        body.addProperty("chunk_depth", 0);
        return postJson("/v1/voxelizations", body, QueuedJob.class);
    }

    public CompletableFuture<Job> getJob(String jobId) {
        return getJson("/v1/jobs/" + encodeOpaqueId(jobId), Job.class);
    }

    public CompletableFuture<Job> cancelJob(String jobId) {
        HttpRequest request = request("/v1/jobs/" + encodeOpaqueId(jobId))
                .DELETE()
                .build();
        return sendJson(request, Job.class);
    }

    public CompletableFuture<byte[]> downloadArtifact(String artifactId, int resolution) {
        long voxelCount = (long) resolution * resolution * resolution;
        long maximum = 128L + ((voxelCount + 7) / 8) + voxelCount * 3;
        HttpRequest request = request("/v1/artifacts/" + encodeOpaqueId(artifactId) + "/content")
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    requireSuccess(response.statusCode(), response.body());
                    if (response.body().length > maximum) {
                        throw new ApiException(
                                response.statusCode(), "artifact_too_large",
                                "TSVOX response exceeds the maximum size for resolution " + resolution);
                    }
                    return response.body();
                });
    }

    public CompletableFuture<Void> deleteArtifact(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        HttpRequest request = request("/v1/artifacts/" + encodeOpaqueId(artifactId))
                .DELETE()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle((response, error) -> null);
    }

    private <T> CompletableFuture<T> getJson(String path, Class<T> type) {
        HttpRequest request = request(path).GET().build();
        return sendJson(request, type);
    }

    private <T> CompletableFuture<T> postJson(String path, JsonObject body, Class<T> type) {
        HttpRequest request = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        return sendJson(request, type);
    }

    private <T> CompletableFuture<T> sendJson(HttpRequest request, Class<T> type) {
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    requireSuccess(response.statusCode(), response.body());
                    try {
                        T value = GSON.fromJson(
                                new String(response.body(), StandardCharsets.UTF_8), type);
                        if (value == null) {
                            throw new JsonParseException("empty JSON value");
                        }
                        return value;
                    } catch (JsonParseException exception) {
                        throw new ApiException("Server returned malformed JSON", exception);
                    }
                });
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "MineSplat/0.1.0");
    }

    private static void requireSuccess(int status, byte[] body) {
        if (status >= 200 && status < 300) {
            return;
        }
        String code = "http_" + status;
        String message = "TripoSplat API returned HTTP " + status;
        try {
            JsonObject root = GSON.fromJson(
                    new String(body, StandardCharsets.UTF_8), JsonObject.class);
            if (root != null && root.has("error") && root.get("error").isJsonObject()) {
                JsonObject error = root.getAsJsonObject("error");
                if (error.has("code")) {
                    code = error.get("code").getAsString();
                }
                if (error.has("message")) {
                    message = error.get("message").getAsString();
                }
            }
        } catch (RuntimeException ignored) {
        }
        throw new ApiException(status, code, message);
    }

    private static String safeUploadName(String name) {
        return name.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private static String encodeOpaqueId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException("Invalid opaque API id");
        }
        return id;
    }
}
