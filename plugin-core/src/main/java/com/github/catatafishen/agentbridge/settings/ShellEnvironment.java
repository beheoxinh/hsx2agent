package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.SystemProperties;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Captures and caches the user's full shell environment (including nvm, sdkman, etc.).
 * This environment is used for both binary detection and runtime execution.
 */
public class ShellEnvironment {
    private static final Logger LOG = Logger.getInstance(ShellEnvironment.class);
    private static volatile Map<String, String> cachedEnvironment = null;
    private static final Object LOCK = new Object();

    private ShellEnvironment() {
    }

    /**
     * Get the captured shell environment, capturing it on first call and caching thereafter.
     *
     * @return Map of environment variables, or empty map if capture fails
     */
    @NotNull
    public static Map<String, String> getEnvironment() {
        if (cachedEnvironment != null) {
            return cachedEnvironment;
        }

        synchronized (LOCK) {
            if (cachedEnvironment != null) {
                return cachedEnvironment;
            }
            cachedEnvironment = captureEnvironment();
            return cachedEnvironment;
        }
    }

    /**
     * Force a re-capture of the shell environment (e.g., after user installs a new tool).
     */
    public static void refresh() {
        synchronized (LOCK) {
            cachedEnvironment = null;
        }
    }

    @NotNull
    private static Map<String, String> captureEnvironment() {
        if (SystemInfo.isWindows) {
            return captureWindowsEnvironment();
        } else {
            return captureUnixEnvironment();
        }
    }

    @NotNull
    private static Map<String, String> captureUnixEnvironment() {
        try {
            String userShell = resolveCaptureShell();
            String home = SystemProperties.getUserHome();
            String command = buildEnvCaptureCommand(home);

            // Phase 1: login shell with all known version-manager inits
            Map<String, String> env = runShellCapture(userShell, "-l", command);
            if (!env.isEmpty()) {
                LOG.info("Captured shell environment with PATH: " + env.get("PATH"));
                return Collections.unmodifiableMap(env);
            }

            // Phase 2: same shell without -l flag (some systems lack login shell support)
            LOG.warn("Login shell capture returned empty, retrying without -l");
            env = runShellCapture(userShell, "", command);
            if (!env.isEmpty()) {
                LOG.info("Fallback capture succeeded (no -l) with PATH: " + env.get("PATH"));
                return Collections.unmodifiableMap(env);
            }

            LOG.warn("Failed to capture shell environment, using system environment");
            return System.getenv();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Shell environment capture interrupted");
            return System.getenv();
        } catch (Exception e) {
            LOG.warn("Failed to capture shell environment: " + e.getMessage(), e);
            return System.getenv();
        }
    }

