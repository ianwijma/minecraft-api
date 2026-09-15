package dev.example.mapi.internal.identity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a client can report about the server player it is currently bound to,
 * plus how the mapping was established (spec §7.2). The mapping basis is
 * always disclosed: an address-based claim is weaker than observation through
 * both APIs, which is weaker than the optional handshake.
 */
public final class ParticipantView {

    /** How the participant → authoritative player mapping was established. */
    public enum MappingBasis {
        ADDRESS_BASED("address-based"),
        OBSERVED_BOTH_APIS("observed-both-apis"),
        HANDSHAKE_VERIFIED("handshake-verified"),
        UNVERIFIED("unverified");

        private final String wireName;

        MappingBasis(String wireName) {
            this.wireName = wireName;
        }

        /** @return the exact string used on the wire */
        public String wireName() {
            return wireName;
        }
    }

    private final String instanceId;
    private final String bootId;
    private final Optional<String> connectionSessionId;
    private final Optional<String> worldSessionId;
    private final Optional<String> launchProfileName;
    private final Optional<String> launchProfileUuid;
    private final Optional<String> joinedPlayerName;
    private final Optional<String> joinedPlayerUuid;
    private final Optional<String> dimension;
    private final Optional<String> entityIdentifier;
    private final Optional<String> targetAddress;
    private final MappingBasis mappingBasis;

    /** Private: use {@link Builder}. */
    private ParticipantView(Builder builder) {
        this.instanceId = builder.instanceId;
        this.bootId = builder.bootId;
        this.connectionSessionId = builder.connectionSessionId;
        this.worldSessionId = builder.worldSessionId;
        this.launchProfileName = builder.launchProfileName;
        this.launchProfileUuid = builder.launchProfileUuid;
        this.joinedPlayerName = builder.joinedPlayerName;
        this.joinedPlayerUuid = builder.joinedPlayerUuid;
        this.dimension = builder.dimension;
        this.entityIdentifier = builder.entityIdentifier;
        this.targetAddress = builder.targetAddress;
        this.mappingBasis = builder.mappingBasis;
    }

    /** @return the instance this view belongs to */
    public String instanceId() {
        return instanceId;
    }

    /** @return the process boot this view belongs to */
    public String bootId() {
        return bootId;
    }

    /** @return current connection session, if any */
    public Optional<String> connectionSessionId() {
        return connectionSessionId;
    }

    /** @return current world session, if any */
    public Optional<String> worldSessionId() {
        return worldSessionId;
    }

    /** @return local launch profile name, if known */
    public Optional<String> launchProfileName() {
        return launchProfileName;
    }

    /** @return local launch profile UUID, if known */
    public Optional<String> launchProfileUuid() {
        return launchProfileUuid;
    }

    /** @return authoritative joined player name, if established after joining */
    public Optional<String> joinedPlayerName() {
        return joinedPlayerName;
    }

    /** @return authoritative joined player UUID, if established after joining */
    public Optional<String> joinedPlayerUuid() {
        return joinedPlayerUuid;
    }

    /** @return current dimension identifier, if connected */
    public Optional<String> dimension() {
        return dimension;
    }

    /** @return the player's entity identifier, if connected */
    public Optional<String> entityIdentifier() {
        return entityIdentifier;
    }

    /** @return configured target address, if any */
    public Optional<String> targetAddress() {
        return targetAddress;
    }

    /** @return how the mapping was established, never {@code null} */
    public MappingBasis mappingBasis() {
        return mappingBasis;
    }

    /** @return the view as an ordered map for JSON serialization */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("instanceId", instanceId);
        map.put("bootId", bootId);
        connectionSessionId.ifPresent(v -> map.put("connectionSessionId", v));
        worldSessionId.ifPresent(v -> map.put("worldSessionId", v));
        launchProfileName.ifPresent(v -> map.put("launchProfileName", v));
        launchProfileUuid.ifPresent(v -> map.put("launchProfileUuid", v));
        joinedPlayerName.ifPresent(v -> map.put("joinedPlayerName", v));
        joinedPlayerUuid.ifPresent(v -> map.put("joinedPlayerUuid", v));
        dimension.ifPresent(v -> map.put("dimension", v));
        entityIdentifier.ifPresent(v -> map.put("entityIdentifier", v));
        targetAddress.ifPresent(v -> map.put("targetAddress", v));
        map.put("mappingBasis", mappingBasis.wireName());
        return map;
    }

    /** Builder for {@link ParticipantView}. */
    public static final class Builder {

        private final String instanceId;
        private final String bootId;
        private Optional<String> connectionSessionId = Optional.empty();
        private Optional<String> worldSessionId = Optional.empty();
        private Optional<String> launchProfileName = Optional.empty();
        private Optional<String> launchProfileUuid = Optional.empty();
        private Optional<String> joinedPlayerName = Optional.empty();
        private Optional<String> joinedPlayerUuid = Optional.empty();
        private Optional<String> dimension = Optional.empty();
        private Optional<String> entityIdentifier = Optional.empty();
        private Optional<String> targetAddress = Optional.empty();
        private MappingBasis mappingBasis = MappingBasis.UNVERIFIED;

        /**
         * @param identity the instance identity this view belongs to, never
         *                 {@code null}
         */
        public Builder(InstanceIdentity identity) {
            Objects.requireNonNull(identity, "identity");
            this.instanceId = identity.instanceId();
            this.bootId = identity.bootId();
        }

        /** @param value connection session id or {@code null} */
        public Builder connectionSessionId(String value) {
            this.connectionSessionId = Optional.ofNullable(value);
            return this;
        }

        /** @param value world session id or {@code null} */
        public Builder worldSessionId(String value) {
            this.worldSessionId = Optional.ofNullable(value);
            return this;
        }

        /** @param value launch profile name or {@code null} */
        public Builder launchProfileName(String value) {
            this.launchProfileName = Optional.ofNullable(value);
            return this;
        }

        /** @param value launch profile UUID or {@code null} */
        public Builder launchProfileUuid(String value) {
            this.launchProfileUuid = Optional.ofNullable(value);
            return this;
        }

        /** @param value joined player name or {@code null} */
        public Builder joinedPlayerName(String value) {
            this.joinedPlayerName = Optional.ofNullable(value);
            return this;
        }

        /** @param value joined player UUID or {@code null} */
        public Builder joinedPlayerUuid(String value) {
            this.joinedPlayerUuid = Optional.ofNullable(value);
            return this;
        }

        /** @param value dimension identifier or {@code null} */
        public Builder dimension(String value) {
            this.dimension = Optional.ofNullable(value);
            return this;
        }

        /** @param value entity identifier or {@code null} */
        public Builder entityIdentifier(String value) {
            this.entityIdentifier = Optional.ofNullable(value);
            return this;
        }

        /** @param value target address or {@code null} */
        public Builder targetAddress(String value) {
            this.targetAddress = Optional.ofNullable(value);
            return this;
        }

        /** @param value mapping basis, never {@code null} */
        public Builder mappingBasis(MappingBasis value) {
            this.mappingBasis = Objects.requireNonNull(value, "mappingBasis");
            return this;
        }

        /** @return the built view */
        public ParticipantView build() {
            return new ParticipantView(this);
        }
    }
}
