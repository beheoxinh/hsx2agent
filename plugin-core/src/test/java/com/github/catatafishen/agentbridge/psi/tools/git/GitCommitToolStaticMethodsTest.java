package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for static helper methods in {@link GitCommitTool}.
 */
@DisplayName("GitCommitTool static methods")
class GitCommitToolStaticMethodsTest {

    @Nested
    @DisplayName("resolveAmend")
    class ResolveAmend {

        @Test
        @DisplayName("defaults to false when 'amend' param is absent")
        void defaultsToFalse() {
            assertFalse(GitCommitTool.resolveAmend(new JsonObject()));
        }

        @Test
        @DisplayName("returns true when 'amend' is explicitly true")
        void explicitlyTrue() {
            JsonObject args = new JsonObject();
            args.addProperty("amend", true);
            assertTrue(GitCommitTool.resolveAmend(args));
        }

        @Test
        @DisplayName("returns false when 'amend' is explicitly false")
        void explicitlyFalse() {
            JsonObject args = new JsonObject();
            args.addProperty("amend", false);
            assertFalse(GitCommitTool.resolveAmend(args));
        }

        @Test
        @DisplayName("other args don't affect amend result")
        void otherArgsIgnored() {
            JsonObject args = new JsonObject();
            args.addProperty("message", "test commit");
            args.addProperty("all", true);
            assertFalse(GitCommitTool.resolveAmend(args));
        }
    }

    @Nested
    @DisplayName("resolveCommitAll")
    class ResolveCommitAll {

        @Test
        @DisplayName("defaults to true when 'all' param is absent")
        void defaultsToTrue() {
            assertTrue(GitCommitTool.resolveCommitAll(new JsonObject()));
        }

        @Test
        @DisplayName("returns true when 'all' is explicitly true")
        void explicitlyTrue() {
            JsonObject args = new JsonObject();
            args.addProperty("all", true);
            assertTrue(GitCommitTool.resolveCommitAll(args));
        }

        @Test
        @DisplayName("returns false when 'all' is explicitly false")
        void explicitlyFalse() {
            JsonObject args = new JsonObject();
            args.addProperty("all", false);
            assertFalse(GitCommitTool.resolveCommitAll(args));
        }

        @Test
        @DisplayName("other args don't affect result")
        void otherArgsIgnored() {
            JsonObject args = new JsonObject();
            args.addProperty("message", "test commit");
            args.addProperty("amend", true);
            assertTrue(GitCommitTool.resolveCommitAll(args));
        }
    }

    @Nested
    @DisplayName("interpretPromptUserResponse")
    class InterpretPromptUserResponse {

        @Test
        @DisplayName("returns null for explicit approval")
        void explicitApproval() {
            assertNull(GitCommitTool.interpretPromptUserResponse("Yes, commit code"));
        }

        @Test
        @DisplayName("bypasses ask-for-commit when Settings modal blocks prompt")
        void bypassesSettingsModalError() {
            assertNull(GitCommitTool.interpretPromptUserResponse(
                "Error: EDT blocked by modal dialog. Modal dialog blocking: 'Settings' Use the interact_with_modal tool to respond to the dialog."
            ));
        }

        @Test
        @DisplayName("returns timeout message for timed out prompt")
        void timeout() {
            assertEquals(
                "Commit skipped: user response timed out",
                GitCommitTool.interpretPromptUserResponse("request timed out")
            );
        }

        @Test
        @DisplayName("returns cancellation for other prompt errors")
        void otherPromptErrorsCancel() {
            assertEquals(
                "Commit cancelled: Error: Failed to invoke ask-user panel API",
                GitCommitTool.interpretPromptUserResponse("Error: Failed to invoke ask-user panel API")
            );
        }

        @Test
        @DisplayName("returns cancellation for explicit user rejection")
        void userRejectionCancels() {
            assertEquals(
                "Commit cancelled by user. Response: No, cancel",
                GitCommitTool.interpretPromptUserResponse("No, cancel")
            );
        }
    }
}
