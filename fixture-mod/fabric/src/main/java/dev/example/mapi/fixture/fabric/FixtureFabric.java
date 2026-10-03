package dev.example.mapi.fixture.fabric;

import dev.example.mapi.fixture.ApiConsumer;
import net.fabricmc.api.ModInitializer;

/** Acceptance-only Fabric registration. */
public final class FixtureFabric implements ModInitializer {
    /** Starts the public API consumer after MAPI bootstrap becomes available. */
    @Override
    public void onInitialize() {
        ApiConsumer.start();
    }
}
