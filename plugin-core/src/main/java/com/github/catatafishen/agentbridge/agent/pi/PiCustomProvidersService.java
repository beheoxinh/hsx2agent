package com.github.catatafishen.agentbridge.agent.pi;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Application-level store of custom Pi providers (LLM endpoints registered via
 * {@code pi.registerProvider()} inside a generated TypeScript extension).
 *
 * <p>Each entry produces one provider in the {@code agentbridge-providers.ts} file
 * written to {@code .agent-work/pi/} on every Pi launch. The {@link Entry#apiKeyValue}
 * is exported as an environment variable named {@link Entry#apiKeyEnv} so Pi reads it
 * via the env-var lookup convention recommended in {@code docs/custom-provider.md}.</p>
 *
 * <p>Storage: shared XML at {@code piCustomProviders.xml}. Persisted at the IDE level
 * because providers are usually global, not per-project.</p>
 */
@Service(Service.Level.APP)
@State(name = "PiCustomProviders", storages = @Storage("piCustomProviders.xml"))
public final class PiCustomProvidersService implements PersistentStateComponent<PiCustomProvidersService.State> {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9._-]+");

    /**
     * Persisted entry (mutable; XmlSerializer needs a no-arg ctor and public fields).
     */
    public static final class Entry {
        public String id = ""; // NOSONAR java:S1104
        public String displayName = ""; // NOSONAR java:S1104
        public String baseUrl = ""; // NOSONAR java:S1104
        public String api = "openai-completions"; // NOSONAR java:S1104
        public String apiKeyEnv = ""; // NOSONAR java:S1104
        public String apiKeyValue = ""; // NOSONAR java:S1104
        public String modelId = ""; // NOSONAR java:S1104
        public String modelName = ""; // NOSONAR java:S1104
        public int contextWindow = 128_000; // NOSONAR java:S1104
        public int maxTokens = 4096; // NOSONAR java:S1104
        public boolean supportsImage; // NOSONAR java:S1104
        public boolean supportsReasoning; // NOSONAR java:S1104
        public boolean authHeader; // NOSONAR java:S1104

        public Entry() {
            // for XmlSerializer
        }

        public Entry copy() {
            Entry c = new Entry();
            c.id = id;
            c.displayName = displayName;
            c.baseUrl = baseUrl;
            c.api = api;
            c.apiKeyEnv = apiKeyEnv;
            c.apiKeyValue = apiKeyValue;
            c.modelId = modelId;
            c.modelName = modelName;
            c.contextWindow = contextWindow;
            c.maxTokens = maxTokens;
            c.supportsImage = supportsImage;
            c.supportsReasoning = supportsReasoning;
            c.authHeader = authHeader;
            return c;
        }

        /**
         * Validates the entry. Returns {@code null} when valid, or a human-readable error.
         */
        @Nullable
        public String validate() {
            if (id == null || id.isBlank()) return "Provider ID is required";
            if (!ID_PATTERN.matcher(id).matches()) {
                return "Provider ID must contain only letters, digits, '-', '_', or '.'";
            }
            if (baseUrl == null || baseUrl.isBlank()) return "Base URL is required";
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                return "Base URL must start with http:// or https://";
            }
            if (modelId == null || modelId.isBlank()) return "Model ID is required";
            if (apiKeyEnv != null && !apiKeyEnv.isBlank() && !apiKeyEnv.matches("[A-Z0-9_]+")) {
                return "API key env var name must be uppercase letters, digits, or '_'";
            }
            if (contextWindow < 1024) return "Context window must be at least 1024";
            if (maxTokens < 16) return "Max tokens must be at least 16";
            return null;
        }

        /**
         * Convenience: derive a sensible env var name from the provider id when blank.
         */
        @NotNull
        public String effectiveApiKeyEnv() {
            if (apiKeyEnv != null && !apiKeyEnv.isBlank()) return apiKeyEnv;
            String safe = id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
            return safe + "_API_KEY";
        }
    }

    public static final class State {
        public List<Entry> providers = new ArrayList<>(); // NOSONAR java:S1104
    }

    private State state = new State();

    @NotNull
    public static PiCustomProvidersService getInstance() {
        return ApplicationManager.getApplication().getService(PiCustomProvidersService.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
        if (this.state.providers == null) this.state.providers = new ArrayList<>();
    }

    /**
     * Returns a defensive copy so callers cannot mutate the persisted list.
     */
    @NotNull
    public synchronized List<Entry> getProviders() {
        List<Entry> copy = new ArrayList<>(state.providers.size());
        for (Entry e : state.providers) copy.add(e.copy());
        return copy;
    }

    public synchronized void setProviders(@NotNull List<Entry> providers) {
        List<Entry> copies = new ArrayList<>(providers.size());
        for (Entry e : providers) copies.add(e.copy());
        state.providers = copies;
    }
}
