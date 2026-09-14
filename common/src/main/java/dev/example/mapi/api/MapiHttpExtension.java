package dev.example.mapi.api;

import java.util.Map;

/**
 * An HTTP extension surface for other mods (spec §6.2 ext SPI): registered
 * like any {@link MapiService}, it exposes documented operations under
 * {@code /api/v1/ext/<id>/…} on the local HTTP API.
 *
 * <p>Handlers run on the HTTP worker thread — implementations must be
 * thread-safe and must schedule any game-state work onto the owning thread
 * themselves (for example via the server thread through their own hooks).
 * Extensions add no authority: requests are already authenticated and
 * checked against {@link #requiredScope()}; handlers must never return
 * secrets (tokens, token files, environment variables).
 */
public interface MapiHttpExtension extends MapiService {

    /**
     * Scope required to invoke this extension (spec §4.2 vocabulary:
     * {@code observe}, {@code world.read}, {@code world.write},
     * {@code commands.execute}, {@code client.control}, …). Choose the
     * scope matching the <strong>effect</strong> of the exposed operations.
     *
     * @return the scope name, never {@code null}
     */
    String requiredScope();

    /**
     * Self-describing schema of the exposed operations (spec §6.2), returned
     * by {@code GET /api/v1/ext/<id>/$schema}. Values must be
     * JSON-serializable (see {@code docs/api.md}).
     *
     * @return the schema payload, never {@code null}
     */
    Map<String, Object> schema();

    /**
     * Handles one request. Exceptions are logged and answered with 500.
     *
     * @param request method (GET/POST), path relative to
     *                {@code /api/v1/ext/<id>/} (may be empty), decoded query
     *                parameters, and the parsed JSON body for POST requests
     *                (empty map otherwise)
     * @return the response; statuses 200–499 pass through, other statuses
     *         are replaced by 500
     */
    MapiHttpResponse handle(MapiHttpRequest request);

    /**
     * Extension request.
     *
     * @param method HTTP method (GET or POST)
     * @param path   path relative to the extension root, never
     *               {@code null} (may be empty)
     * @param query  decoded query parameters, never {@code null}
     * @param body   parsed JSON body for POST (empty map otherwise), never
     *               {@code null}
     */
    record MapiHttpRequest(String method, String path, Map<String, String> query,
            Map<String, Object> body) {
    }

    /**
     * Extension response.
     *
     * @param status HTTP status (200–499 pass through)
     * @param body   JSON-serializable payload, never {@code null}
     */
    record MapiHttpResponse(int status, Map<String, Object> body) {
    }
}
