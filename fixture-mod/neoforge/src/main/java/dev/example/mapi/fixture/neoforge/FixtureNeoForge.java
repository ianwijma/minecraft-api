package dev.example.mapi.fixture.neoforge;

import dev.example.mapi.fixture.ApiConsumer;
import net.neoforged.fml.common.Mod;

/** Acceptance-only NeoForge registration. */
@Mod("mapi_fixture")
public final class FixtureNeoForge {
    /** Starts the public API consumer after MAPI bootstrap becomes available. */
    public FixtureNeoForge() {
        ApiConsumer.start();
    }
}
