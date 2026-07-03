package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the applyAcpTitleIfPresent logic in McpProtocolHandler.
 * <p>
 * Mocks the static service lookups to isolate the method's decision logic:
 * it should call {@link LiveToolCallService#setDisplayName} only when a
 * ToolCallRecord with a non-null, different ACP title exists for
 * the given toolUseId.
 */
class McpProtocolHandlerApplyAcpTitleTest {

    @Mock
    private Project project;

    @Mock
    private ToolCallTracker tracker;

    @Mock
    private ToolCallRecord record;

    @Mock
    private LiveToolCallService liveService;

    private McpProtocolHandler handler;

    private static final long CALL_ID = 42L;
    private static final String TOOL_USE_ID = "call_abc123";
    private static final String DISPLAY_NAME = "read_file";
    private static final String ACP_TITLE = "Reading config for server settings";

    private Method applyAcpTitleMethod;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        handler = new McpProtocolHandler(project);
        applyAcpTitleMethod = McpProtocolHandler.class
            .getDeclaredMethod("applyAcpTitleIfPresent", long.class, String.class, String.class);
        applyAcpTitleMethod.setAccessible(true);
    }

    // ── When ACP has a better title → setDisplayName must be called ─────────

    @Test
    void applyAcpTitleIfPresent_updatesDisplayNameWhenAcpTitleExistsAndDiffers() throws Exception {
        when(record.getAcpTitle()).thenReturn(ACP_TITLE);
        when(tracker.findByToolUseId(TOOL_USE_ID)).thenReturn(record);

        try (MockedStatic<ToolCallTracker> trackerStatic = mockStatic(ToolCallTracker.class);
             MockedStatic<LiveToolCallService> liveStatic = mockStatic(LiveToolCallService.class)) {

            trackerStatic.when(() -> ToolCallTracker.getInstance(project)).thenReturn(tracker);
            liveStatic.when(() -> LiveToolCallService.getInstance(project)).thenReturn(liveService);

            applyAcpTitleMethod.invoke(handler, CALL_ID, TOOL_USE_ID, DISPLAY_NAME);

            verify(liveService).setDisplayName(CALL_ID, ACP_TITLE);
        }
    }

    // ── When ACP title is null → no-op ──────────────────────────────────────

    @Test
    void applyAcpTitleIfPresent_doesNothingWhenAcpTitleIsNull() throws Exception {
        when(record.getAcpTitle()).thenReturn(null);
        when(tracker.findByToolUseId(TOOL_USE_ID)).thenReturn(record);

        try (MockedStatic<ToolCallTracker> trackerStatic = mockStatic(ToolCallTracker.class);
             MockedStatic<LiveToolCallService> liveStatic = mockStatic(LiveToolCallService.class)) {

            trackerStatic.when(() -> ToolCallTracker.getInstance(project)).thenReturn(tracker);
            liveStatic.when(() -> LiveToolCallService.getInstance(project)).thenReturn(liveService);

            applyAcpTitleMethod.invoke(handler, CALL_ID, TOOL_USE_ID, DISPLAY_NAME);

            verify(liveService, never()).setDisplayName(anyLong(), anyString());
        }
    }

    // ── When ACP title equals displayName → no-op ───────────────────────────

    @Test
    void applyAcpTitleIfPresent_doesNothingWhenAcpTitleEqualsDisplayName() throws Exception {
        when(record.getAcpTitle()).thenReturn(DISPLAY_NAME);
        when(tracker.findByToolUseId(TOOL_USE_ID)).thenReturn(record);

        try (MockedStatic<ToolCallTracker> trackerStatic = mockStatic(ToolCallTracker.class);
             MockedStatic<LiveToolCallService> liveStatic = mockStatic(LiveToolCallService.class)) {

            trackerStatic.when(() -> ToolCallTracker.getInstance(project)).thenReturn(tracker);
            liveStatic.when(() -> LiveToolCallService.getInstance(project)).thenReturn(liveService);

            applyAcpTitleMethod.invoke(handler, CALL_ID, TOOL_USE_ID, DISPLAY_NAME);

            verify(liveService, never()).setDisplayName(anyLong(), anyString());
        }
    }

    // ── When toolUseId has no matching record → no-op ───────────────────────

    @Test
    void applyAcpTitleIfPresent_doesNothingWhenNoMatchingTrackerRecord() throws Exception {
        when(tracker.findByToolUseId(TOOL_USE_ID)).thenReturn(null);

        try (MockedStatic<ToolCallTracker> trackerStatic = mockStatic(ToolCallTracker.class);
             MockedStatic<LiveToolCallService> liveStatic = mockStatic(LiveToolCallService.class)) {

            trackerStatic.when(() -> ToolCallTracker.getInstance(project)).thenReturn(tracker);
            liveStatic.when(() -> LiveToolCallService.getInstance(project)).thenReturn(liveService);

            applyAcpTitleMethod.invoke(handler, CALL_ID, TOOL_USE_ID, DISPLAY_NAME);

            verify(liveService, never()).setDisplayName(anyLong(), anyString());
        }
    }

    // ── Verifies the exact toolUseId is used for lookup ─────────────────────

    @Test
    void applyAcpTitleIfPresent_looksUpByExactToolUseId() throws Exception {
        when(tracker.findByToolUseId(TOOL_USE_ID)).thenReturn(null);

        try (MockedStatic<ToolCallTracker> trackerStatic = mockStatic(ToolCallTracker.class);
             MockedStatic<LiveToolCallService> liveStatic = mockStatic(LiveToolCallService.class)) {

            trackerStatic.when(() -> ToolCallTracker.getInstance(project)).thenReturn(tracker);
            liveStatic.when(() -> LiveToolCallService.getInstance(project)).thenReturn(liveService);

            applyAcpTitleMethod.invoke(handler, CALL_ID, TOOL_USE_ID, DISPLAY_NAME);

            verify(tracker).findByToolUseId(TOOL_USE_ID);
        }
    }
}
