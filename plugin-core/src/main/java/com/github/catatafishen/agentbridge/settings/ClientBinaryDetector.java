package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

/**
 * Base class for per-client binary detection.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>User-configured override from {@link #getConfiguredPath()}</li>
 *   <li>Auto-detection via the captured login-shell environment ({@link BinaryDetector}),
 *       which includes generic known directories like {@code /opt/homebrew/bin},
 *       {@code /usr/local/bin}, etc., plus OS-native tools ({@code whereis}, {@code which -a}).</li>
 *   <li>Binary-specific additional paths from {@link #additionalSearchPaths()} —
 *       subclasses override this to list locations that are unique to their binary
 *       (e.g. snap packages, package-manager-specific install prefixes, Windows
 *       program-files paths).</li>
 * </ol>
 */
public abstract class ClientBinaryDetector {
    private static final Logger LOG = Logger.getInstance(ClientBinaryDetector.class);

    /**
     * Return the user-configured binary path override, or {@code null} if none is set.
     */
    @Nullable
    protected abstract String getConfiguredPath();

    /**
     * Fully-qualified paths to check as a last resort, specific to this binary.
     * Only paths that are truly unique to this binary should be listed here —
     * generic directories like {@code /usr/local/bin} are already handled by
     * {@link BinaryDetector#findBinaryPath(String)}.
     *
     * <p>Default implementation returns an empty list.
     */
    @NotNull
    protected List<String> additionalSearchPaths() {
        return List.of();
    }

    /**
     * Resolve the binary: returns the configured override if set, otherwise
     * auto-detects using the captured shell environment and any
     * {@link #additionalSearchPaths()}.
     *
     * <p>Auto-detection collects all candidates (primary + alternates) via
     * {@link BinaryDetector#findAllBinaryPaths}, then picks the one with the
     * highest version. This is consistent with {@link AgentBinaryResolver}.
     *
     * @param primaryName    Primary binary name (e.g. {@code "copilot"})
     * @param alternateNames Alternate names to try when primary is not found
     * @return Absolute path or name found, or {@code null} if not found
     */
    @Nullable
    public final String resolve(@NotNull String primaryName, @NotNull String... alternateNames) {
        String configured = getConfiguredPath();
        if (configured != null) {
            return configured;
        }

        // Collect all candidates across primary and alternate names
        List<String> allCandidates = new java.util.ArrayList<>(
            BinaryDetector.findAllBinaryPaths(primaryName));
        for (String alt : alternateNames) {
            allCandidates.addAll(BinaryDetector.findAllBinaryPaths(alt));
        }

        if (allCandidates.isEmpty()) {
            // Phase 2: binary-specific additional paths (snap, linuxbrew, Windows program-files, etc.)
            for (String path : additionalSearchPaths()) {
                if (new File(path).canExecute()) {
                    return path;
                }
            }
            return null;
        }

        if (allCandidates.size() == 1) return allCandidates.getFirst();

        // Multiple candidates — pick the highest version
        String bestPath = null;
        String bestVersion = null;

        for (String path : allCandidates) {
            String version = BinaryDetector.getVersionForPath(path);
            if (version == null) continue;

            if (bestVersion == null || BinaryDetector.compareVersions(version, bestVersion) > 0) {
                bestVersion = version;
                bestPath = path;
            }
        }

        if (bestPath != null) {
            LOG.info("Selected " + bestPath + " (version: " + bestVersion
                + ") from " + allCandidates.size() + " candidates for " + primaryName);
            return bestPath;
        }

        // Version detection failed for all — fall back to first-found
        return allCandidates.getFirst();
    }
}