    @NotNull
    private static Map<String, String> runShellCapture(
        @NotNull String shell, @NotNull String shellFlag, @NotNull String command
    ) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(shell);
        if (!shellFlag.isEmpty()) {
            String[] flags = shellFlag.trim().split("\\s+");
            java.util.Collections.addAll(cmd, flags);
        }
        cmd.add("-c");
        cmd.add(command);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        Map<String, String> env = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int idx = line.indexOf('=');
                if (idx > 0) {
                    env.put(line.substring(0, idx), line.substring(idx + 1));
                }
            }
        }

        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            LOG.warn("Shell capture timed out after 10s for: " + String.join(" ", cmd));
        }

        return env;
    }

    /**
     * Resolve the shell to use for environment capture.
     * Prefers the user's actual login shell, then IntelliJ's terminal shell, then /bin/sh.
     */
    @NotNull
    private static String resolveCaptureShell() {
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank() && new java.io.File(shell).canExecute()) {
            return shell;
        }
        return "/bin/bash";
    }

    /**
     * Builds a shell command that sources well-known version manager init scripts
     * (nvm, sdkman, cargo, pyenv, mise, etc.) before printing the environment.
     * These scripts are safe to source in non-interactive shells — unlike ~/.bashrc.
     *
     * <p>Also ensures {@code ~/.local/bin} is on PATH since many modern tools
     * (mise, pip --user, cargo, etc.) install there.
     */
    @NotNull
    private static String buildEnvCaptureCommand(@NotNull String home) {
        String lb = home + "/.local/bin";
        return "{ "
            // Ensure ~/.local/bin is on PATH (needed for mise, pip --user, etc.)
            + "case \":$PATH:\" in *:\"" + lb + "\":*) ;; *) export PATH=\"" + lb + ":$PATH\" ;; esac; "
            // nvm
            + "[ -s '" + home + "/.nvm/nvm.sh' ] && . '" + home + "/.nvm/nvm.sh' 2>/dev/null; "
            // sdkman
            + "[ -s '" + home + "/.sdkman/bin/sdkman-init.sh' ] && . '" + home + "/.sdkman/bin/sdkman-init.sh' 2>/dev/null; "
            // cargo
            + "[ -s '" + home + "/.cargo/env' ] && . '" + home + "/.cargo/env' 2>/dev/null; "
            // pyenv
            + "[ -f '" + home + "/.pyenv/bin/pyenv' ] && export PATH='" + home + "/.pyenv/bin:$PATH' 2>/dev/null; "
            // mise (rtx successor) — activate via shims or eval hook
            + "if command -v mise >/dev/null 2>&1; then "
            +   "eval \"$(mise activate bash 2>/dev/null)\" 2>/dev/null; "
            + "elif [ -x '" + lb + "/mise' ]; then "
            +   "eval \"$('" + lb + "/mise' activate bash 2>/dev/null)\" 2>/dev/null; "
            + "fi; "
            + "env 2>/dev/null; }";
    }

    @NotNull
    private static Map<String, String> captureWindowsEnvironment() {
        // Just use system environment - HOME/USERPROFILE are set correctly on Windows
        LOG.info("Using system environment for Windows");
        return System.getenv();
    }

    /**
     * Get PATH from the captured environment, or system PATH if capture failed.
     */
    @NotNull
    public static String getPath() {
        Map<String, String> env = getEnvironment();
        String path = env.get("PATH");
        if (path == null || path.isEmpty()) {
            path = System.getenv("PATH");
        }
        return path != null ? path : "";
    }

    /**
     * Returns the shell to use for executing hook scripts, consulting IntelliJ's terminal
     * settings for the given project via reflection.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>IntelliJ's {@code TerminalProjectOptionsProvider.getShellPath()} — if the result
     *       is a recognised POSIX shell (sh, bash, zsh, dash, fish) it is returned directly.</li>
     *   <li>The {@code $SHELL} environment variable (Unix only).</li>
     *   <li>{@code /bin/sh} on Unix; {@code "sh"} on Windows (resolved via {@code PATH},
     *       e.g. Git Bash adds {@code sh} to the system PATH on Windows).</li>
     * </ol>
     *
     * <p>No disk scanning is performed.
     *
     * @param project the current project
     * @return the resolved shell executable path (never blank)
     */
    @NotNull
    public static String getShellPath(@NotNull Project project) {
        try {
            Class<?> cls = Class.forName("org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider");
            Object settings = cls.getMethod("getInstance", Project.class).invoke(null, project);
            String path = (String) settings.getClass().getMethod("getShellPath").invoke(settings);
            if (path != null && !path.isBlank() && isPosixShell(path)) {
                return path;
            }
        } catch (Exception e) {
            LOG.info("Could not read IntelliJ terminal shell path via reflection: " + e.getMessage());
        }
        return getShellPath();
    }

    /**
     * Returns a fallback shell path without consulting any project settings.
     * On Unix returns {@code $SHELL} (or {@code /bin/sh} if unset).
     * On Windows returns {@code "sh"} (resolved via {@code PATH}, e.g. Git Bash).
     *
     * <p>No disk scanning is performed.
     *
     * @return the shell executable path (never blank)
     */
    @NotNull
    public static String getShellPath() {
        if (SystemInfo.isWindows) {
            return "sh";
        }
        String envShell = System.getenv("SHELL");
        return (envShell != null && !envShell.isBlank()) ? envShell : "/bin/sh";
    }

    /**
     * Returns {@code true} if the given shell path (or bare name) refers to a POSIX-compatible
     * shell that can be used to run hook scripts.
     */
    private static boolean isPosixShell(@NotNull String shellPath) {
        String name = shellPath;
        int lastSlash = Math.max(shellPath.lastIndexOf('/'), shellPath.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = shellPath.substring(lastSlash + 1);
        }
        // Strip extension (e.g. bash.exe on Windows)
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx > 0) name = name.substring(0, dotIdx);
        return switch (name.toLowerCase()) {
            case "sh", "bash", "zsh", "dash", "fish" -> true;
            default -> false;
        };
    }
}
