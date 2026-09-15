package dev.example.mapi.internal.action;

import dev.example.mapi.internal.clock.ClockId;
import dev.example.mapi.internal.encoding.Tag;
import dev.example.mapi.internal.operation.ExecutionMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Receipt produced by every executed action (spec §3.4). The three outcome
 * layers stay distinct fields — "input delivered" ({@code dispatchOutcome}),
 * "client state changed" ({@code clientStateChanged}), and "server effect
 * confirmed" ({@code effectVerified}) — so callers can never conflate them.
 *
 * @param actionId             unique action identifier
 * @param requestId            caller request identifier
 * @param requestedMode        execution mode the caller selected
 * @param actualMode           execution mode actually used (equal to
 *                             requested — there is no silent fallback)
 * @param backendId            input backend identifier, if input was involved
 * @param backendVersion       input backend version, if input was involved
 * @param clock                which boundary clock the start/end counters refer to
 * @param startBoundary        boundary at dispatch start, if observed
 * @param endBoundary          boundary at dispatch end, if observed
 * @param worldSessionId       world the action ran in, if world-scoped
 * @param connectionSessionId  connection the action ran on, if any
 * @param screenRevision       client screen revision, if a screen was involved
 * @param containerGeneration  container generation, if a container was involved
 * @param dispatchOutcome      what happened at the input-dispatch layer
 * @param clientStateChanged   whether client state changed (observed, not
 *                             assumed)
 * @param effectVerified       verification status of the server-side effect
 * @param verificationEvidence typed evidence for a CONFIRMED verification, if
 *                             requested and produced
 * @param note                 human-readable detail for partial executions or
 *                             cancellations
 */
