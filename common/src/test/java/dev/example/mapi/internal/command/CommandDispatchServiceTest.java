package dev.example.mapi.internal.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.event.EventBus;
import dev.example.mapi.internal.event.EventFilter;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import dev.example.mapi.internal.query.ServerThreadRunner;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CommandDispatchServiceTest {

    @Test
    void blankCommandsAreRefused() {
        CommandDispatchService service = service(backend -> new CommandBackend.CommandResult(
                true, true, Optional.empty(), 1));
        assertThrows(ProblemException.class, () -> service.dispatch("  "));
        assertThrows(ProblemException.class, () -> service.dispatch(null));
        ProblemException tooLong = assertThrows(ProblemException.class,
                () -> service.dispatch("x".repeat(5000)));
        assertEquals(ProblemCode.BAD_REQUEST, tooLong.code());
    }

    @Test
    void leadingSlashIsTrimmedAndResultIsMapped() {
        CommandDispatchService service = service(command -> {
            assertEquals("say hi", command);
            return new CommandBackend.CommandResult(true, true, Optional.empty(), 2);
        });
        var result = service.dispatch("/say hi");
        assertEquals(true, result.get("dispatched"));
        assertEquals(true, result.get("success"));
        assertEquals(2, result.get("resultCode"));

        List<dev.example.mapi.internal.event.Event> dispatched = events().eventsAfter(0,
                new EventFilter(java.util.Set.of("command.dispatched"), Optional.empty()), 10).events();
        assertEquals(1, dispatched.size());
        assertEquals(true, dispatched.get(0).payload().get("success"));
    }

    @Test
    void dispatchAndFailureStayDistinct() {
        CommandDispatchService service = service(command -> new CommandBackend.CommandResult(
                true, false, Optional.of("unknown command: nope"), 0));
        var result = service.dispatch("nope");
        assertEquals(true, result.get("dispatched"));
        assertEquals(false, result.get("success"));
        assertEquals("unknown command: nope", result.get("failure"));
    }

    @Test
    void rejectedDispatchIsReported() {
        CommandDispatchService service = service(command -> new CommandBackend.CommandResult(
                false, false, Optional.of("rejected"), 0));
        var result = service.dispatch("bad");
        assertEquals(false, result.get("dispatched"));
    }

    private final EventBus bus = new EventBus();

    private EventBus events() {
        return bus;
    }

    private CommandDispatchService service(java.util.function.Function<String,
            CommandBackend.CommandResult> impl) {
        CommandBackend backend = command -> impl.apply(command);
        return new CommandDispatchService(backend, new ServerThreadRunner() {
            @Override
            public <T> T call(java.util.function.Supplier<T> task) {
                return task.get();
            }
        }, bus);
    }
}
