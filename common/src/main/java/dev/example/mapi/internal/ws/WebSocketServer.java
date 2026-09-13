package dev.example.mapi.internal.ws;

import dev.example.mapi.internal.http.TicketStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * Minimal RFC 6455 WebSocket server on its own loopback port (the JDK's
 * {@code com.sun.net.httpserver} cannot host protocol upgrades, so events
 * get a dedicated listener; the port is advertised via discovery and
 * {@code /api/v1/info}).
 *
 * <p>Handshake security: loopback bind, Host check, Authorization header or
 * single-use ticket query parameter, and Origin rejection when present and
 * not the local listener (spec §4.5).
 */
public final class WebSocketServer {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_CONNECTIONS = 32;
    private static final int MAX_REQUEST_LINE = 8192;

    private final TicketStore tickets;
    private final java.util.function.Predicate<String> authorize;
    private final WebSocketConnection.Listener listener;
    private final Logger logger;
    private final AtomicInteger connections = new AtomicInteger();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    /**
     * @param tickets   single-use ticket store for browser-style connects
     * @param authorize constant-time token validator
     * @param listener  connection behavior
     * @param logger    platform logger
     */
    public WebSocketServer(TicketStore tickets, java.util.function.Predicate<String> authorize,
            WebSocketConnection.Listener listener, Logger logger) {
        this.tickets = tickets;
        this.authorize = authorize;
        this.listener = listener;
        this.logger = logger;
    }

    /**
     * Binds the listener to the IPv4 loopback. Port 0 lets the OS choose.
     *
     * @param port requested port
     * @return true when listening
     */
    public synchronized boolean start(int port) {
        try {
            serverSocket = new ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            logger.error("MAPI WS: failed to bind 127.0.0.1:{} ({})", port, e.toString());
            return false;
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "mapi-ws-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        logger.info("MAPI WS: event stream listening on ws://127.0.0.1:{} (loopback only)",
                serverSocket.getLocalPort());
        return true;
    }

    /**
     * @return the bound port, or -1 when not running
     */
    public synchronized int boundPort() {
        return serverSocket != null && !serverSocket.isClosed() ? serverSocket.getLocalPort() : -1;
    }

    /**
     * Stops the listener. Existing connections finish their close handshake.
     */
    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
            serverSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        logger.info("MAPI WS: event stream stopped");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                    connections.decrementAndGet();
                    socket.close();
                    continue;
                }
                Thread thread = new Thread(() -> {
                    try {
                        handleConnection(socket);
                    } finally {
                        connections.decrementAndGet();
                    }
                }, "mapi-ws-conn");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                if (running) {
                    logger.warn("MAPI WS: accept failed: {}", e.toString());
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(120_000);
            Handshake handshake = readHandshake(socket);
            if (handshake == null) {
                return;
            }
            String accept = Base64.getEncoder().encodeToString(sha1((handshake.headers()
                    .get("sec-websocket-key").trim() + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            WebSocketConnection connection = new WebSocketConnection(socket.getInputStream(), out, listener,
                    logger);
            listener.onOpen(connection);
            Thread pump = new Thread(connection::runPump, "mapi-ws-pump");
            pump.setDaemon(true);
            pump.start();
            connection.runReader();
            pump.join(2000);
        } catch (IOException e) {
            logger.debug("MAPI WS: connection ended: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    /**
     * Reads and validates the upgrade request. Rejections are answered with
     * plain HTTP error responses.
     */
    private Handshake readHandshake(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String requestLine = reader.readLine();
        if (requestLine == null || !requestLine.startsWith("GET ")
                || requestLine.length() > MAX_REQUEST_LINE) {
            reject(socket, 400, "Bad Request");
            return null;
        }
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.length() > MAX_REQUEST_LINE) {
                reject(socket, 400, "Bad Request");
                return null;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        String host = headers.get("host");
        if (host == null || !(host.startsWith("127.0.0.1") || host.startsWith("localhost") || host.startsWith("[::1]"))) {
            reject(socket, 403, "Forbidden Host");
            return null;
        }
        if (!"websocket".equalsIgnoreCase(headers.get("upgrade"))
                || headers.get("sec-websocket-key") == null
                || headers.get("sec-websocket-key").isBlank()
                || !"13".equals(headers.get("sec-websocket-version"))) {
            reject(socket, 400, "Expected WebSocket upgrade");
            return null;
        }
        String origin = headers.get("origin");
        if (origin != null && !origin.startsWith("http://localhost") && !origin.startsWith("http://127.0.0.1")) {
            reject(socket, 403, "Forbidden Origin");
            return null;
        }
        String query = requestLine.split(" ")[1];
        String authorization = headers.get("authorization");
        boolean authorized = false;
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            authorized = authorize.test(authorization.substring(7).trim());
        }
        if (!authorized) {
            String ticket = queryParam(query, "ticket");
            if (ticket == null || !tickets.consume(ticket)) {
                reject(socket, 401, "Unauthorized");
                return null;
            }
        }
        return new Handshake(query, headers);
    }

    private static String queryParam(String rawPath, String name) {
        int queryStart = rawPath.indexOf('?');
        if (queryStart < 0) {
            return null;
        }
        for (String pair : rawPath.substring(queryStart + 1).split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                return eq < 0 ? "" : pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static void reject(Socket socket, int status, String reason) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException ignored) {
            // Client gone.
        }
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the JDK", e);
        }
    }

    /**
     * Parsed and authorized upgrade request.
     *
     * @param path    request path with query
     * @param headers lowercase header map
     */
    private record Handshake(String path, Map<String, String> headers) {
    }
}
