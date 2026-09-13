/**
 * Public Java API of MAPI (mod id {@code mapi}, display name "Minecraft API").
 *
 * <h2>Stability</h2>
 *
 * Everything in {@code dev.example.mapi.api} is the documented public surface of
 * MAPI. Everything in {@code dev.example.mapi.internal} (and in loader modules)
 * is internal and may change or be removed at any time without notice.
 *
 * <p>Until MAPI reaches 1.0.0 the whole public API is considered experimental:
 * minor versions may add functionality and make source-incompatible changes.
 * See {@code docs/api.md} for the exact versioning and deprecation policy.
 *
 * <h2>Threading</h2>
 *
 * All public methods are safe to call from any thread unless documented
 * otherwise. {@link Mapi#serverStatus()} never accesses live server state
 * directly; it schedules a bounded snapshot onto the server thread.
 */
package dev.example.mapi.api;
