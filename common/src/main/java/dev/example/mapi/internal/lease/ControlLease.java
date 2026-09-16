package dev.example.mapi.internal.lease;

import java.util.Optional;

/**
 * An exclusive control lease on one topic (spec §5, §14). Ownership is
 * wall-clock bounded: a lease expires at its deadline unless renewed, and
 * revocation is enforced immediately at the API level by the
 * {@link LeaseManager} watchdog — actual game-state cleanup happens at the
 * next safe game-thread opportunity (spec §4.5).
 */
public final class ControlLease {

    private final String id;
    private final String topic;
    private final String owner;
    private volatile long expiresAtEpochMs;
    private volatile boolean revoked;
    private volatile long revokedAtEpochMs;

    ControlLease(String id, String topic, String owner, long expiresAtEpochMs) {
        this.id = id;
        this.topic = topic;
        this.owner = owner;
        this.expiresAtEpochMs = expiresAtEpochMs;
    }

    /** @return the lease identifier, never blank */
    public String id() {
        return id;
    }

    /** @return the exclusive topic this lease controls */
    public String topic() {
        return topic;
    }

    /** @return the owner label supplied at acquisition */
    public String owner() {
        return owner;
    }

    /** @return the current wall-clock expiry in epoch milliseconds */
    public long expiresAtEpochMs() {
        return expiresAtEpochMs;
    }

    /** @return true once the lease was revoked or expired */
    public boolean revoked() {
        return revoked;
    }

    /** @return when the lease was revoked, if it was */
    public Optional<Long> revokedAtEpochMs() {
        return revoked ? Optional.of(revokedAtEpochMs) : Optional.empty();
    }

    void extend(long newExpiryEpochMs) {
        this.expiresAtEpochMs = newExpiryEpochMs;
    }

    void revoke(long atEpochMs) {
        this.revoked = true;
        this.revokedAtEpochMs = atEpochMs;
    }

    /** @return true while the lease is held: not revoked and not past expiry */
    public boolean held(long nowEpochMs) {
        return !revoked && nowEpochMs < expiresAtEpochMs;
    }
}
