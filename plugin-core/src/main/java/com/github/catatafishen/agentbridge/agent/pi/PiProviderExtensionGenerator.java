package com.github.catatafishen.agentbridge.agent.pi;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates {@code .agent-work/pi/agentbridge-providers.ts} containing one
 * {@code pi.registerProvider()} call per entry in {@link PiCustomProvidersService}.
 *
 * <p>Pi loads the file via {@code --extension <path>} and reads each provider's API key
 * from the environment variable named in the entry; AgentBridge sets that env var on
 * the subprocess before launch ({@link #buildEnv(List)}).</p>
 */
public final class PiProviderExtensionGenerator {

    private static final Logger LOG = Logger.getInstance(PiProviderExtensionGenerator.class);

    private static final String TEMPLATE = "/pi/agentbridge-providers.ts.template";
    private static final String OUT_DIR = ".agent-work/pi";
    private static final String OUT_FILE = "agentbridge-providers.ts";

    private final Project project;

    public PiProviderExtensionGenerator(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Writes the providers extension if any custom providers are configured.
     *
     * @return path to the generated file, or {@code null} when no providers are
     * configured (caller should then skip passing {@code --extension}).
     */
    @Nullable
    public Path generate(@NotNull List<PiCustomProvidersService.Entry> providers) {
        if (providers.isEmpty()) return null;
        String basePath = project.getBasePath();
        if (basePath == null) {
            LOG.warn("[pi-providers] project has no base path; cannot generate provider extension");
            return null;
        }
        try {
            String template = loadTemplate();
            String json = buildProvidersJson(providers);
            String rendered = template.replace("${AGENTBRIDGE_PI_PROVIDERS_JSON}", json);
            Path outDir = Path.of(basePath, OUT_DIR);
            Files.createDirectories(outDir);
            Path out = outDir.resolve(OUT_FILE);
            Files.writeString(
                out,
                rendered,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            LOG.info("[pi-providers] wrote " + providers.size() + " provider(s) to " + out);
            return out;
        } catch (IOException e) {
            LOG.warn("[pi-providers] failed to generate extension", e);
            return null;
        }
    }

    /**
     * Builds the env-var map (apiKeyEnv → apiKeyValue) so the launcher can export
     * each provider's API key into Pi's process environment.
     */
    @NotNull
    public static Map<String, String> buildEnv(@NotNull List<PiCustomProvidersService.Entry> providers) {
        Map<String, String> env = new LinkedHashMap<>();
        for (PiCustomProvidersService.Entry p : providers) {
            if (p.validate() != null) continue;
            String key = p.effectiveApiKeyEnv();
            String val = p.apiKeyValue == null ? "" : p.apiKeyValue;
            if (!key.isEmpty() && !val.isEmpty()) env.put(key, val);
        }
        return env;
    }

    @NotNull
    private static String buildProvidersJson(@NotNull List<PiCustomProvidersService.Entry> providers) {
        Gson gson = new Gson();
        JsonArray arr = new JsonArray();
        for (PiCustomProvidersService.Entry p : providers) {
            if (p.validate() != null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id);
            o.addProperty("name", p.displayName != null && !p.displayName.isBlank() ? p.displayName : p.id);
            o.addProperty("baseUrl", p.baseUrl);
            o.addProperty("api", p.api == null || p.api.isBlank() ? "openai-completions" : p.api);
            String apiKeyEnv = p.effectiveApiKeyEnv();
            o.addProperty("apiKeyEnv", apiKeyEnv);
            o.addProperty("authHeader", p.authHeader);
            LOG.info("[pi-providers] model: " + p.id + " uses env: " + apiKeyEnv);
            JsonArray models = new JsonArray();
            JsonObject m = new JsonObject();
            m.addProperty("id", p.modelId);
            m.addProperty("name", p.modelName != null && !p.modelName.isBlank() ? p.modelName : p.modelId);
            m.addProperty("reasoning", p.supportsReasoning);
            m.addProperty("image", p.supportsImage);
            m.addProperty("contextWindow", p.contextWindow);
            m.addProperty("maxTokens", p.maxTokens);
            models.add(m);
            o.add("models", models);
            arr.add(o);
        }
        return gson.toJson(arr);
    }

    @NotNull
    private static String loadTemplate() throws IOException {
        try (InputStream in = PiProviderExtensionGenerator.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) throw new IOException("Bundled resource not found: " + TEMPLATE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
