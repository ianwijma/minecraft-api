package dev.example.mapi.internal.connection;

import dev.example.mapi.internal.operation.Scope;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Carries API authorization into vanilla client connections and checks the
 * requested name and each final numeric resolver destination. Manual joins do
 * not have a context and retain vanilla behavior.
 */
public final class ConnectionPolicy {

    private static final ThreadLocal<RequestContext> REQUEST_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<ResolutionSession> RESOLUTION_SESSION = new ThreadLocal<>();
    private static final Map<Object, RequestContext> CONNECTION_CONTEXTS = new WeakHashMap<>();
    private static final Map<Object, RequestContext> SESSION_CONTEXTS = new WeakHashMap<>();

    private ConnectionPolicy() {}

    /** Immutable authorization and allowlist snapshot propagated with API work. */
    public record RequestContext(Set<Scope> grants, List<String> allowlist, Runnable requireControl) {
        public RequestContext {
            grants = Set.copyOf(Objects.requireNonNull(grants, "grants"));
            allowlist = List.copyOf(Objects.requireNonNull(allowlist, "allowlist"));
            requireControl = requireControl == null ? () -> {} : requireControl;
        }

        public RequestContext(Set<Scope> grants, List<String> allowlist) {
            this(grants, allowlist, () -> {});
        }
    }

    /** Captures API connection authorization for propagation into queued client work. */
    public static RequestContext captureContext(Set<Scope> grants, List<String> allowlist) {
        return new RequestContext(grants, allowlist);
    }

    /** Captures request authorization and the lease check to repeat at each connection boundary. */
    public static RequestContext captureContext(Set<Scope> grants, List<String> allowlist,
            Runnable requireControl) {
        return new RequestContext(grants, allowlist, requireControl);
    }

    /**
     * Returns the authorization context active on this thread, or {@code null}
     * when work is a manual Minecraft action without API ownership.
     */
    public static RequestContext currentContext() {
        return REQUEST_CONTEXT.get();
    }

    /** Runs an API action with its connection authorization installed on the current thread. */
    public static void withApiControl(Set<Scope> grants, List<String> allowlist, Runnable action) {
        withContext(captureContext(grants, allowlist), action);
    }

    /** Runs an API action with its connection authorization installed on the current thread. */
    public static <T> T withApiControl(Set<Scope> grants, List<String> allowlist, Supplier<T> action) {
        return withContext(captureContext(grants, allowlist), action);
    }

