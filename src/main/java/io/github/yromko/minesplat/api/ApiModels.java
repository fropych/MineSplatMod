package io.github.yromko.minesplat.api;

import java.util.List;
import java.util.Map;

public final class ApiModels {
    private ApiModels() {
    }

    public record Health(String status, String service, String api_version) {
        public boolean compatible() {
            return "ok".equals(status)
                    && "triposplat-vulkan".equals(service)
                    && "v1".equals(api_version);
        }
    }

    public record Device(int index, String name, boolean selected) {
    }

    public record DeviceList(List<Device> devices) {
        public Device selectedDevice() {
            if (devices == null) {
                return null;
            }
            return devices.stream().filter(Device::selected).findFirst().orElse(null);
        }
    }

    public record Artifact(
            String id,
            String type,
            String state,
            String original_name,
            long size_bytes,
            String content_url
    ) {
    }

    public record QueuedJob(String job_id, String status, String status_url) {
    }

    public record Job(
            String id,
            String type,
            String status,
            String error,
            String input_artifact_id,
            Map<String, String> artifacts,
            Map<String, Object> metrics
    ) {
        public boolean terminal() {
            return switch (status) {
                case "succeeded", "failed", "cancelled", "expired" -> true;
                default -> false;
            };
        }

        public String artifact(String name) {
            return artifacts == null ? null : artifacts.get(name);
        }
    }

    public record ConnectionInfo(Health health, Device selectedDevice, List<Device> devices) {
        public ConnectionInfo {
            devices = devices == null ? List.of() : List.copyOf(devices);
        }
    }
}
