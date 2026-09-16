package dev.example.mapi.internal.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.json.JsonWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IdentityTest {

    @Test
    void generatedIdentityIsWellFormedAndSerializable() {
        InstanceIdentity identity = InstanceIdentity.generate();
        assertTrue(identity.instanceId().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertTrue(identity.bootId().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertEquals("{\"instanceId\":\"" + identity.instanceId() + "\","
                + "\"bootId\":\"" + identity.bootId() + "\"}", JsonWriter.write(identity.toMap()));
    }

    @Test
    void participantViewMapsAllSpecFields() {
        ParticipantView view = new ParticipantView.Builder(InstanceIdentity.generate())
                .connectionSessionId("conn-1")
                .worldSessionId("world-1")
                .launchProfileName("test-client-1")
                .launchProfileUuid("00000000-0000-0000-0000-000000000001")
                .joinedPlayerName("Zoe")
                .joinedPlayerUuid("00000000-0000-0000-0000-000000000002")
                .dimension("minecraft:overworld")
                .entityIdentifier("entity-1")
                .targetAddress("127.0.0.1:25565")
                .mappingBasis(ParticipantView.MappingBasis.OBSERVED_BOTH_APIS)
                .build();

        Map<String, Object> map = view.toMap();
        assertEquals("test-client-1", map.get("launchProfileName"));
        assertEquals("Zoe", map.get("joinedPlayerName"));
        assertEquals("observed-both-apis", map.get("mappingBasis"));
        String json = JsonWriter.write(map);
        assertTrue(json.contains("\"connectionSessionId\":\"conn-1\""));
        assertTrue(json.contains("\"dimension\":\"minecraft:overworld\""));
        // UNVERIFIED is the default basis.
        ParticipantView minimal = new ParticipantView.Builder(InstanceIdentity.generate()).build();
        assertEquals("unverified", JsonWriter.write(minimal.toMap()).contains("unverified") ? "unverified" : "?");
    }

    @Test
    void wireNamesAreStable() {
        assertEquals("address-based", ParticipantView.MappingBasis.ADDRESS_BASED.wireName());
        assertEquals("handshake-verified", ParticipantView.MappingBasis.HANDSHAKE_VERIFIED.wireName());
        assertEquals("unverified", ParticipantView.MappingBasis.UNVERIFIED.wireName());
    }
}
