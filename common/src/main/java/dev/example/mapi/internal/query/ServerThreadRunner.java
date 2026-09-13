package dev.example.mapi.internal.query;

/**
 * Runs a supplier on the Minecraft server thread with a bounded wait,
 * throwing {@code SERVER_BUSY} when the bounded wait elapses (the snapshot
 * pattern of docs/architecture.md, generalized).
 */
@FunctionalInterface
public interface ServerThreadRunner {

    /**
     * @param task the supplier to run on the server thread
     * @param <T>  result type
     * @return the supplier's result
     * @throws Exception on timeout, interruption, or when the task failed;
     *     implementations translate these to problem exceptions or runtime
     *     failures
     */
    <T> T call(java.util.function.Supplier<T> task) throws Exception;
}
