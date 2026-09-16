package dev.example.mapi.fabric;

import dev.example.mapi.internal.config.MapiConfig;
import dev.example.mapi.internal.config.MapiJsonConfigFile;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Fabric configuration: loader convention on Fabric is a hand-rolled JSON
 * file in {@code config/} (no built-in config system). The file is generated
 * with defaults on first run; environment variables win over file values.
 */
final class FabricConfig {

    private FabricConfig() {
    }

    /**
     * @param configDir instance config directory
     * @param env       process environment
     * @param logger    platform logger
     * @return the validated configuration
     */
    static MapiConfig load(Path configDir, Map<String, String> env, Logger logger) {
        return MapiJsonConfigFile.load(configDir, env, logger);
    }
}
