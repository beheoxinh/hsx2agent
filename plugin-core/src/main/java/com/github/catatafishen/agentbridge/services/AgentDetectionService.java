package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings;
import com.github.catatafishen.agentbridge.settings.BinaryDetector;
import com.github.catatafishen.agentbridge.settings.ShellEnvironment;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central service for detecting agent binaries on the system.
 *
 * <p>This is the single source of truth for which agents are installed.
 * It provides synchronous and asynchronous detection, caches results per
 * profile, and publishes events when detection results change.</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Comprehensive multi-platform binary detection (PATH, common dirs, WSL, brew, snap, etc.)</li>
 *   <li>Validates existing paths — if a previously detected binary no longer exists,
 *       the detection is reset and a full re-scan is triggered.</li>
 *   <li>Auto-detection runs when the connect panel opens and after MCP server starts.</li>
 *   <li>Auto-connect waits for detection to complete before connecting.</li>
 *   <li>Background periodic re-detection to catch newly installed agents.</li>
 * </ul>
 * </p>
 */
@Service(Service.Level.APP)
public final class AgentDetectionService implements Disposable {

    private static final Logger LOG = Logger.getInstance(AgentDetectionService.class);

    /**
     * Result of a single agent's binary detection.
     */
    public record DetectionResult(
        String profileId,
        @Nullable String path,
        @Nullable String version,
        AgentProfile.DetectionSource source,
        long detectedAtEpochMs
    ) {
        public boolean isFound() {
            return path != null && !path.isEmpty();
        }
    }

    /**
     * Listener for detection events.
     */
    public interface DetectionListener {
        /** Called after detection completes for one or more profiles. */
        void onDetectionComplete(@NotNull Set<String> profileIds);

        /** Called when a single profile's detection status changes. */
        void onStatusChanged(@NotNull String profileId, @NotNull DetectionResult result);
    }

    private final Map<String, DetectionResult> resultCache = new ConcurrentHashMap<>();
    private final List<DetectionListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean detectionInProgress = new AtomicBoolean(false);
    private final AtomicInteger periodicTaskIndex = new AtomicInteger(0);
    private volatile boolean disposed;

    public AgentDetectionService() {
        LOG.info("AgentDetectionService initialized");
    }

    @NotNull
    public static AgentDetectionService getInstance() {
        return ApplicationManager.getApplication().getService(AgentDetectionService.class);
    }

