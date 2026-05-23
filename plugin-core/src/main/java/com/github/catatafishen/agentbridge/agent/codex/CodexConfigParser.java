package com.github.catatafishen.agentbridge.agent.codex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.SystemProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Codex configuration from {@code ~/.codex/config.toml} and {@code ~/.codex/auth.json}.
 */
public final class CodexConfigParser {
    private static final Logger LOG = Logger.getInstance(CodexConfigParser.class);

    private static final Pattern SECTION_PATTERN = Pattern.compile("^\\[(.+)]$");
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("^\\s*([a-zA-Z0-9_-]+)\\s*=\\s*[\"'](.+)[\"']\\s*$");

    public static class ConfigResult {
        @NotNull
        private final Set<String> customModels = new LinkedHashSet<>();
        @Nullable
        private String apiKey;

        @NotNull
        public Set<String> getCustomModels() {
            return customModels;
        }

        @Nullable
        public String getApiKey() {
            return apiKey;
        }
    }

    /**
     * Parses the custom models and auth info from {@code ~/.codex/}.
     */
    @NotNull
    public static ConfigResult parse() {
        ConfigResult result = new ConfigResult();
        String userHome = SystemProperties.getUserHome();
        Path codexDir = Path.of(userHome, ".codex");

        // 1. Parse config.toml
        File configFile = codexDir.resolve("config.toml").toFile();
        if (configFile.exists() && configFile.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(configFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                        continue;
                    }

                    Matcher sectionMatcher = SECTION_PATTERN.matcher(line);
                    if (sectionMatcher.matches()) {
                        continue;
                    }

                    Matcher kvMatcher = KEY_VALUE_PATTERN.matcher(line);
                    if (kvMatcher.matches()) {
                        String key = kvMatcher.group(1).trim();
                        String val = kvMatcher.group(2).trim();

                        if ("model".equals(key)) {
                            result.customModels.add(val);
                        }
                    }
                }
            } catch (IOException e) {
                LOG.warn("Failed to parse ~/.codex/config.toml", e);
            }
        }

        // 2. Parse auth.json
        File authFile = codexDir.resolve("auth.json").toFile();
        if (authFile.exists() && authFile.isFile()) {
            try {
                String content = Files.readString(authFile.toPath(), StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                if (json.has("OPENAI_API_KEY")) {
                    result.apiKey = json.get("OPENAI_API_KEY").getAsString();
                }
            } catch (Exception e) {
                LOG.warn("Failed to parse ~/.codex/auth.json", e);
            }
        }

        return result;
    }
}
