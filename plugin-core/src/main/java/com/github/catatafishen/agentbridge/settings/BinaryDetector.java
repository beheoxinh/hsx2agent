package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Detects binaries using the user's full shell environment.
 * Works with any installation method (nvm, homebrew, system packages, etc.).
 */
public class BinaryDetector {
    private static final Logger LOG = Logger.getInstance(BinaryDetector.class);

    private static final String VERSION_FLAG = " --version";

    private static final String DEFAULT_PATHEXT =
        ".COM;.EXE;.BAT;.CMD;.VBS;.VBE;.JS;.JSE;.WSF;.WSH;.MSC";

    private BinaryDetector() {
    }

    /**
     * Detect if a binary exists and get its version, trying alternate names if primary fails.
     *
     * @param binaryName     Name of the binary (e.g., "copilot", "opencode")
     * @param alternateNames Alternate names to try if primary name not found
     * @return Version string like "v1.2.3", or null if not found
     */
    @Nullable
    public static String detectBinaryVersion(@NotNull String binaryName, @NotNull String[] alternateNames) {
        String version = tryDetectBinary(binaryName);
        if (version != null) return version;

        for (String altName : alternateNames) {
            version = tryDetectBinary(altName);
            if (version != null) return version;
        }

        return null;
    }

    @Nullable
    private static String tryDetectBinary(@NotNull String binaryName) {
        // Phase 1: command -v via shell (fast, relies on subprocess PATH)
        List<String> cmd = isWindows()
            ? List.of("cmd.exe", "/c", binaryName + VERSION_FLAG)
            : List.of("sh", "-c", "command -v " + binaryName + " >/dev/null && " + binaryName + VERSION_FLAG);

        String output = runCommand(cmd, 5);
        if (output != null) {
            String version = parseVersion(output);
            if (version != null) {
                LOG.info("Detected " + binaryName + " version: " + version);
            }
            return version;
        }

        // Phase 1B: try each path from OS-native tools (whereis/where.exe)
        for (String path : findUsingNativeTools(binaryName)) {
            output = runCommand(
                isWindows()
                    ? List.of("cmd.exe", "/c", path + VERSION_FLAG)
                    : List.of("sh", "-c", path + VERSION_FLAG),
                5
            );
            if (output == null) continue;
            String version = parseVersion(output);
            if (version != null) {
                LOG.info("Detected " + binaryName + " version: " + version + " at " + path);
                return version;
            }
        }

        // Phase 2: try each path from findAllBinaryPaths (handles mise, custom prefixes, etc.)
        for (String path : findAllBinaryPaths(binaryName)) {
            output = runCommand(
                isWindows()
                    ? List.of("cmd.exe", "/c", path + VERSION_FLAG)
                    : List.of("sh", "-c", path + VERSION_FLAG),
                5
            );
            if (output == null) continue;
            String version = parseVersion(output);
            if (version != null) {
                LOG.info("Detected " + binaryName + " version: " + version + " at " + path);
                return version;
            }
        }

        return null;
    }