    /**
     * Add a listener for detection events.
     */
    public void addListener(@NotNull DetectionListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a listener.
     */
    public void removeListener(@NotNull DetectionListener listener) {
        listeners.remove(listener);
    }

    // ── Public Query API ──

    /**
     * Returns the cached detection result for a profile, or null if not yet detected.
     */
    @Nullable
    public DetectionResult getResult(@NotNull String profileId) {
        return resultCache.get(profileId);
    }

    /**
     * Returns true if the given profile's agent binary is available (cached check).
     * Falls back to synchronous detection if no cached result exists.
     */
    public boolean isInstalled(@NotNull String profileId) {
        DetectionResult cached = resultCache.get(profileId);
        if (cached != null) {
            return cached.isFound();
        }
        // No cache — do a quick synchronous check
        AgentProfileManager mgr = AgentProfileManager.getInstance();
        AgentProfile profile = mgr.getProfile(profileId);
        if (profile == null) return false;
        return detectSingle(profile, true).isFound();
    }

    /**
     * Returns all profiles that have a detected or user-configured binary.
     */
    @NotNull
    public List<AgentProfile> getInstalledProfiles() {
        AgentProfileManager mgr = AgentProfileManager.getInstance();
        List<AgentProfile> result = new ArrayList<>();
        for (AgentProfile p : mgr.getAllProfiles()) {
            if (isInstalled(p.getId())) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Returns the effective binary path for a profile: custom > detected > null.
     */
    @Nullable
    public String resolveBinary(@NotNull String profileId) {
        AgentProfileManager mgr = AgentProfileManager.getInstance();
        AgentProfile profile = mgr.getProfile(profileId);
        if (profile == null) return null;
        return resolveBinary(profile);
    }

    /**
     * Returns the effective binary path for a profile.
     */
    @Nullable
    public static String resolveBinary(@NotNull AgentProfile profile) {
        // 1. Custom path takes priority
        String custom = profile.getCustomBinaryPath();
        if (!custom.isEmpty()) {
            return custom;
        }
        // 2. Auto-detected path
        String detected = profile.getDetectedBinaryPath();
        return !detected.isEmpty() ? detected : null;
    }

    /**
     * Validates a binary path. If the path no longer exists on disk,
     * resets the profile's detection state and returns true.
     * Returns false if the path is still valid.
     */
    public boolean validateAndResetIfStale(@NotNull AgentProfile profile) {
        String effective = resolveBinary(profile);
        if (effective == null) return false;

        if (!new File(effective).exists()) {
            LOG.info("Binary '" + effective + "' for " + profile.getDisplayName()
                + " no longer exists on disk. Resetting detection state.");
            profile.resetDetectionState();
            resultCache.remove(profile.getId());
            fireStatusChanged(profile.getId(),
                new DetectionResult(profile.getId(), null, null,
                    AgentProfile.DetectionSource.NOT_FOUND, System.currentTimeMillis()));
            return true;
        }
        return false;
    }

    // ── Detection Execution ──

    /**
     * Runs detection for all profiles in the background.
     * If a profile already has a valid custom path, that profile is skipped.
     * Returns a CompletableFuture that completes when all detection finishes.
     */
    @NotNull
    public CompletableFuture<Void> detectAllInBackground() {
        LOG.info("Agent binary detection requested");
        ShellEnvironment.refresh();

        CompletableFuture<Void> future = new CompletableFuture<>();
        if (!detectionInProgress.compareAndSet(false, true)) {
            LOG.debug("Detection already in progress, returning existing future");
            return CompletableFuture.completedFuture(null);
        }

        AppExecutorUtil.getAppExecutorService().submit(() -> {
            try {
                AgentProfileManager mgr = AgentProfileManager.getInstance();
                List<AgentProfile> profiles = mgr.getAllProfiles();
                Set<String> changedIds = ConcurrentHashMap.newKeySet();

                profiles.parallelStream().forEach(profile -> {
                    DetectionResult existing = resultCache.get(profile.getId());
                    DetectionResult result = detectSingle(profile,
                        existing == null || !existing.isFound());

                    // Did the result change?
                    boolean changed = existing == null
                        || (existing.isFound() != result.isFound())
                        || !java.util.Objects.equals(existing.path(), result.path());

                    if (changed) {
                        resultCache.put(profile.getId(), result);
                        updateProfileFromResult(profile, result);
                        changedIds.add(profile.getId());
                        fireStatusChanged(profile.getId(), result);
                    }
                });

                // Mark detection as complete
                AgentBridgeStorageSettings.getInstance().getState().setAgentBinaryDetectionRun(true);
                LOG.info("Agent binary detection complete. Found "
                    + changedIds.stream().filter(id -> {
                        DetectionResult r = resultCache.get(id);
                        return r != null && r.isFound();
                    }).count() + " / " + profiles.size() + " agents.");

                if (!changedIds.isEmpty()) {
                    fireDetectionComplete(changedIds);
                }

                future.complete(null);
            } catch (Exception e) {
                LOG.warn("Agent binary detection failed", e);
                future.completeExceptionally(e);
            } finally {
                detectionInProgress.set(false);
            }
        });

        return future;
    }

    /**
     * Runs detection for a single profile. Returns the result without caching.
     */
    @NotNull
    private DetectionResult detectSingle(@NotNull AgentProfile profile, boolean force) {
        // 1. If user has a custom path, validate it
        String customPath = profile.getCustomBinaryPath();
        if (!customPath.isEmpty()) {
            if (new File(customPath).exists()) {
                String version = BinaryDetector.getVersionForPath(customPath);
                DetectionResult result = new DetectionResult(
                    profile.getId(), customPath, version,
                    AgentProfile.DetectionSource.USER_CONFIGURED,
                    System.currentTimeMillis());
                updateProfileFromResult(profile, result);
                return result;
            } else {
                // Custom path exists but file is gone — auto-detection will try to find it
                LOG.info("Custom path '" + customPath + "' for " + profile.getDisplayName()
                    + " no longer exists. Will attempt auto-detection.");
                // Do NOT clear customBinaryPath — user needs to see it's wrong in Settings
                // But for effective resolution, we fall through to auto-detect below
            }
        }

        // 2. Try auto-detection
        String binaryName = profile.getBinaryName();
        List<String> namesToTry = new ArrayList<>();
        if (!binaryName.isEmpty()) namesToTry.add(binaryName);
        namesToTry.addAll(profile.getAlternateNames());

        for (String name : namesToTry) {
            // Find ALL locations, then pick highest version
            List<String> allPaths = findAllBinaryPaths(name);
            if (allPaths.isEmpty()) continue;

            // Pick highest version
            String bestPath = null;
            String bestVersion = null;
            for (String path : allPaths) {
                String version = BinaryDetector.getVersionForPath(path);
                if (version == null) continue;
                if (bestVersion == null || BinaryDetector.compareVersions(version, bestVersion) > 0) {
                    bestVersion = version;
                    bestPath = path;
                }
            }

            if (bestPath == null) {
                // Version detection failed — use first found
                bestPath = allPaths.getFirst();
                bestVersion = BinaryDetector.getVersionForPath(bestPath);
            }

            if (bestPath != null) {
                DetectionResult result = new DetectionResult(
                    profile.getId(), bestPath, bestVersion,
                    AgentProfile.DetectionSource.AUTO_DETECTED,
                    System.currentTimeMillis());
                updateProfileFromResult(profile, result);
                LOG.info("Detected " + profile.getDisplayName() + " at: " + bestPath
                    + (bestVersion != null ? " (v" + bestVersion + ")" : ""));
                return result;
            }
        }

        // 3. Not found
        DetectionResult notFound = new DetectionResult(
            profile.getId(), null, null,
            AgentProfile.DetectionSource.NOT_FOUND,
            System.currentTimeMillis());
        updateProfileFromResult(profile, notFound);
        return notFound;
    }

    /**
     * Run synchronous detection for a single profile (for immediate use).
     * Updates cache and profile, returns the result.
     */
    @NotNull
    public DetectionResult detectSingleSync(@NotNull String profileId) {
        AgentProfileManager mgr = AgentProfileManager.getInstance();
        AgentProfile profile = mgr.getProfile(profileId);
        if (profile == null) {
            return new DetectionResult(profileId, null, null,
                AgentProfile.DetectionSource.NOT_FOUND, System.currentTimeMillis());
        }

        // First, check if any stale path needs resetting
        validateAndResetIfStale(profile);

        DetectionResult result = detectSingle(profile, true);
        resultCache.put(profileId, result);
        updateProfileFromResult(profile, result);
        fireStatusChanged(profileId, result);
        return result;
    }

    /**
     * Blocks until the initial detection run completes.
     * Safe to call from auto-connect flows — waits up to the given timeout.
     *
     * @param timeoutMillis maximum time to wait
     * @return true if detection completed within the timeout
     */
    public boolean awaitInitialDetection(long timeoutMillis) {
        if (AgentBridgeStorageSettings.getInstance().getState().isAgentBinaryDetectionRun()) {
            return true; // Already done
        }
        // If detection is not running, start it
        if (!detectionInProgress.get()) {
            detectAllInBackground();
        }
        // Wait for detection to complete (poll with small sleeps)
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (AgentBridgeStorageSettings.getInstance().getState().isAgentBinaryDetectionRun()) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return AgentBridgeStorageSettings.getInstance().getState().isAgentBinaryDetectionRun();
    }

    /**
     * Schedule periodic re-detection to catch newly installed agents.
     */
    public void startPeriodicDetection() {
        if (disposed) return;
        int idx = periodicTaskIndex.incrementAndGet();
        AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            () -> {
                if (disposed || idx != periodicTaskIndex.get()) return;
                if (detectionInProgress.get()) return;
                detectAllInBackground();
            },
            5, 5, TimeUnit.MINUTES);
        LOG.info("Periodic agent detection scheduled (every 5 minutes)");
    }

    // ── Multi-Platform Binary Discovery ──

    /**
     * Finds ALL absolute paths to a binary across PATH, OS-native tools,
     * package managers, and platform-specific locations.
     *
     * <p>Coverage:
     * <ul>
     *   <li>PATH directories (shell env or system env)</li>
     *   <li>OS-native tools: {@code whereis -b}, {@code which -a}</li>
     *   <li>Version managers: mise, nvm, sdkman, cargo</li>
     *   <li>macOS: Homebrew (Intel + Apple Silicon)</li>
     *   <li>Linux: snap, flatpak, linuxbrew</li>
     *   <li>Windows: scoop, chocolatey, winget, nvm-windows, bun, pnpm</li>
     *   <li>WSL: query WSL distros for Linux binary</li>
     *   <li>Common system directories (fallback)</li>
     * </ul>
     * </p>
     */
    @NotNull
    public static List<String> findAllBinaryPaths(@NotNull String binaryName) {
        // Use BinaryDetector's existing comprehensive scan first
        List<String> results = new ArrayList<>(BinaryDetector.findAllBinaryPaths(binaryName));

        // Additional platform-specific paths not covered by BinaryDetector
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);

        if (os.contains("windows")) {
            addWindowsPaths(results, binaryName);
        } else if (os.contains("mac")) {
            addMacPaths(results, binaryName);
        } else {
            addLinuxPaths(results, binaryName);
        }

        // WSL interop (when running on Windows)
        if (os.contains("windows")) {
            addWslPaths(results, binaryName);
        }

        return results;
    }

    private static void addWindowsPaths(@NotNull List<String> results, @NotNull String binaryName) {
        String home = System.getProperty("user.home");
        String[] extensions = {".exe", ".cmd", ".bat", ".ps1", ""};

        // Scoop
        addIfExists(results, home + "\\scoop\\shims\\" + binaryName, extensions);
        // Chocolatey
        String chocoInstall = System.getenv("ChocolateyInstall");
        if (chocoInstall == null) chocoInstall = "C:\\ProgramData\\chocolatey";
        addIfExists(results, chocoInstall + "\\bin\\" + binaryName, extensions);
        // nvm-windows
        String nvmHome = System.getenv("NVM_HOME");
        if (nvmHome != null) {
            addIfExists(results, nvmHome + "\\" + binaryName, extensions);
        }
        // winget links
        addIfExists(results, home + "\\AppData\\Local\\Microsoft\\WinGet\\links\\" + binaryName, extensions);
        // Bun
        String bunInstall = System.getenv("BUN_INSTALL");
        if (bunInstall != null) {
            addIfExists(results, bunInstall + "\\bin\\" + binaryName, extensions);
        }
    }

    private static void addMacPaths(@NotNull List<String> results, @NotNull String binaryName) {
        String home = System.getProperty("user.home");

        // Homebrew via dynamic prefix (handles both Intel and Apple Silicon)
        try {
            ProcessBuilder pb = new ProcessBuilder("brew", "--prefix");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                String prefix = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!prefix.isEmpty()) {
                    addIfExists(results, prefix + "/bin/" + binaryName);
                }
            }
        } catch (Exception e) {
            // brew not available — fallback to hardcoded paths
        }

