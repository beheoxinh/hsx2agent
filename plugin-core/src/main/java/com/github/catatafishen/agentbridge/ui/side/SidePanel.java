package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.services.PromptDbService;
import com.github.catatafishen.agentbridge.ui.ChatConsolePanel;
import com.github.catatafishen.agentbridge.ui.review.DiffPanel;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.OnePixelSplitter;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Container for the left-hand tool-window pane.
 * <p>
 * Tab selection is driven from outside (via the title-bar tab header in
 * {@link com.github.catatafishen.agentbridge.ui.ChatToolWindowContent}). This panel uses a
 * plain {@link CardLayout} so the tab strip appears in the IDE title bar rather than as a
 * separate row inside the panel itself.
 * <p>
 * Hosts five tabs:
 * <ol>
 *   <li><b>Diff</b> — pending agent edits ({@link DiffPanel}).</li>
 *   <li><b>Plan</b> — rendered view of the active agent's {@code plan.md} with a
 *       {@code (done/total)} badge when task items exist.</li>
 *   <li><b>MCP</b> — live list of MCP tool calls with timestamps and expandable I/O.</li>
 *   <li><b>Prompts</b> — searchable conversation history, click to scroll.</li>
 *   <li><b>Stats</b> — session statistics and billing info.</li>
 * </ol>
 * Tab order is deliberate: review is the most time-sensitive and sits first.
 */
public final class SidePanel extends JPanel implements Disposable {

    public static final int TAB_MCP = 0;
    public static final int TAB_PROMPT_DB = 1;

    /**
     * Display names for each tab, in index order. Unmodifiable.
     */
    public static final java.util.List<String> TAB_NAMES =
        java.util.List.of("MCP/Diff", "Prompts");

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentContainer = new JPanel(cardLayout);
    private int selectedTab = TAB_MCP;

    @Nullable
    private java.util.function.Consumer<Integer> onTabSwitch = null;

    public void setOnTabSwitch(@Nullable java.util.function.Consumer<Integer> listener) {
        this.onTabSwitch = listener;
    }

    private final JComponent mcpPanel;
    private final OnePixelSplitter reviewSplitter;
    private final OnePixelSplitter reviewStatsSplitter;
    private final SessionStatsPanel statsPanel;
    private final transient Project project;
    private final DiffPanel reviewPanel;

    public SidePanel(@NotNull Project project, @NotNull ChatConsolePanel chatConsole,
                     @NotNull SessionStatsPanel sessionStatsPanel) {
        super(new BorderLayout());
        this.project = project;
        this.statsPanel = sessionStatsPanel;

        reviewPanel = new DiffPanel(project);
        Disposer.register(this, reviewPanel);
        Disposer.register(this, sessionStatsPanel);

        PromptsPanel promptsPanel = new PromptsPanel(project, chatConsole);
        Disposer.register(this, promptsPanel);

        com.github.catatafishen.agentbridge.settings.SidePanelPosition position =
            com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance().getSidePanelPosition();
        boolean vertical = !position.isVertical();
        mcpPanel = buildMcpPanel(project);

        float initialReviewSplitterProportion = 5.0f / 11.0f;
        reviewSplitter = new OnePixelSplitter(vertical, initialReviewSplitterProportion);
        reviewSplitter.setFirstComponent(mcpPanel);
        reviewSplitter.setSecondComponent(reviewPanel);

        float initialStatsSplitterProportion = 11.0f / 15.0f;
        reviewStatsSplitter = new OnePixelSplitter(vertical, initialStatsSplitterProportion);
        reviewStatsSplitter.setFirstComponent(reviewSplitter);
        reviewStatsSplitter.setSecondComponent(statsPanel);

        contentContainer.add(reviewStatsSplitter, String.valueOf(TAB_MCP));
        contentContainer.add(promptsPanel, String.valueOf(TAB_PROMPT_DB));
        cardLayout.show(contentContainer, String.valueOf(TAB_MCP));

        PromptDbService.getInstance(project).registerNavigateCallback(params -> {
            selectTab(TAB_PROMPT_DB);
            promptsPanel.applySearchParams(params);
        });

        add(contentContainer, BorderLayout.CENTER);
        updateLayoutOrientation(com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance().getSidePanelPosition());
        updateStatsVisibility();
    }

    /**
     * Updates internal splitter orientations based on tool window position.
     * When at TOP/BOTTOM, uses horizontal split (side-by-side columns with 1:2:1 ratio).
     * When at LEFT/RIGHT, uses vertical split (top/bottom rows).
     */
    public void updateLayoutOrientation(@NotNull com.github.catatafishen.agentbridge.settings.SidePanelPosition position) {
        boolean vertical = !position.isVertical();
        reviewSplitter.setOrientation(vertical);
        reviewStatsSplitter.setOrientation(vertical);

        // reviewSplitter (MCP | Diff): 5/11 for MCP (1/3 of total), 6/11 for Diff (2/5 of total)
        reviewSplitter.setProportion(5.0f / 11.0f);
        // reviewStatsSplitter (reviewSplitter | Stats): 11/15 for reviewSplitter (1/3+2/5), 4/15 for Stats
        reviewStatsSplitter.setProportion(11.0f / 15.0f);

        reviewPanel.updateLayoutOrientation(position);
        revalidate();
        repaint();
    }

    /**
     * Switches to the given tab index and refreshes it if needed.
     */
    public void selectTab(int index) {
        if (index == selectedTab) return;
        selectedTab = index;
        cardLayout.show(contentContainer, String.valueOf(index));
        updateStatsVisibility();
        if (onTabSwitch != null) {
            onTabSwitch.accept(index);
        }
    }

    private void updateStatsVisibility() {
        boolean showStats = selectedTab != TAB_PROMPT_DB;
        statsPanel.setVisible(showStats);
        reviewStatsSplitter.revalidate();
        reviewStatsSplitter.repaint();
    }

    /**
     * Returns the currently selected tab index.
     */
    public int getSelectedTab() {
        return selectedTab;
    }

    /**
     * Switches to the review tab (merged MCP + Diff + Stats). Safe to call from the EDT.
     */
    public void selectReviewTab() {
        selectTab(TAB_MCP);
    }

    public void clearToolCalls() {
        if (mcpPanel instanceof ToolCallsWebPanel) {
            ((ToolCallsWebPanel) mcpPanel).clearAll();
        }
    }

    public void reloadToolCalls() {
        if (mcpPanel instanceof ToolCallsWebPanel) {
            ((ToolCallsWebPanel) mcpPanel).reloadHistory();
        }
    }

    /**
     * Builds the MCP area (tool calls only; hooks pane intentionally hidden).
     */
    private @NotNull JComponent buildMcpPanel(@NotNull Project project) {
        ToolCallsWebPanel toolCallsPanel = new ToolCallsWebPanel(project);
        Disposer.register(this, toolCallsPanel);
        return toolCallsPanel;
    }

    @Override
    public void dispose() {
        PromptDbService.getInstance(project).registerNavigateCallback(null);
    }
}
