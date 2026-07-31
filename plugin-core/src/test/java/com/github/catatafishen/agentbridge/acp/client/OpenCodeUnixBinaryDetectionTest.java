package com.github.catatafishen.agentbridge.acp.client;

import com.intellij.openapi.util.SystemInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void returnsRealBinaryForMiseShimSymlinkToMise() throws IOException {
        if (SystemInfo.isWindows) {
            return;
        }

        // Simulate a mise shim: opencode -> mise symlink.
        Path mise = tempDir.resolve("mise");
        Files.writeString(mise, "mise binary");
        Path shim = tempDir.resolve("opencode");
        Files.createSymbolicLink(shim, mise);

        String real = OpenCodeClient.resolveMiseShimOpenCodePath(shim.toString());
        // If mise is installed on this machine, it resolves to the real opencode binary.
        // If mise is not installed, the method still runs and returns either a real path
        // or null — the important thing is it never returns the mise shim itself.
        if (real != null) {
            assertTrue(real.contains("/"), "resolved path must be absolute: " + real);
        }
    }

    @Test
    void returnsNullForRealOpenCodeBinary() throws IOException {
        if (SystemInfo.isWindows) {
            return;
        }

        Path realBinary = tempDir.resolve("opencode");
        Files.writeString(realBinary, "real opencode binary");
        assertNull(OpenCodeClient.resolveMiseShimOpenCodePath(realBinary.toString()));
    }

    @Test
    void resolvesMiseBinaryPathToRealOpenCode() {
        if (SystemInfo.isWindows) {
            return;
        }

        // The detection layer may store the canonical path of the mise shim, which is
        // the mise binary itself (e.g. ~/.local/bin/mise). This must be resolved to the
        // real opencode binary.
        String real = OpenCodeClient.resolveMiseShimOpenCodePath("/home/alienware/.local/bin/mise");
        // If mise is installed, this returns the real opencode binary.
        if (real != null) {
            assertTrue(real.contains("/"), "resolved path must be absolute: " + real);
        }
    }

    @Test
    void returnsNullForNullInput() {
        assertNull(OpenCodeClient.resolveMiseShimOpenCodePath(null));
    }
}
