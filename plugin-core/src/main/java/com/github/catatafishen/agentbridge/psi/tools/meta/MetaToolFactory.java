package com.github.catatafishen.agentbridge.psi.tools.meta;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;

import java.util.List;

public final class MetaToolFactory {

    private MetaToolFactory() {}

    public static List<Tool> create(Project project) {
        return List.of(
            new ReportSubagentStreamTool(project)
        );
    }
}