    /**
     * Find the absolute path to a binary using the captured shell environment.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>{@code command -v} via login shell</li>
     *   <li>OS-native tools ({@code whereis -b} on Unix, {@code where} on Windows)</li>
     *   <li>{@code mise bin-paths} (version-manager-managed tools)</li>
     *   <li>Scan {@code PATH} directories directly</li>
     *   <li>Scan common system locations ({@code /usr/local/bin}, {@code /opt/homebrew/bin}, …)</li>
     * </ol>
     *
     * <p>On Windows, scans the {@code PATH} directories directly using Java's {@link File}
     * API to avoid encoding issues. The {@code where.exe} approach fails for users whose PATH
     * contains non-ASCII characters (e.g. accented characters in the Windows username) because
     * the console output uses the OEM code page while Java reads it as UTF-8, mangling the path.
     *
     * @param binaryName Name of the binary to find
     * @return Absolute path, or null if not found
     */
    @Nullable
    public static String findBinaryPath(@NotNull String binaryName) {
        if (isWindows()) {
            return findBinaryOnWindowsPath(binaryName);
        }

        // Phase 1: command -v via shell (fast, covers standard PATH)
        List<String> cmd = List.of("sh", "-c", "command -v " + binaryName);
        String output = runCommand(cmd, 3);
        if (output != null && !output.isBlank()) {
            String path = output.trim().split("\n")[0].trim();
            logFound(binaryName, path);
            return path;
        }

        // Phase 1B: OS-native tools (whereis on Unix, where on Windows)
        // These search standard system dirs independently of the shell's PATH.
        List<String> nativePaths = findUsingNativeTools(binaryName);
        if (!nativePaths.isEmpty()) {
            String path = nativePaths.getFirst();
            logFound(binaryName, path);
            return path;
        }

        // Phase 1C: mise bin-paths (handles tools installed in mise-managed locations)
        List<String> misePaths = findUsingMise(binaryName);
        if (!misePaths.isEmpty()) {
            String path = misePaths.getFirst();
            logFound(binaryName, path);
            return path;
        }

        // Phase 2: fall back to findAllBinaryPaths (scans PATH + common system dirs)
        List<String> allPaths = findAllBinaryPaths(binaryName);
        if (!allPaths.isEmpty()) {
            String path = allPaths.getFirst();
            logFound(binaryName, path);
            return path;
        }

        return null;
    }

    /**
     * Find ALL absolute paths to a binary across the user's PATH, common system directories,
     * and using OS-native detection tools like 'whereis'.
     *
     * @param binaryName Name of the binary to find
     * @return list of absolute paths (may be empty, never null)
     */
    @NotNull
    public static List<String> findAllBinaryPaths(@NotNull String binaryName) {
        List<String> results = new java.util.ArrayList<>();

        // 1. OS-native tools (whereis on Unix, where on Windows)
        // Fast search independent of the user's shell PATH configuration.
        results.addAll(findUsingNativeTools(binaryName));

        // 2. mise bin-paths (handles tools from mise version manager)
        // Also works on Windows if mise is installed.
        results.addAll(findUsingMise(binaryName));

        // 3. Scan PATH directories directly
        results.addAll(findOnPath(binaryName));

        // 4. Scan hardcoded system locations (Unix)
        if (!isWindows()) {
            results.addAll(findInCommonSystemLocations(binaryName));
        }

        // Deduplicate results by canonical path
        List<String> uniqueResults = new java.util.ArrayList<>();
        for (String path : results) {
            try {
                String canonical = new File(path).getCanonicalPath();
                if (!uniqueResults.contains(canonical)) {
                    uniqueResults.add(canonical);
                }
            } catch (Exception e) {
                if (!uniqueResults.contains(path)) uniqueResults.add(path);
            }
        }

        if (uniqueResults.size() > 1) {
            LOG.info("Found " + uniqueResults.size() + " binaries for '" + binaryName + "': " + uniqueResults);
        }
        return uniqueResults;
    }

    @NotNull
    private static List<String> findOnPath(@NotNull String binaryName) {
        Map<String, String> env = ShellEnvironment.getEnvironment();
        String pathVar;
        if (isWindows()) {
            pathVar = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        } else {
            pathVar = env.getOrDefault("PATH", "");
        }

        String separator = isWindows() ? ";" : ":";
        String[] dirs = pathVar.split(separator);
        List<String> results = new java.util.ArrayList<>();

        if (isWindows()) {
            String pathext = env.getOrDefault("PATHEXT", DEFAULT_PATHEXT);
            String[] extensions = pathext.split(";");

            if (hasExtension(binaryName, extensions)) {
                collectFromDirs(dirs, binaryName, results);
            }
            for (String ext : extensions) {
                collectFromDirs(dirs, binaryName + ext, results);
            }
        } else {
            collectFromDirs(dirs, binaryName, results);
        }
        return results;
    }

    @NotNull
    private static List<String> findInCommonSystemLocations(@NotNull String binaryName) {
        List<String> results = new java.util.ArrayList<>();
        String[] commonDirs;

        if (SystemInfo.isLinux) {
            commonDirs = new String[]{
                "/usr/bin", "/usr/local/bin", "/opt/bin", "/bin",
                System.getProperty("user.home") + "/.local/bin",
                System.getProperty("user.home") + "/bin"
            };
        } else if (SystemInfo.isMac) {
            commonDirs = new String[]{
                "/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/bin"
            };
        } else {
            return results;
        }

        collectFromDirs(commonDirs, binaryName, results);
        return results;
    }

