package io.hivekeeper.core.transport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the shell-session text heuristics. The pager handling is the important one: a large paged read
 * ({@code show log buffered}) used to spam pager acks and time out because the whole buffer was scanned for
 * "--More--"; we now only answer when it sits at the tail.
 */
class SshjShellSessionTest {

    @Test
    void endsWithMore_true_only_when_pager_prompt_is_at_the_tail() {
        assertTrue(SshjShellSession.endsWithMore("line1\nline2\n--More--"));
        assertTrue(SshjShellSession.endsWithMore("line1\n--More-- "));          // trailing space/pad
        assertTrue(SshjShellSession.endsWithMore("page of log\r\n--More--\r"));  // trailing CR
    }

    @Test
    void endsWithMore_false_when_more_already_scrolled_into_the_backlog() {
        // The regression: a "--More--" answered earlier is now in the middle of the buffer. Re-acking it is the
        // bug that made large reads never settle.
        assertFalse(SshjShellSession.endsWithMore("--More--\nnext page line 1\nnext page line 2\nmore log here"));
        assertFalse(SshjShellSession.endsWithMore(""));
        assertFalse(SshjShellSession.endsWithMore(null));
    }

    @Test
    void looksLikePrompt_matches_the_device_prompt_not_log_lines() {
        assertTrue(SshjShellSession.looksLikePrompt("banner\nAH-827200#"));
        assertTrue(SshjShellSession.looksLikePrompt("AH-827200>"));
        assertFalse(SshjShellSession.looksLikePrompt("2026-06-15 19:57:33 info ah_cli: logged in"));
        assertFalse(SshjShellSession.looksLikePrompt("")); // nothing yet
    }
}
