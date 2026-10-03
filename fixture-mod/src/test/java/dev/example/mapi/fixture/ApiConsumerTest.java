package dev.example.mapi.fixture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.api.Mapi;
import dev.example.mapi.api.MapiApi;
import dev.example.mapi.api.MapiService;
import dev.example.mapi.api.MapiServices;
import dev.example.mapi.api.PlatformType;
import dev.example.mapi.api.ServerStatusSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiConsumerTest {
    @TempDir
    Path directory;

    @Test
    void reportContainsObservedMembersAndBalancedLifecycleEvidence() throws Exception {
        Map<String, MapiService> registry = new HashMap<>();
        MapiServices services = new MapiServices() {
            public MapiService register(String id, MapiService service) {
                registry.put(id, service);
                return service;
            }
            public Optional<MapiService> get(String id) {
                return Optional.ofNullable(registry.get(id));
            }
            public Map<String, MapiService> all() {
                return Map.copyOf(registry);
            }
        };
        MapiApi.bind(new Mapi() {
            public String modVersion() { return "0.1.0"; }
            public String apiVersion() { return "0.1.0"; }
            public String minecraftVersion() { return "26.2"; }
            public PlatformType platform() { return PlatformType.FABRIC; }
            public String platformVersion() { return "test-loader"; }
            public MapiServices services() { return services; }
            public Optional<ServerStatusSnapshot> serverStatus() {
                return Optional.of(new ServerStatusSnapshot(200, 100, 1, 20, 42, 2, "fixture"));
            }
        });
        Path report = directory.resolve("fixture.json");
        ApiConsumer consumer = new ApiConsumer(report);
        consumer.sample();
        registry.get("acceptance-fixture").onServerStart();
        registry.get("acceptance-fixture").onServerStop();
        String json = Files.readString(report);
        assertTrue(json.contains("\"failures\":[]"));
        assertTrue(json.contains("\"starts\":1,\"stops\":1,\"snapshots\":1"));
        assertTrue(json.contains("MapiService.onServerStop"));
        assertTrue(json.contains("ServerStatusSnapshot.tickCount"));
    }
}