    @NotNull
    private static List<String> findUsingMise(@NotNull String binaryName) {
        List<String> results = new java.util.ArrayList<>();
        if (isWindows()) return results;

        // mise bin-paths returns versioned install directories managed by mise.
        // Works directly without needing mise activate — the raw bin-paths contain actual binaries.
        // Try common mise install locations since mise may not be on the subprocess PATH.
        String home = System.getProperty("user.home");
        String[] miseCandidates = {
            home + "/.local/bin/mise",
            home + "/.local/share/mise/mise",
            home + "/.config/mise/mise",
            "/usr/local/bin/mise",
            "/opt/mise/bin/mise",
        };

        for (String misePath : miseCandidates) {
            if (!new File(misePath).canExecute()) continue;
            String output = runCommand(List.of(misePath, "bin-paths"), 3);
            if (output == null || output.isBlank()) continue;

            for (String binDir : output.split("\n")) {
                String dir = binDir.trim();
                if (dir.isEmpty()) continue;
                File f = new File(dir, binaryName);
                if (f.isFile()) {
                    results.add(f.getAbsolutePath());
                }
            }
            break;
        }

        return results;
    }

    @NotNull
    private static List<String> findUsingNativeTools(@NotNull String binaryName) {
        List<String> results = new java.util.ArrayList<>();

        if (isWindows()) {
            // Try 'where' on Windows
            String output = runCommand(List.of("where", binaryName), 2);
            if (output != null) {
                for (String path : output.split("\n")) {
                    String trimmed = path.trim();
                    if (!trimmed.isEmpty() && new File(trimmed).isAbsolute() && new File(trimmed).isFile()) {
                        results.add(trimmed);
                    }
                }
            }
            return results;
        }

        // Try 'whereis' on Unix - returns multiple paths often
        String output = runCommand(List.of("whereis", "-b", binaryName), 2);
        if (output != null && output.contains(":")) {
            String[] parts = output.split(":", 2);
            if (parts.length > 1) {
                for (String path : parts[1].trim().split("\\s+")) {
                    String trimmed = path.trim();
                    if (!trimmed.isEmpty() && new File(trimmed).isAbsolute() && new File(trimmed).isFile()) {
                        results.add(trimmed);
                    }
                }
            }
        }

        // Try 'which -a' as well for redundancy
        String whichOutput = runCommand(List.of("which", "-a", binaryName), 2);
        if (whichOutput != null) {
            for (String path : whichOutput.split("\n")) {
                String trimmed = path.trim();
                if (!trimmed.isEmpty() && new File(trimmed).isAbsolute() && new File(trimmed).isFile()) {
                    results.add(trimmed);
                }
            }
        }

        return results;
    }

    /**
     * Gets the version string for a binary at the given absolute path.
     *
     * @param binaryPath absolute path to the binary
     * @return version string (e.g. "v1.0.40"), or null if version detection fails
     */
    @Nullable
    public static String getVersionForPath(@NotNull String binaryPath) {
        List<String> cmd = isWindows()
            ? List.of("cmd.exe", "/c", binaryPath + VERSION_FLAG)
            : List.of("sh", "-c", binaryPath + VERSION_FLAG);

        String output = runCommand(cmd, 5);
        if (output == null) return null;
        return parseVersion(output);
    }

