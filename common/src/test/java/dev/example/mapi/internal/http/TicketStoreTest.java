package dev.example.mapi.internal.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TicketStoreTest {

    @Test
    void ticketIsSingleUse() {
        TicketStore store = new TicketStore();
        String ticket = store.mint();
        assertTrue(store.consume(ticket));
        assertFalse(store.consume(ticket), "tickets must be single-use");
        assertFalse(store.consume("bogus"));
        assertFalse(store.consume(null));
    }
}