public record ActionReceipt(
        String actionId,
        String requestId,
        ExecutionMode requestedMode,
        ExecutionMode actualMode,
        Optional<String> backendId,
        Optional<String> backendVersion,
        Optional<ClockId> clock,
        Optional<Long> startBoundary,
        Optional<Long> endBoundary,
        Optional<String> worldSessionId,
        Optional<String> connectionSessionId,
        Optional<Long> screenRevision,
        Optional<Long> containerGeneration,
        DispatchOutcome dispatchOutcome,
        Optional<Boolean> clientStateChanged,
        EffectVerification effectVerified,
        Optional<Tag> verificationEvidence,
        Optional<String> note) {

    /** What happened at the input-dispatch layer (the "input delivered" layer). */
    public enum DispatchOutcome {
        DISPATCHED("dispatched"),
        REJECTED_PRE_DISPATCH("rejected-pre-dispatch"),
        PARTIAL("partial"),
        CANCELLED("cancelled");

        private final String wireName;

        DispatchOutcome(String wireName) {
            this.wireName = wireName;
        }

        /** @return the exact string used on the wire */
        public String wireName() {
            return wireName;
        }
    }

    /** Verification status of the server-side effect ("server effect confirmed" layer). */
    public enum EffectVerification {
        NOT_REQUESTED("not-requested"),
        PENDING("pending"),
        CONFIRMED("confirmed"),
        REFUTED("refuted"),
        UNAVAILABLE("unavailable");

        private final String wireName;

        EffectVerification(String wireName) {
            this.wireName = wireName;
        }

        /** @return the exact string used on the wire */
        public String wireName() {
            return wireName;
        }
    }

    public ActionReceipt {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(requestId, "requestId");
        requestedMode = Objects.requireNonNull(requestedMode, "requestedMode");
        actualMode = Objects.requireNonNull(actualMode, "actualMode");
        backendId = backendId == null ? Optional.empty() : backendId;
        backendVersion = backendVersion == null ? Optional.empty() : backendVersion;
        clock = clock == null ? Optional.empty() : clock;
        startBoundary = startBoundary == null ? Optional.empty() : startBoundary;
        endBoundary = endBoundary == null ? Optional.empty() : endBoundary;
        worldSessionId = worldSessionId == null ? Optional.empty() : worldSessionId;
        connectionSessionId = connectionSessionId == null ? Optional.empty() : connectionSessionId;
        screenRevision = screenRevision == null ? Optional.empty() : screenRevision;
        containerGeneration = containerGeneration == null ? Optional.empty() : containerGeneration;
        dispatchOutcome = Objects.requireNonNull(dispatchOutcome, "dispatchOutcome");
        clientStateChanged = clientStateChanged == null ? Optional.empty() : clientStateChanged;
        effectVerified = Objects.requireNonNull(effectVerified, "effectVerified");
        verificationEvidence = verificationEvidence == null ? Optional.empty() : verificationEvidence;
        note = note == null ? Optional.empty() : note;
        if (requestedMode != actualMode) {
            throw new IllegalArgumentException(
                    "actual mode must equal requested mode: no silent fallback (spec §3.3)");
        }
        if (effectVerified == EffectVerification.CONFIRMED && verificationEvidence.isEmpty()) {
            throw new IllegalArgumentException("CONFIRMED verification requires evidence");
        }
        if ((dispatchOutcome == DispatchOutcome.PARTIAL
                || dispatchOutcome == DispatchOutcome.CANCELLED) && note.isEmpty()) {
            throw new IllegalArgumentException(
                    "partial and cancelled dispatches require an explanatory note");
        }
    }

    /** @return the receipt as an ordered map for JSON serialization */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("actionId", actionId);
        map.put("requestId", requestId);
        map.put("requestedMode", requestedMode.wireName());
        map.put("actualMode", actualMode.wireName());
        backendId.ifPresent(value -> map.put("backendId", value));
        backendVersion.ifPresent(value -> map.put("backendVersion", value));
        clock.ifPresent(value -> map.put("clock", value.wireName()));
        startBoundary.ifPresent(value -> map.put("startBoundary", value));
        endBoundary.ifPresent(value -> map.put("endBoundary", value));
        worldSessionId.ifPresent(value -> map.put("worldSessionId", value));
        connectionSessionId.ifPresent(value -> map.put("connectionSessionId", value));
        screenRevision.ifPresent(value -> map.put("screenRevision", value));
        containerGeneration.ifPresent(value -> map.put("containerGeneration", value));
        map.put("dispatchOutcome", dispatchOutcome.wireName());
        clientStateChanged.ifPresent(value -> map.put("clientStateChanged", value));
        map.put("effectVerified", effectVerified.wireName());
        verificationEvidence.ifPresent(value -> map.put("verificationEvidence",
                dev.example.mapi.internal.encoding.TagJson.toWire(value)));
        note.ifPresent(value -> map.put("note", value));
        return map;
    }

    /** Builder for {@link ActionReceipt}; {@code actualMode} defaults to the requested mode. */
    public static final class Builder {

        private final String actionId;
        private final String requestId;
        private final ExecutionMode requestedMode;
        private ExecutionMode actualMode;
        private Optional<String> backendId = Optional.empty();
        private Optional<String> backendVersion = Optional.empty();
        private Optional<ClockId> clock = Optional.empty();
        private Optional<Long> startBoundary = Optional.empty();
        private Optional<Long> endBoundary = Optional.empty();
        private Optional<String> worldSessionId = Optional.empty();
        private Optional<String> connectionSessionId = Optional.empty();
        private Optional<Long> screenRevision = Optional.empty();
        private Optional<Long> containerGeneration = Optional.empty();
        private DispatchOutcome dispatchOutcome;
        private Optional<Boolean> clientStateChanged = Optional.empty();
        private EffectVerification effectVerification;
        private Optional<Tag> verificationEvidence = Optional.empty();
        private Optional<String> note = Optional.empty();

        private Builder(String actionId, String requestId, ExecutionMode requestedMode) {
            this.actionId = actionId;
            this.requestId = requestId;
            this.requestedMode = Objects.requireNonNull(requestedMode, "requestedMode");
            this.actualMode = requestedMode;
        }

        /** @param value actual mode; defaults to the requested mode */
        public Builder actualMode(ExecutionMode value) {
            this.actualMode = Objects.requireNonNull(value, "actualMode");
            return this;
        }

        /** @param value input backend identifier or {@code null} */
        public Builder backendId(String value) {
            this.backendId = Optional.ofNullable(value);
            return this;
        }

        /** @param value input backend version or {@code null} */
        public Builder backendVersion(String value) {
            this.backendVersion = Optional.ofNullable(value);
            return this;
        }

        /** @param value boundary clock or {@code null} */
        public Builder clock(ClockId value) {
            this.clock = Optional.ofNullable(value);
            return this;
        }

        /** @param value dispatch-start boundary or {@code null} */
        public Builder startBoundary(Long value) {
            this.startBoundary = Optional.ofNullable(value);
            return this;
        }

        /** @param value dispatch-end boundary or {@code null} */
        public Builder endBoundary(Long value) {
            this.endBoundary = Optional.ofNullable(value);
            return this;
        }

        /** @param value world session id or {@code null} */
        public Builder worldSessionId(String value) {
            this.worldSessionId = Optional.ofNullable(value);
            return this;
        }

        /** @param value connection session id or {@code null} */
        public Builder connectionSessionId(String value) {
            this.connectionSessionId = Optional.ofNullable(value);
            return this;
        }

        /** @param value screen revision or {@code null} */
        public Builder screenRevision(Long value) {
            this.screenRevision = Optional.ofNullable(value);
            return this;
        }

        /** @param value container generation or {@code null} */
        public Builder containerGeneration(Long value) {
            this.containerGeneration = Optional.ofNullable(value);
            return this;
        }

        /** @param value dispatch outcome, never {@code null} */
        public Builder dispatchOutcome(DispatchOutcome value) {
            this.dispatchOutcome = Objects.requireNonNull(value, "dispatchOutcome");
            return this;
        }

        /** @param value observed client-state change or {@code null} for unknown */
        public Builder clientStateChanged(Boolean value) {
            this.clientStateChanged = Optional.ofNullable(value);
            return this;
        }

        /** @param value effect verification status, never {@code null} */
        public Builder effectVerified(EffectVerification value) {
            this.effectVerification = Objects.requireNonNull(value, "effectVerification");
            return this;
        }

        /** @param value typed verification evidence or {@code null} */
        public Builder verificationEvidence(Tag value) {
            this.verificationEvidence = Optional.ofNullable(value);
            return this;
        }

        /** @param value explanatory note or {@code null} */
        public Builder note(String value) {
            this.note = Optional.ofNullable(value);
            return this;
        }

        /** @return the built receipt */
        public ActionReceipt build() {
            return new ActionReceipt(actionId, requestId, requestedMode, actualMode,
                    backendId, backendVersion, clock, startBoundary, endBoundary,
                    worldSessionId, connectionSessionId, screenRevision, containerGeneration,
                    dispatchOutcome, clientStateChanged, effectVerification,
                    verificationEvidence, note);
        }
    }

    /**
     * @param actionId      unique action identifier
     * @param requestId     caller request identifier
     * @param requestedMode the caller-selected execution mode
     * @return a receipt builder
     */
    public static Builder builder(String actionId, String requestId, ExecutionMode requestedMode) {
        return new Builder(actionId, requestId, requestedMode);
    }
}