    /**
     * Compares two version strings and returns the higher one. Handles common version
     * formats: {@code "v1.0.40"}, {@code "1.0.40"}, {@code "v1.0.40-1"}.
     *
     * @return positive if v1 > v2, negative if v1 < v2, zero if equal
     */
    public static int compareVersions(@Nullable String v1, @Nullable String v2) {
        int[] parts1 = parseVersionParts(v1);
        int[] parts2 = parseVersionParts(v2);

        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int p1 = i < parts1.length ? parts1[i] : 0;
            int p2 = i < parts2.length ? parts2[i] : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
        }
        return 0;
    }

    private static int[] parseVersionParts(@Nullable String version) {
        if (version == null || version.isBlank()) return new int[0];

        // Strip leading non-digits (e.g. "v", "Copilot v")
        String cleaned = version.replaceAll("^\\D*", "");
        // Strip trailing non-numeric suffixes (e.g. "-1", "-beta")
        cleaned = cleaned.replaceAll("[^0-9.].*$", "");
        if (cleaned.isEmpty()) return new int[0];

        String[] segments = cleaned.split("\\.");
        int[] parts = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            try {
                parts[i] = Integer.parseInt(segments[i]);
            } catch (NumberFormatException e) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    /**
     * Collects all matching files from directories into the results list.
     * Skips duplicates (same canonical path).
     */
    private static void collectFromDirs(@NotNull String[] dirs, @NotNull String fileName,
                                        @NotNull List<String> results) {
        for (String dir : dirs) {
            String trimmed = dir.trim();
            if (trimmed.isEmpty()) continue;
            File f = new File(trimmed, fileName);
            if (f.isFile()) {
                String absPath = f.getAbsolutePath();
                if (!results.contains(absPath)) {
                    results.add(absPath);
                }
            }
        }
    }

    /**
     * Scans the Windows {@code PATH} directories for a binary, respecting {@code PATHEXT}.
     * Uses Java's {@link File} API directly — no subprocess, no encoding issues.
     */
    @Nullable
    private static String findBinaryOnWindowsPath(@NotNull String binaryName) {
        Map<String, String> env = ShellEnvironment.getEnvironment();
        String pathVar = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        String pathext = env.getOrDefault("PATHEXT", DEFAULT_PATHEXT);

        String[] dirs = pathVar.split(";");
        String[] extensions = pathext.split(";");

        // If the name already has a recognized extension, check it directly first
        if (hasExtension(binaryName, extensions)) {
            String found = scanDirs(dirs, binaryName);
            if (found != null) {
                logFound(binaryName, found);
                return found;
            }
        }

        // Search with each PATHEXT extension appended
        for (String ext : extensions) {
            String found = scanDirs(dirs, binaryName + ext);
            if (found != null) {
                logFound(binaryName, found);
                return found;
            }
        }

        LOG.debug("Binary '" + binaryName + "' not found on Windows PATH");
        return null;
    }

    /**
     * Scans a list of directories for a file with the given name.
     *
     * @return the absolute path if found, or {@code null}
     */
    @Nullable
    private static String scanDirs(@NotNull String[] dirs, @NotNull String fileName) {
        for (String dir : dirs) {
            String trimmed = dir.trim();
            if (trimmed.isEmpty()) continue;
            File f = new File(trimmed, fileName);
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static boolean hasExtension(@NotNull String name, @NotNull String[] extensions) {
        String lower = name.toLowerCase();
        for (String ext : extensions) {
            if (lower.endsWith(ext.toLowerCase())) return true;
        }
        return false;
    }

    private static void logFound(@NotNull String binaryName, @NotNull String path) {
        LOG.info("Found " + binaryName + " at: " + path);
    }

    private static boolean isWindows() {
        return SystemInfo.isWindows;
    }

    /**
     * Run a command with the captured shell environment and return stdout, or null on failure.
     */
    @Nullable
    private static String runCommand(@NotNull List<String> cmd, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().clear();
            pb.environment().putAll(ShellEnvironment.getEnvironment());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }

            return process.exitValue() == 0 ? output.toString() : null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOG.debug("Command failed " + cmd + ": " + e.getMessage());
            return null;
        }
    }

    @Nullable
    private static String parseVersion(@NotNull String output) {
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            String lower = trimmed.toLowerCase();
            boolean isNoise = lower.contains("welcome") || lower.contains("loading") || lower.contains("initializing");
            if (!isNoise && containsVersionPattern(trimmed)) {
                return trimmed;
            }
        }
        return null;
    }

    /**
     * Checks if a string contains a version-like pattern (digits.digits) without using regex,
     * avoiding ReDoS risk from patterns like {@code .*\d+\.\d+.*}.
     */
    private static boolean containsVersionPattern(@NotNull String s) {
        boolean prevWasDigit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' && prevWasDigit && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1))) {
                return true;
            }
            prevWasDigit = Character.isDigit(c);
        }
        return false;
    }
}
