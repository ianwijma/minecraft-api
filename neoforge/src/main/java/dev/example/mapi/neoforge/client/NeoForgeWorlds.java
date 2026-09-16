package dev.example.mapi.neoforge.client;

import dev.example.mapi.internal.client.ClientBridge;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

/**
 * NeoForge worlds backend (list/load/delete over the level storage).
 * Listing runs off-thread (vanilla UI pattern: loadLevelSummaries is
 * async-safe); load and delete run on the client thread.
 */
public final class NeoForgeWorlds implements ClientBridge.WorldsBackend {

    private LevelStorageSource storage() {
        LevelStorageSource storage = Minecraft.getInstance().getLevelSource();
        if (storage == null) {
            throw new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                    "level storage unavailable");
        }
        return storage;
    }

    @Override
    public List<ClientBridge.WorldsBackend.WorldEntry> listWorlds() throws Exception {
        try {
            List<ClientBridge.WorldsBackend.WorldEntry> entries = new ArrayList<>();
            var summaries = storage().loadLevelSummaries(storage().findLevelCandidates())
                    .get(5, TimeUnit.SECONDS);
            for (LevelSummary summary : summaries) {
                if (!summary.isDisabled()) {
                    entries.add(new ClientBridge.WorldsBackend.WorldEntry(
                            summary.getLevelId(), summary.getLevelName(),
                            summary.requiresManualConversion(),
                            summary.requiresFileFixing(),
                            summary.isExperimental()));
                }
            }
            return entries;
        } catch (net.minecraft.world.level.storage.LevelStorageException e) {
            throw new ProblemException(ProblemCode.INTERNAL,
                    "level storage read failed: " + e.getMessage());
        }
    }

    @Override
    public void createWorld(String levelId, String gamemode, Long seed) throws Exception {
        if (levelId == null || levelId.isBlank()) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "levelId is required");
        }
        // Creation over an existing id must fail, never overwrite.
        if (Files.exists(Minecraft.getInstance().gameDirectory.toPath()
                .resolve("saves").resolve(levelId))) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "a world with this id already exists: " + levelId);
        }
        var gameType = gamemodeOf(gamemode == null ? "survival" : gamemode);
        var settings = new net.minecraft.world.level.LevelSettings(
                levelId,
                gameType,
                net.minecraft.world.level.LevelSettings.DifficultySettings.DEFAULT,
                false,
                net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
        var options = new net.minecraft.world.level.levelgen.WorldOptions(
                seed == null ? net.minecraft.world.level.levelgen.WorldOptions.randomSeed()
                        : seed,
                true, false);
        Minecraft.getInstance().createWorldOpenFlows().createFreshLevel(levelId,
                settings, options,
                registryAccess -> registryAccess.lookupOrThrow(
                                net.minecraft.core.registries.Registries.WORLD_PRESET)
                        .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL)
                        .value()
                        .createWorldDimensions(),
                null);
    }

    @Override
    public void loadWorld(String levelId) throws Exception {
        if (levelId == null || levelId.isBlank()) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "levelId is required");
        }
        Minecraft client = Minecraft.getInstance();
        client.createWorldOpenFlows().openWorld(levelId,
                () -> client.gui.setScreen((net.minecraft.client.gui.screens.Screen) null));
    }

    private net.minecraft.world.level.GameType gamemodeOf(String name) {
        if (name == null || name.isBlank()) {
            return net.minecraft.world.level.GameType.SURVIVAL;
        }
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "survival" -> net.minecraft.world.level.GameType.SURVIVAL;
            case "creative" -> net.minecraft.world.level.GameType.CREATIVE;
            case "adventure" -> net.minecraft.world.level.GameType.ADVENTURE;
            case "spectator" -> net.minecraft.world.level.GameType.SPECTATOR;
            default -> throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "unknown gamemode: " + name);
        };
    }

    @Override
    public void deleteWorld(String levelId) throws Exception {
        if (levelId == null || levelId.isBlank()) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "levelId is required");
        }
        var access = storage().validateAndCreateAccess(levelId);
        try {
            access.deleteLevel();
        } finally {
            access.close();
        }
    }
}
