package dev.example.mapi.internal.command;

import dev.example.mapi.internal.event.EventBus;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Console-context command dispatch (spec §20). Dispatch and asynchronous
 * effects are distinguished: a dispatched command reports its own completion
 * only; effects scheduled for later ticks are NOT confirmed synchronously.
 *
 * <p>Authorization: command dispatch is an unrestricted/administrative
 * operation (spec §14 — arbitrary mod commands cannot be safely classified);
 * the HTTP layer enforces the {@code operations:unrestricted} grant.
 *
 * <p>Threading: the backend owns dispatch threading and must never park the
 * server thread waiting for a command-chain callback (that deadlocks — the
 * callback resolves on a later tick of that same thread). Routing through
 * the bounded {@link dev.example.mapi.internal.query.ServerThreadRunner} is
 * therefore deliberately NOT used here.
 */
public final class CommandDispatchService {

    /** Maximum accepted command line length. */
    public static final int MAX_COMMAND_LENGTH = 4096;

    private final CommandBackend backend;
    private final EventBus events;

    /**
     * @param backend loader backend, never {@code null}
     * @param events  event bus for dispatch notices
     */
    public CommandDispatchService(CommandBackend backend, EventBus events) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Dispatches a command.
     *
     * @param commandLine command without leading slash
     * @return the outcome as an ordered map for JSON serialization
     */
    public Map<String, Object> dispatch(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "command is required");
        }
        String command = commandLine.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "command exceeds " + MAX_COMMAND_LENGTH + " characters");
        }
        CommandBackend.CommandResult result = call(command);
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("dispatched", result.dispatched());
        eventPayload.put("success", result.success());
        result.failureMessage().ifPresent(value -> eventPayload.put("failure", value));
        eventPayload.put("resultCode", result.resultCode());
        events.publish("command.dispatched", Optional.empty(), eventPayload);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dispatched", result.dispatched());
        map.put("success", result.success());
        result.failureMessage().ifPresent(value -> map.put("failure", value));
        map.put("resultCode", result.resultCode());
        return map;
    }

    private CommandBackend.CommandResult call(String command) {
        try {
            return backend.execute(command);
        } catch (ProblemException e) {
            throw e;
        } catch (Exception e) {
            throw new ProblemException(ProblemCode.INTERNAL, "command dispatch failed: " + e);
        }
    }
}
