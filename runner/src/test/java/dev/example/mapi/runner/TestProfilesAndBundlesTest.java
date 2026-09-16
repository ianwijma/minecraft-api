package dev.example.mapi.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Profiles/preflight (§8), participant mapping (§7.2), bundles (§1.1). */
class TestProfilesAndBundlesTest {

    private static final String TOKEN = "profile-token-0123456789";

    @Test
    void preflightAcceptsAValidClientProfile() {
        String profile = "{\"profileId\":\"smoke\",\"version\":\"1\","
                + "\"windowWidth\":1280,\"windowHeight\":720,\"guiScale\":2,"
                + "\"pauseOnLostFocus\":false}";
        Map<String, Object> report = TestProfiles.preflight(profile, "client");
        assertEquals(Boolean.TRUE, report.get("ok"));
        assertEquals("smoke", report.get("profileId"));
        assertEquals(0, ((java.util.List<?>) report.get("fatal")).size());
        assertEquals(0, ((java.util.List<?>) report.get("unsupportedSettings")).size());
    }

    @Test
    void preflightFlagsFatalAndUnknownSettings() {
        String profile = "{\"windowWidth\":99999,\"mystery\":true}";
        Map<String, Object> report = TestProfiles.preflight(profile, "client");
        assertEquals(Boolean.FALSE, report.get("ok"));
        assertTrue(((java.util.List<?>) report.get("fatal")).size() >= 2,
                report.toString());
        assertTrue(((java.util.List<?>) report.get("unsupportedSettings")).contains("mystery"));
        // §8.4: fatal vs advisory separation.
        assertTrue(!((java.util.List<?>) report.get("advisory")).isEmpty());
    }

    @Test
    void preflightWarnsOnOnlineModeAndLan() {
        String profile = "{\"profileId\":\"srv\",\"version\":\"1\",\"onlineMode\":true}";
        Map<String, Object> report = TestProfiles.preflight(profile, "server");
        assertEquals(Boolean.TRUE, report.get("ok"));
        assertTrue(report.get("advisory").toString().contains("externally provisioned"),
                report.toString());
    }

    @Test
    void malformedProfileIsAFatalReport() {
        Map<String, Object> report = TestProfiles.preflight("{bad", "client");
        assertEquals(Boolean.FALSE, report.get("ok"));
        assertTrue(report.get("fatal").toString().contains("not valid JSON"));
    }

    @Test
    void participantMappingDisclosesUnverifiedBasis() {
        var participant = ParticipantMapper.map("p1",
                Map.of("bridgeId", "mapi-fabric-client", "bootId", "b-1"),
                Map.of("phase", "ACTIVE"));
        assertEquals("mapi-fabric-client", participant.instanceId());
        assertEquals("unverified", participant.mappingBasis());
        assertEquals("ACTIVE", participant.phase());
        var table = ParticipantMapper.table(java.util.List.of(participant));
        assertTrue(table.get("mappingBasisLegend").toString().contains("unverified"));
        assertTrue(table.get("participants").toString().contains("p1"));
    }

    @Test
    void bundleAssemblesManifestAndArtifacts() throws Exception {
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        fake.createContext("/api/v1", exchange -> {
            byte[] body = "{\"protocolVersion\":1,\"name\":\"mapi\",\"version\":\"0.1.0\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        fake.start();
        try {
            Path dir = Files.createTempDirectory("mapi-bundle");
            Path recording = Files.createTempFile("mapi-rec", ".jsonl");
            Files.writeString(recording, "{\"index\":0,\"name\":\"health\","
                    + "\"method\":\"GET\",\"path\":\"/api/v1/health\",\"status\":200}\n");
            Path profile = Files.createTempFile("mapi-prof", ".json");
            Files.writeString(profile, "{\"profileId\":\"smoke\",\"version\":\"1\"}");

            BundleWriter writer = new BundleWriter(
                    new MapiClient("http://127.0.0.1:" + fake.getAddress().getPort(), TOKEN));
            Map<String, Object> manifest = writer.write(dir, recording, "{\"ok\":true}", profile);

            assertTrue(Files.isRegularFile(dir.resolve("manifest.json")));
            assertTrue(Files.isRegularFile(dir.resolve("recording.jsonl")));
            assertTrue(Files.isRegularFile(dir.resolve("profile.json")));
            assertTrue(Files.isRegularFile(dir.resolve("preflight.json")));
            assertTrue(manifest.get("instance").toString().contains("mapi"));
            String manifestJson = Files.readString(dir.resolve("manifest.json"));
            assertTrue(manifestJson.contains("\"recording\":\"recording.jsonl\""), manifestJson);
        } finally {
            fake.stop(0);
        }
    }
}
