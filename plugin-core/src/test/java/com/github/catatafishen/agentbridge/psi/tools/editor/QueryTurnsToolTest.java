package com.github.catatafishen.agentbridge.psi.tools.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryTurnsToolTest {

    @Test
    void normalizeRetrievalSelection_defaultsToLastFiveWhenNoSelectorsProvided() {
        QueryTurnsTool.RetrievalSelection selection = QueryTurnsTool.normalizeRetrievalSelection(null, null, null, null);

        assertNull(selection.turnId());
        assertNull(selection.sessionId());
        assertEquals(5, selection.lastN());
        assertNull(selection.offset());
    }

    @Test
    void normalizeRetrievalSelection_prefersTurnIdOverInjectedLastN() {
        QueryTurnsTool.RetrievalSelection selection =
            QueryTurnsTool.normalizeRetrievalSelection("turn-123", null, 5, 20);

        assertEquals("turn-123", selection.turnId());
        assertNull(selection.sessionId());
        assertNull(selection.lastN());
        assertNull(selection.offset());
    }

    @Test
    void normalizeRetrievalSelection_prefersSessionIdOverInjectedLastN() {
        QueryTurnsTool.RetrievalSelection selection =
            QueryTurnsTool.normalizeRetrievalSelection(null, "session-123", 5, 20);

        assertNull(selection.turnId());
        assertEquals("session-123", selection.sessionId());
        assertNull(selection.lastN());
        assertNull(selection.offset());
    }

    @Test
    void normalizeRetrievalSelection_rejectsTurnIdAndSessionIdTogether() {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> QueryTurnsTool.normalizeRetrievalSelection("turn-123", "session-123", null, null)
        );

        assertEquals(
            "Only one of turn_id or session_id may be used at a time. Pick the one that matches your intent.",
            error.getMessage()
        );
    }

    @Test
    void normalizeRetrievalSelection_treatsBlankAndNonPositiveValuesAsUnset() {
        QueryTurnsTool.RetrievalSelection selection =
            QueryTurnsTool.normalizeRetrievalSelection("   ", "", 0, -5);

        assertNull(selection.turnId());
        assertNull(selection.sessionId());
        assertEquals(5, selection.lastN());
        assertNull(selection.offset());
    }
}