        // Also check standard Homebrew cellar paths
        addIfExists(results, "/opt/homebrew/bin/" + binaryName);
        addIfExists(results, "/usr/local/bin/" + binaryName);

        // ~/.local/bin (pip --user, mise, etc.)
        addIfExists(results, home + "/.local/bin/" + binaryName);
        // ~/.cargo/bin
        addIfExists(results, home + "/.cargo/bin/" + binaryName);
    }

    private static void addLinuxPaths(@NotNull List<String> results, @NotNull String binaryName) {
        String home = System.getProperty("user.home");

        // snap
        addIfExists(results, "/snap/bin/" + binaryName);
        // flatpak
        addIfExists(results, "/var/lib/flatpak/exports/bin/" + binaryName);
        // linuxbrew
        addIfExists(results, "/home/linuxbrew/.linuxbrew/bin/" + binaryName);
        // ~/.local/bin
        addIfExists(results, home + "/.local/bin/" + binaryName);
        // ~/.cargo/bin
        addIfExists(results, home + "/.cargo/bin/" + binaryName);
        // Standard system paths (fallback for non-homebrew systems)
        addIfExists(results, "/usr/bin/" + binaryName);
        addIfExists(results, "/usr/local/bin/" + binaryName);
    }

    /**
     * Queries WSL distros for a binary.
     * Only available when running on Windows with WSL installed.
     */
    private static void addWslPaths(@NotNull List<String> results, @NotNull String binaryName) {
        try {
            // Check if WSL is available
            ProcessBuilder checkPb = new ProcessBuilder("wsl", "--status");
            checkPb.redirectErrorStream(true);
            Process checkProcess = checkPb.start();
            boolean wslAvailable = checkProcess.waitFor(3, TimeUnit.SECONDS) && checkProcess.exitValue() == 0;
            if (!wslAvailable) return;

            // Get list of WSL distros
            ProcessBuilder listPb = new ProcessBuilder("wsl", "-l", "--quiet");
            listPb.redirectErrorStream(true);
            Process listProcess = listPb.start();
            if (!listProcess.waitFor(5, TimeUnit.SECONDS) || listProcess.exitValue() != 0) return;

            String distroOutput = new String(listProcess.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            for (String distro : distroOutput.split("\n")) {
                distro = distro.trim();
                if (distro.isEmpty() || distro.contains(" ")) continue;

                // Find binary in the WSL distro
                ProcessBuilder findPb = new ProcessBuilder(
                    "wsl", "-d", distro, "sh", "-c",
                    "command -v " + binaryName + " 2>/dev/null || which " + binaryName + " 2>/dev/null || true"
                );
                findPb.redirectErrorStream(true);
                Process findProcess = findPb.start();
                if (!findProcess.waitFor(5, TimeUnit.SECONDS) || findProcess.exitValue() != 0) continue;

                String wslPath = new String(findProcess.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
                if (wslPath.isEmpty() || !wslPath.startsWith("/")) continue;

                // Convert WSL Linux path to Windows path
                ProcessBuilder convertPb = new ProcessBuilder("wslpath", "-w", wslPath);
                convertPb.redirectErrorStream(true);
                Process convertProcess = convertPb.start();
                if (convertProcess.waitFor(3, TimeUnit.SECONDS) && convertProcess.exitValue() == 0) {
                    String winPath = new String(convertProcess.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (!winPath.isEmpty() && !results.contains(winPath)) {
                        results.add(winPath + " [WSL:" + distro + "]");
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("WSL binary detection failed (WSL may not be installed): " + e.getMessage());
        }
    }

    private static void addIfExists(@NotNull List<String> results, @NotNull String path) {
        if (new File(path).isFile() && !results.contains(path)) {
            results.add(path);
        }
    }

    private static void addIfExists(@NotNull List<String> results, @NotNull String basePath,
                                    @NotNull String[] extensions) {
        for (String ext : extensions) {
            String path = basePath + ext;
            if (new File(path).isFile() && !results.contains(path)) {
                results.add(path);
                return; // Only add the first matching extension
            }
        }
    }

    // ── Helpers ──

    private void updateProfileFromResult(@NotNull AgentProfile profile, @NotNull DetectionResult result) {
        if (result.source() == AgentProfile.DetectionSource.AUTO_DETECTED) {
            profile.setDetectedBinaryPath(result.path() != null ? result.path() : "");
            profile.setDetectionSource(result.isFound()
                ? AgentProfile.DetectionSource.AUTO_DETECTED
                : AgentProfile.DetectionSource.NOT_FOUND);
            profile.setLastDetectedAt(Instant.now().toString());
        } else if (result.source() == AgentProfile.DetectionSource.USER_CONFIGURED) {
            profile.setDetectionSource(AgentProfile.DetectionSource.USER_CONFIGURED);
        } else if (result.source() == AgentProfile.DetectionSource.NOT_FOUND) {
            profile.setDetectedBinaryPath("");
            profile.setDetectionSource(AgentProfile.DetectionSource.NOT_FOUND);
            profile.setLastDetectedAt(Instant.now().toString());
        }
    }

    private void fireDetectionComplete(@NotNull Set<String> profileIds) {
        for (DetectionListener l : listeners) {
            try {
                l.onDetectionComplete(profileIds);
            } catch (Exception e) {
                LOG.warn("Detection listener error", e);
            }
        }
    }

    private void fireStatusChanged(@NotNull String profileId, @NotNull DetectionResult result) {
        for (DetectionListener l : listeners) {
            try {
                l.onStatusChanged(profileId, result);
            } catch (Exception e) {
                LOG.warn("Detection listener error", e);
            }
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        resultCache.clear();
        listeners.clear();
    }
}
