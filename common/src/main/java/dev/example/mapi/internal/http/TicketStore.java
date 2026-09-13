package dev.example.mapi.internal.http;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single-use, short-lived tickets for browser-style WebSocket connects
 * (spec §4.5): an authenticated HTTP request mints a ticket, the browser
 * presents it once as a query parameter, and it is consumed on use.
 */
public final class TicketStore {

    /** Ticket time-to-live in ms. */
    public static final long TTL_MS = 30_000;

    /** Maximum live tickets; oldest entries are dropped beyond this. */
    public static final int MAX_LIVE = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Object lock = new Object();
    private final Map<String, Long> tickets = new LinkedHashMap<>();

    /**
     * Mints a ticket valid for {@link #TTL_MS}.
     *
     * @return the ticket string
     */
    public String mint() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        synchronized (lock) {
            tickets.put(ticket, System.currentTimeMillis() + TTL_MS);
            while (tickets.size() > MAX_LIVE) {
                Iterator<String> iterator = tickets.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
        }
        return ticket;
    }

    /**
     * Consumes a ticket: valid exactly once and only before expiry.
     *
     * @param ticket the presented ticket
     * @return true when the ticket was valid and is now spent
     */
    public boolean consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        synchronized (lock) {
            evictExpired();
            return tickets.remove(ticket) != null;
        }
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        tickets.values().removeIf(expiresAt -> now > expiresAt);
    }

    /**
     * @return number of live tickets (observability)
     */
    public int liveCount() {
        synchronized (lock) {
            evictExpired();
            return tickets.size();
        }
    }
}
