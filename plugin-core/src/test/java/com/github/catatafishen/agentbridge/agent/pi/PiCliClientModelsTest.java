package com.github.catatafishen.agentbridge.agent.pi;

import com.github.catatafishen.agentbridge.acp.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@code PiCliClient} surfaces Pi's real on-disk model catalog
 * ({@code models.json} / {@code models-store.json}) instead of returning an empty
 * list when no plugin-level custom providers are configured.
 */
class PiCliClientModelsTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesModelsJsonCustomProviders() throws IOException {
        Path file = tempDir.resolve("models.json");
        Files.writeString(file, """
            {
              "providers": {
                "9router": {
                  "baseUrl": "http://127.0.0.1:20128/v1",
                  "api": "openai-completions",
                  "models": [
                    {"id": "Hsx2Coder", "name": "9Router Hsx2Coder", "reasoning": true},
                    {"id": "OpenCode", "name": "9Router OpenCode"}
                  ]
                }
              }
            }
            """);

        List<Model> out = new ArrayList<>();
        PiCliClient.addModelsFromJson(file, out);

        assertEquals(2, out.size());
        assertEquals("Hsx2Coder", out.get(0).id());
        assertEquals("9Router Hsx2Coder", out.get(0).name());
        assertEquals("OpenCode", out.get(1).id());
        assertNotNull(out.get(0)._meta());
    }

    @Test
    void parsesModelsStoreJsonCatalog() throws IOException {
        Path file = tempDir.resolve("models-store.json");
        Files.writeString(file, """
            {
              "openrouter": {
                "checkedAt": 1,
                "models": [
                  {"id": "anthropic/claude-sonnet-4.5", "name": "Claude Sonnet 4.5", "contextWindow": 1000000},
                  {"id": "openai/gpt-5", "name": "GPT-5"}
                ]
              },
              "google-vertex": {
                "models": [
                  {"id": "gemini-2.5-pro", "name": "Gemini 2.5 Pro"}
                ]
              }
            }
            """);

        List<Model> out = new ArrayList<>();
        PiCliClient.addModelsFromJson(file, out);

        assertEquals(3, out.size());
        assertEquals("anthropic/claude-sonnet-4.5", out.get(0).id());
        assertEquals("gemini-2.5-pro", out.get(2).id());
    }

    @Test
    void ignoresMissingOrMalformedFiles() throws IOException {
        Path missing = tempDir.resolve("does-not-exist.json");
        List<Model> out = new ArrayList<>();
        PiCliClient.addModelsFromJson(missing, out);
        assertTrue(out.isEmpty());

        Path malformed = tempDir.resolve("malformed.json");
        Files.writeString(malformed, "not json at all");
        PiCliClient.addModelsFromJson(malformed, out);
        assertTrue(out.isEmpty());
    }
}
