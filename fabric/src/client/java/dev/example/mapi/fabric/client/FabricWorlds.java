package dev.example.mapi.fabric.client;

import dev.example.mapi.internal.client.ClientBridge;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

/** Fabric worlds backend (list/load/delete over the level storage). */
final class FabricWorlds implements ClientBridge.WorldsBackend {

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
    public void loadWorld(String levelId) throws Exception {
        if (levelId == null || levelId.isBlank()) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "levelId is required");
        }
        Minecraft client = Minecraft.getInstance();
        client.createWorldOpenFlows().openWorld(levelId,
                () -> client.gui.setScreen((net.minecraft.client.gui.screens.Screen) null));
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
