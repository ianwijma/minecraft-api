package dev.example.mapi.internal.command;

import java.util.Optional;

/**
 * Loader-neutral backend for console-context command execution (spec §20).
 * Implemented per loader against the vanilla command dispatcher; executed on
 * the server thread.
 */
public interface CommandBackend {

    /**
     * Outcome of one command execution.
     *
     * @param dispatched     true when the dispatcher accepted and started the
     *                       command (distinct from its effects — asynchronous
     *                       effects are NOT confirmed here, spec §20)
     * @param success        true when the command chain completed successfully
     * @param failureMessage failure detail when success is false
     * @param resultCode     brigadier result code when completed
     */
    record CommandResult(boolean dispatched, boolean success,
            Optional<String> failureMessage, int resultCode) {

        public CommandResult {
            failureMessage = failureMessage == null ? Optional.empty() : failureMessage;
        }
    }

    /**
     * Executes a command in the server console context.
     *
     * @param commandLine the command line without leading slash
     * @return the execution outcome
     */
    CommandResult execute(String commandLine);
}
