package com.github.catatafishen.agentbridge.agent.pi;

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
import java.util.Map;

/**
 * Generates the Pi extension TypeScript file that proxies AgentBridge IDE tools
 * into the {@code pi} subprocess.
 *
 * <p>The Pi CLI ships without native MCP support and is extended exclusively through
 * TypeScript factory modules loaded via {@code --extension &lt;path&gt;}. This generator
 * reads the bundled template at {@code resources/pi/agentbridge-bridge.ts.template},
 * substitutes the runtime MCP URL, and writes the result to
 * {@code &lt;project&gt;/.agent-work/pi/agentbridge-bridge.ts}. The path is then passed
 * to Pi's launch command so the extension is auto-loaded at startup.</p>
 *
 * <p>At runtime, the extension fetches {@code tools/list} from the local MCP HTTP
 * server, materialises each tool as a {@code pi.registerTool()} call, and forwards
 * tool executions to {@code tools/call}. The {@code AGENTBRIDGE_MCP_URL} env var
 * lets users override the endpoint without regenerating the file.</p>
 */
public final class PiMcpBridgeGenerator {

    private static final Logger LOG = Logger.getInstance(PiMcpBridgeGenerator.class);

    private static final String TEMPLATE_RESOURCE = "/pi/agentbridge-bridge.ts.template";
    private static final String OUTPUT_RELATIVE_DIR = ".agent-work/pi";
    private static final String OUTPUT_FILE_NAME = "agentbridge-bridge.ts";

    private final Project project;

    public PiMcpBridgeGenerator(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Writes the bridge extension to {@code .agent-work/pi/agentbridge-bridge.ts}.
     *
     * @param mcpUrl        the MCP HTTP endpoint Pi should connect to
     *                      (e.g. {@code http://127.0.0.1:8765/mcp}). When {@code null} or blank,
     *                      generation is skipped and {@code null} is returned.
     * @param pluginVersion the AgentBridge plugin version, embedded in the file header
     * @return absolute path to the generated file, or {@code null} if the project has
     * no base path or the MCP URL is missing
     */
    @Nullable
    public Path generate(@Nullable String mcpUrl, @NotNull String pluginVersion) {
        if (mcpUrl == null || mcpUrl.isBlank()) {
            LOG.info("[pi-bridge] MCP URL is not available; skipping extension generation");
            return null;
        }
        String basePath = project.getBasePath();
        if (basePath == null) {
            LOG.warn("[pi-bridge] Project has no base path; cannot place extension on disk");
            return null;
        }

        try {
            String template = loadTemplate();
            String rendered = render(template, Map.of(
                "AGENTBRIDGE_MCP_URL", mcpUrl,
                "AGENTBRIDGE_PROJECT_NAME", project.getName(),
                "AGENTBRIDGE_PLUGIN_VERSION", pluginVersion
            ));

            Path outDir = Path.of(basePath, OUTPUT_RELATIVE_DIR);
            Files.createDirectories(outDir);
            Path outFile = outDir.resolve(OUTPUT_FILE_NAME);
            Files.writeString(
                outFile,
                rendered,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            LOG.info("[pi-bridge] Wrote extension to " + outFile);
            return outFile;
        } catch (IOException e) {
            LOG.warn("[pi-bridge] Failed to generate extension file", e);
            return null;
        }
    }

    @NotNull
    private String loadTemplate() throws IOException {
        try (InputStream in = PiMcpBridgeGenerator.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Bundled resource not found: " + TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @NotNull
    private static String render(@NotNull String template, @NotNull Map<String, String> vars) {
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        return out;
    }
}
