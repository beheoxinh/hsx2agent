package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.AgentProfileManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates smart detection of agent binaries across the system.
 */
public class SmartAgentDetector {
    private static final Logger LOG = Logger.getInstance(SmartAgentDetector.class);

    private final Project project;

    public SmartAgentDetector(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Runs detection for all known agent profiles in a background task.
     * Updates profile settings if a binary is found and no custom path is currently set.
     */
    public void detectAllInBackground(boolean force) {
        new Task.Backgroundable(project, "Detecting AI Agents", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                detectAll(indicator, force);
            }
        }.queue();
    }

    private void detectAll(@NotNull ProgressIndicator indicator, boolean force) {
        AgentProfileManager profileManager = AgentProfileManager.getInstance();
        List<AgentProfile> profiles = profileManager.getAllProfiles();
        int count = 0;

        for (AgentProfile profile : profiles) {
            if (indicator.isCanceled()) break;
            indicator.setText("Searching for " + profile.getDisplayName() + "...");

            // Skip if already has a custom path, unless force is requested
            String current = profile.getCustomBinaryPath();
            if (!force && !current.isEmpty()) continue;

            List<String> names = new ArrayList<>();
            names.add(profile.getBinaryName());
            names.addAll(profile.getAlternateNames());

            String bestPath = null;
            String bestVersion = null;

            for (String name : names) {
                List<String> paths = BinaryDetector.findAllBinaryPaths(name);
                for (String path : paths) {
                    String version = BinaryDetector.getVersionForPath(path);
                    if (bestPath == null || (version != null && (bestVersion == null || BinaryDetector.compareVersions(version, bestVersion) > 0))) {
                        bestPath = path;
                        bestVersion = version;
                    }
                }
            }

            if (bestPath != null) {
                LOG.info("Smart-detected " + profile.getDisplayName() + " at: " + bestPath + " (v" + bestVersion + ")");
                profileManager.saveBinaryPath(profile.getId(), bestPath);
                count++;
            }
        }

        if (count > 0) {
            LOG.info("Smart detection finished. Updated " + count + " agent paths.");
            // Mark detection as run
            AgentBridgeStorageSettings.getInstance().getState().setAgentBinaryDetectionRun(true);
        }
    }
}
