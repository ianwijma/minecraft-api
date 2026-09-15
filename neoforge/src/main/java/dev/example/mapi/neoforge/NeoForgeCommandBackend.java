package dev.example.mapi.neoforge;

import dev.example.mapi.internal.command.CommandBackend;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/**
 * Fabric command backend: executes commands in the server console context
 * (verified 26.2 API: {@code createCommandSourceStack} +
 * {@code Commands.performPrefixedCommand} + {@link CommandResultCallback}).
 * Executes on the server thread only. Completion is tracked with a bounded
 * wait on the command result callback.
 */
final class NeoForgeCommandBackend implements CommandBackend {

    /** Bound for command-chain completion (spec §4.3: wall-clock deadlines). */
    static final long COMPLETION_TIMEOUT_MS = 5_000;

    private final MinecraftServer server;

    NeoForgeCommandBackend(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CommandResult execute(String commandLine) {
        CompletableFuture<Integer> completion = new CompletableFuture<>();
        CommandSourceStack source = server.createCommandSourceStack()
                .withCallback((boolean success, int result) -> {
                    if (success) {
                        completion.complete(result);
                    } else {
                        completion.completeExceptionally(new CommandFailedException());
                    }
                });
        try {
            server.getCommands().performPrefixedCommand(source, commandLine);
        } catch (RuntimeException e) {
            return new CommandResult(false, false, Optional.of(String.valueOf(e)), 0);
        }
        try {
            int resultCode = completion.get(COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return new CommandResult(true, true, Optional.empty(), resultCode);
        } catch (TimeoutException e) {
            return new CommandResult(true, false,
                    Optional.of("command chain did not complete within " + COMPLETION_TIMEOUT_MS + " ms"),
                    0);
        } catch (Exception e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException
                    && e.getCause() != null ? e.getCause() : e;
            return new CommandResult(true, false, Optional.of(String.valueOf(cause)), 0);
        }
    }

    /** Marker for the failure path of the command result callback. */
    private static final class CommandFailedException extends RuntimeException {
    }
}
