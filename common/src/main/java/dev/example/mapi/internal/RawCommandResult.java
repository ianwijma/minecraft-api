package dev.example.mapi.internal;

import java.util.List;

/**
 * Raw, loader-supplied command execution outcome, captured on the server
 * thread (spec §6.2: feedback is routed through the command source during
 * the execution window; logs remain separate).
 *
 * @param result   the command result value from the callback, or {@code null}
 *                 when the command did not reach execution (parse/validation
 *                 errors surface via feedback instead)
 * @param success  callback success flag or {@code null}
 * @param feedback system messages produced through the capturing source
 */
public record RawCommandResult(Integer result, Boolean success, List<String> feedback) {
}
