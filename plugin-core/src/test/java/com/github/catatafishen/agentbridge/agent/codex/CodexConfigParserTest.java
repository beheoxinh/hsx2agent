package com.github.catatafishen.agentbridge.agent.codex;

import com.intellij.util.SystemProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexConfigParserTest {

    private Path backupConfig;
    private Path backupAuth;
    private Path codexDir;

    @BeforeEach
    void setUp() throws IOException {
        String userHome = SystemProperties.getUserHome();
        codexDir = Path.of(userHome, ".codex");
        if (!Files.exists(codexDir)) {
            Files.createDirectories(codexDir);
        }

        Path configPath = codexDir.resolve("config.toml");
        if (Files.exists(configPath)) {
            backupConfig = Files.createTempFile("config_backup", ".toml");
            Files.copy(configPath, backupConfig, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        Path authPath = codexDir.resolve("auth.json");
        if (Files.exists(authPath)) {
            backupAuth = Files.createTempFile("auth_backup", ".json");
            Files.copy(authPath, backupAuth, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        Path configPath = codexDir.resolve("config.toml");
        Files.deleteIfExists(configPath);
        if (backupConfig != null) {
            Files.copy(backupConfig, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(backupConfig);
        }

        Path authPath = codexDir.resolve("auth.json");
        Files.deleteIfExists(authPath);
        if (backupAuth != null) {
            Files.copy(backupAuth, authPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(backupAuth);
        }
    }

    @Test
    void testParseConfigAndAuth() throws IOException {
        String configContent = """
            # 9Router Configuration for Codex CLI
            model = "vx/gemini-3.1-flash-lite"
            model_provider = "9router"

            [model_providers.9router]
            name = "9Router"
            base_url = "http://127.0.0.1:20128/v1"
            wire_api = "responses"

            [agents.subagent]
            model = "vx/gemini-3.5-flash"
            """;

        String authContent = """
            {
              "auth_mode": "apikey",
              "OPENAI_API_KEY": "sk-4527ebaf3255279e-xdlolk-09621176"
            }""";

        Files.writeString(codexDir.resolve("config.toml"), configContent, StandardCharsets.UTF_8);
        Files.writeString(codexDir.resolve("auth.json"), authContent, StandardCharsets.UTF_8);

        CodexConfigParser.ConfigResult result = CodexConfigParser.parse();
        assertNotNull(result);
        assertEquals("sk-4527ebaf3255279e-xdlolk-09621176", result.getApiKey());
        assertTrue(result.getCustomModels().contains("vx/gemini-3.1-flash-lite"));
        assertTrue(result.getCustomModels().contains("vx/gemini-3.5-flash"));
    }
}
