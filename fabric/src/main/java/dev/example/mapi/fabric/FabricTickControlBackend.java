package dev.example.mapi.fabric;

import dev.example.mapi.internal.tick.TickControlBackend;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;

/**
 * Fabric tick-control backend backed by the vanilla
 * {@link ServerTickRateManager}. Executes on the server thread only.
 * Sprinting is asynchronous in vanilla; the backend records the request and
 * callers poll {@link #state()} for completion.
 */
final class FabricTickControlBackend implements TickControlBackend {

    private final MinecraftServer server;

    FabricTickControlBackend(MinecraftServer server) {
        this.server = server;
    }

    private ServerTickRateManager manager() {
        return server.tickRateManager();
    }

    @Override
    public State state() {
        ServerTickRateManager manager = manager();
        return new State(manager.isFrozen(), manager.isSprinting(), manager.tickrate(),
                server.getTickCount(), Optional.empty());
    }

    @Override
    public boolean freeze() {
        manager().setFrozen(true);
        return manager().isFrozen();
    }

    @Override
    public boolean unfreeze() {
        manager().setFrozen(false);
        return !manager().isFrozen();
    }

    @Override
    public Optional<Float> setTickRate(float rate) {
        manager().setTickRate(rate);
        return Optional.of(manager().tickrate());
    }

    @Override
    public StepResult step(int ticks) {
        long before = server.getTickCount();
        boolean accepted = manager().stepGameIfPaused(ticks);
        if (!accepted) {
            return new StepResult(ticks, 0, before);
        }
        // 26.2 stepping advances the count over subsequent frames — poll
        // briefly. Safe to sleep on the server thread here: the tick loop is
        // frozen, only step frames run.
        long deadline = System.currentTimeMillis() + 2000;
        long after = before;
        while (System.currentTimeMillis() < deadline) {
            after = server.getTickCount();
            if (after - before >= ticks) {
                break;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        after = server.getTickCount();
        int completed = (int) Math.max(0, Math.min(ticks, after - before));
        return new StepResult(ticks, completed, after);
    }

    @Override
    public Optional<StepResult> sprint(int ticks) {
        boolean accepted = manager().requestGameToSprint(ticks);
        return Optional.of(new StepResult(ticks, accepted ? 0 : 0, server.getTickCount()));
    }

    @Override
    public boolean stopStepping() {
        return manager().stopStepping();
    }

    @Override
    public boolean stopSprinting() {
        return manager().stopSprinting();
    }

    @Override
    public boolean supportsStepping() {
        return true;
    }

    @Override
    public boolean supportsSprinting() {
        return true;
    }

    @Override
    public boolean supportsRate() {
        return true;
    }
}
