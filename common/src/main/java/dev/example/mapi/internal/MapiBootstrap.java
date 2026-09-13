package dev.example.mapi.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstrap seam between loader entrypoints and the MAPI runtime. Loader
 * modules call {@link #initialize(MapiPlatform)} during their construction or
 * initialization phase; everything else is internal wiring.
 */
public final class MapiBootstrap {

    /**
     * Version of the public Java API. Equal to the mod version for MAPI 0.x.
     */
    public static final String API_VERSION;

    /**
     * MAPI mod id ({@value}).
     */
    public static final String MOD_ID = "mapi";

    private static final Logger LOG = LoggerFactory.getLogger("mapi");

    static final String MOD_VERSION;
    static final String NAME;
    static final String DISPLAY_NAME;

    private static boolean initialized;

    static {
        Properties meta = loadMetadata();
        MOD_VERSION = meta.getProperty("version", "unknown");
        NAME = meta.getProperty("name", MOD_ID);
        DISPLAY_NAME = meta.getProperty("displayName", "Minecraft API");
        API_VERSION = MOD_VERSION;
    }

    private MapiBootstrap() {
    }

    /**
     * Initializes MAPI exactly once. Later calls are ignored with a warning
     * (relevant for hot-reload dev environments).
     *
     * @param platform the loader platform adapter, never {@code null}
     */
    public static synchronized void initialize(MapiPlatform platform) {
        Objects.requireNonNull(platform, "platform");
        if (initialized) {
            LOG.warn("MAPI: initialize() called more than once; ignoring duplicate call");
            return;
        }
        initialized = true;
        MapiRuntime runtime = new MapiRuntime(platform);
        dev.example.mapi.api.MapiApi.bind(runtime);
        LOG.info("MAPI {} initialized (platform={}, minecraft={})", MOD_VERSION, platform.type().id(),
                platform.minecraftVersion());
    }

    private static Properties loadMetadata() {
        try (InputStream in = MapiBootstrap.class.getResourceAsStream("/mapi/metadata.properties")) {
            if (in == null) {
                throw new IllegalStateException("MAPI metadata resource /mapi/metadata.properties is missing "
                        + "(broken build: common module resources were not expanded)");
            }
            Properties props = new Properties();
            props.load(in);
            return props;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read MAPI metadata resource", e);
        }
    }

    /**
     * @return MAPI version, never {@code null}
     */
    public static String modVersion() {
        return MOD_VERSION;
    }
}
