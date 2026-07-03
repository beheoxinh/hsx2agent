package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * Tests file-path extraction used for MCP tool call enrichment.
 */
class McpProtocolHandlerFilePathExtractionTest {

    @Mock
    private Project project;

    private McpProtocolHandler handler;
    private Method extractFilePath;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        handler = new McpProtocolHandler(project);
        extractFilePath = McpProtocolHandler.class.getDeclaredMethod("extractFilePath", JsonObject.class);
        extractFilePath.setAccessible(true);
    }

    @Test
    void extractsRelativePathFromAbsoluteProjectPath() throws Exception {
        when(project.getBasePath()).thenReturn("/repo/project");
        JsonObject args = new JsonObject();
        args.addProperty("path", "/repo/project/src/Main.java");

        String result = (String) extractFilePath.invoke(handler, args);

        assertEquals("src/Main.java", result);
    }

    @Test
    void extractsFirstPathFromArray() throws Exception {
        when(project.getBasePath()).thenReturn("/repo/project");
        JsonObject args = new JsonObject();
        JsonArray paths = new JsonArray();
        paths.add("/repo/project/a.txt");
        paths.add("/repo/project/b.txt");
        args.add("paths", paths);

        String result = (String) extractFilePath.invoke(handler, args);

        assertEquals("a.txt", result);
    }

    @Test
    void rejectsAbsolutePathOutsideProject() throws Exception {
        when(project.getBasePath()).thenReturn("/repo/project");
        JsonObject args = new JsonObject();
        args.addProperty("path", "/other/location/outside.txt");

        String result = (String) extractFilePath.invoke(handler, args);

        assertNull(result);
    }
}
