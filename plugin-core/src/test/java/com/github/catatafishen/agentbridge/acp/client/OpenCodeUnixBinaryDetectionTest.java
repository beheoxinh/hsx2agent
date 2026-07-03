package com.github.catatafishen.agentbridge.acp.client;

import com.intellij.openapi.util.SystemInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link OpenCodeClient#resolveBundledUnixOpenCodePath(String)}.
 */
class OpenCodeUnixBinaryDetectionTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsBundledUnixBinaryForNpmLauncher() throws IOException {
        if (SystemInfo.isWindows) {
            return;
        }

        Path wrapper = tempDir.resolve(Path.of("node_modules", "opencode-ai", "bin", "opencode.exe"));
        Files.createDirectories(wrapper.getParent());
        Files.writeString(wrapper, "shim");

        Path nativeBinary = tempDir.resolve(Path.of(
            "node_modules", "opencode-ai", "node_modules", "opencode-linux-x64", "bin", "opencode"));
        Files.createDirectories(nativeBinary.getParent());
        Files.writeString(nativeBinary, "binary");

        assertEquals(nativeBinary.toString(), OpenCodeClient.resolveBundledUnixOpenCodePath(wrapper.toString()));
    }

    @Test
    void returnsNullWhenLauncherIsNotNpmWrapper() {
        assertNull(OpenCodeClient.resolveBundledUnixOpenCodePath(tempDir.resolve("opencode").toString()));
    }

    @Test
    void returnsNullWhenBundledBinaryMissing() throws IOException {
        if (SystemInfo.isWindows) {
            return;
        }

        Path wrapper = tempDir.resolve(Path.of("node_modules", "opencode-ai", "bin", "opencode.exe"));
        Files.createDirectories(wrapper.getParent());
        Files.writeString(wrapper, "shim");

        assertNull(OpenCodeClient.resolveBundledUnixOpenCodePath(wrapper.toString()));
    }
}