    /** Runs a task under a previously captured API request context. */
    public static void withContext(RequestContext context, Runnable action) {
        Objects.requireNonNull(action, "action");
        RequestContext previous = REQUEST_CONTEXT.get();
        install(context);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    /** Runs a task under a previously captured API request context. */
    public static <T> T withContext(RequestContext context, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        RequestContext previous = REQUEST_CONTEXT.get();
        install(context);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /** Wraps a callback so queued API work reinstalls its captured authorization context. */
    public static Runnable wrap(RequestContext context, Runnable action) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(action, "action");
        return () -> withContext(context, action);
    }

    /**
     * Captures API ownership for one vanilla connection. Called at the start
     * of ConnectScreen's internal connection method. Returns false for
     * ordinary manual connections.
     */
    public static boolean captureConnect(Object connectionOwner, String host, int port) {
        return captureConnect(connectionOwner, null, host, port, false);
    }

    /** Captures or inherits policy for a ConnectScreen belonging to this Minecraft session. */
    public static boolean captureConnect(Object connectionOwner, Object sessionOwner,
            String host, int port, boolean serverTransfer) {
        Objects.requireNonNull(connectionOwner, "connectionOwner");
        RequestContext context = REQUEST_CONTEXT.get();
        if (context == null && serverTransfer && sessionOwner != null) {
            synchronized (SESSION_CONTEXTS) {
                context = SESSION_CONTEXTS.get(sessionOwner);
            }
        } else if (context == null && !serverTransfer && sessionOwner != null) {
            synchronized (SESSION_CONTEXTS) {
                SESSION_CONTEXTS.remove(sessionOwner);
            }
        }
        if (context == null) {
            synchronized (CONNECTION_CONTEXTS) {
                CONNECTION_CONTEXTS.remove(connectionOwner);
            }
            return false;
        }
        requireConnectScope(context);
        context.requireControl().run();
        if (!matchesRequestedTarget(host, port, context.allowlist())) {
            throw new ProblemException(ProblemCode.INSUFFICIENT_SCOPE,
                    "requested connection target is not allowed",
                    Map.of("host", normalizeHost(host), "port", port));
        }
        synchronized (CONNECTION_CONTEXTS) {
            CONNECTION_CONTEXTS.put(connectionOwner, context);
        }
        if (sessionOwner != null) {
            synchronized (SESSION_CONTEXTS) {
                SESSION_CONTEXTS.put(sessionOwner, context);
            }
        }
        return true;
    }

    /** Runs vanilla's resolver under the context captured for its ConnectScreen. */
    public static <T> T withConnectionResolver(Object connectionOwner, Supplier<T> resolver) {
        Objects.requireNonNull(connectionOwner, "connectionOwner");
        Objects.requireNonNull(resolver, "resolver");
        RequestContext context;
        synchronized (CONNECTION_CONTEXTS) {
            context = CONNECTION_CONTEXTS.remove(connectionOwner);
        }
        if (context == null) {
            return resolver.get();
        }
        RequestContext previousRequest = REQUEST_CONTEXT.get();
        ResolutionSession previousSession = RESOLUTION_SESSION.get();
        install(context);
        ResolutionSession session = new ResolutionSession();
        RESOLUTION_SESSION.set(session);
        try {
            T result = resolver.get();
            return result;
        } finally {
            restore(previousRequest);
            restoreSession(previousSession);
        }
    }

    /** Checks a hostname and port before vanilla asks its resolver to resolve them. */
    public static void assertResolverAddress(String host, int port) {
        ResolutionSession session = RESOLUTION_SESSION.get();
        if (session == null) {
            return;
        }
        RequestContext context = REQUEST_CONTEXT.get();
        if (context == null || !matchesRequestedTarget(host, port, context.allowlist())) {
            throw new IllegalStateException("MAPI connection policy rejected resolver target "
                    + normalizeHost(host) + ":" + port);
        }
        context.requireControl().run();
        session.lastHost = normalizeHost(host);
        session.lastPort = port;
    }

    /** Checks the final resolved numeric address against an explicit IP:port allowlist entry. */
    public static void verifyResolvedDestination(String ip, int port) {
        ResolutionSession session = RESOLUTION_SESSION.get();
        if (session == null) {
            return;
        }
        RequestContext context = REQUEST_CONTEXT.get();
        String normalizedIp = normalizeIp(ip);
        if (context == null || session.lastHost == null || session.lastPort != port
                || !hasExactIpPort(normalizedIp, port, context.allowlist())) {
            throw new IllegalStateException("MAPI connection policy rejected resolved destination "
                    + normalizedIp + ":" + port);
        }
        context.requireControl().run();
    }

    /** Checks an API-requested target against exact hostname/IP allowlist entries. */
    public static boolean matchesRequestedTarget(String host, int port, List<String> allowlist) {
        if (host == null || port < 1 || port > 65535 || allowlist == null) {
            return false;
        }
        String normalizedHost = normalizeHost(host);
        if (normalizedHost.isEmpty()) {
            return false;
        }
        for (String entry : allowlist) {
            Optional<AllowEntry> parsed = parseEntry(entry);
            if (parsed.isPresent() && parsed.get().host().equals(normalizedHost)
                    && (parsed.get().port() == null || parsed.get().port() == port)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExactIpPort(String ip, int port, List<String> allowlist) {
        for (String entry : allowlist) {
            Optional<AllowEntry> parsed = parseEntry(entry);
            if (parsed.isPresent() && parsed.get().port() != null
                    && parsed.get().port() == port && isIpLiteral(parsed.get().host())
                    && parsed.get().host().equals(ip)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<AllowEntry> parseEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return Optional.empty();
        }
        String rawHost = entry;
        Integer port = null;
        if (entry.startsWith("[")) {
            int end = entry.indexOf(']');
            if (end < 0) return Optional.empty();
            rawHost = entry.substring(1, end);
            String suffix = entry.substring(end + 1);
            if (!suffix.isEmpty()) {
                if (!suffix.startsWith(":")) return Optional.empty();
                port = parsePort(suffix.substring(1));
                if (port == null) return Optional.empty();
            }
        } else {
            int colon = entry.lastIndexOf(':');
            if (colon >= 0) {
                if (entry.indexOf(':') != colon) return Optional.empty();
                rawHost = entry.substring(0, colon);
                port = parsePort(entry.substring(colon + 1));
                if (port == null) return Optional.empty();
            }
        }
        String host = normalizeHost(rawHost);
        if (host.isEmpty()) return Optional.empty();
        return Optional.of(new AllowEntry(host, port));
    }

    private static Integer parsePort(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 1 && parsed <= 65535 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) return "";
        String candidate = host.strip();
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) {
            String normalizedIp = normalizeIp(candidate);
            return normalizedIp == null ? "" : normalizedIp;
        }
        if (isIpLiteral(candidate)) return normalizeIp(candidate);
        try {
            String ascii = java.net.IDN.toASCII(candidate).toLowerCase(java.util.Locale.ROOT);
            return ascii.endsWith(".") ? ascii.substring(0, ascii.length() - 1) : ascii;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean isIpLiteral(String host) {
        if (host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) {
            return normalizeIp(host) != null;
        }
        return host.indexOf(':') >= 0 && host.matches("[0-9A-Fa-f:.]+")
                && normalizeIp(host) != null;
    }

    private static String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        if (ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) {
            String[] octets = ip.split("\\.");
            List<String> normalized = new ArrayList<>(4);
            for (String octet : octets) {
                int value;
                try {
                    value = Integer.parseInt(octet);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (value > 255) return null;
                normalized.add(Integer.toString(value));
            }
            return String.join(".", normalized);
        }
        if (ip.indexOf(':') < 0 || !ip.matches("[0-9A-Fa-f:.]+")) return null;
        try {
            return InetAddress.getByName(ip).getHostAddress().toLowerCase(java.util.Locale.ROOT);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static void requireConnectScope(RequestContext context) {
        if (!context.grants().contains(Scope.CLIENT_CONNECT)) {
            throw new ProblemException(ProblemCode.INSUFFICIENT_SCOPE,
                    "missing required scope for API-driven connection",
                    Map.of("missing", List.of(Scope.CLIENT_CONNECT.wireName())));
        }
    }

    private static void install(RequestContext context) {
        if (context == null) REQUEST_CONTEXT.remove();
        else REQUEST_CONTEXT.set(context);
    }

    private static void restore(RequestContext previous) {
        install(previous);
    }

    private static void restoreSession(ResolutionSession previous) {
        if (previous == null) RESOLUTION_SESSION.remove();
        else RESOLUTION_SESSION.set(previous);
    }

    private record AllowEntry(String host, Integer port) {}

    private static final class ResolutionSession {
        private String lastHost;
        private int lastPort;
    }
}
