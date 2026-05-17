package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.services.PromptDbService;
import com.github.catatafishen.agentbridge.ui.ChatConsolePanel;
import com.github.catatafishen.agentbridge.ui.review.DiffPanel;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.OnePixelSplitter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

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
    public static final int TAB_TODOS = 1;
    public static final int TAB_PROMPT_DB = 2;

    /**
     * Display names for each tab, in index order. Unmodifiable.
     */
    public static final java.util.List<String> TAB_NAMES =
        java.util.List.of("MCP/Diff", "Plan", "Prompts");

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentContainer = new JPanel(cardLayout);
    private int selectedTab = TAB_MCP;

    private String planBadge = "";
    private transient @Nullable Consumer<String> onPlanTitleChanged;

    private final JComponent mcpPanel;
    private final OnePixelSplitter reviewSplitter;
    private final OnePixelSplitter reviewStatsSplitter;
    private final SessionStatsPanel statsPanel;
    private final transient Project project;
    private final TodoPanel todoPanel;
    private final DiffPanel reviewPanel;

    public SidePanel(@NotNull Project project, @NotNull ChatConsolePanel chatConsole,
                     @NotNull SessionStatsPanel sessionStatsPanel) {
        super(new BorderLayout());
        this.project = project;
        this.statsPanel = sessionStatsPanel;

        reviewPanel = new DiffPanel(project);
        Disposer.register(this, reviewPanel);
        Disposer.register(this, sessionStatsPanel);

        todoPanel = new TodoPanel(project);
        Disposer.register(this, todoPanel);
        PromptsPanel promptsPanel = new PromptsPanel(project, chatConsole);
        Disposer.register(this, promptsPanel);

        boolean vertical = !com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance().getSidePanelPosition().isVertical();
        mcpPanel = buildMcpPanel(project);

        reviewSplitter = new OnePixelSplitter(vertical, 0.5f);
        reviewSplitter.setFirstComponent(mcpPanel);
        reviewSplitter.setSecondComponent(reviewPanel);

        reviewStatsSplitter = new OnePixelSplitter(vertical, 0.8f);
        reviewStatsSplitter.setFirstComponent(reviewSplitter);
        reviewStatsSplitter.setSecondComponent(statsPanel);

        contentContainer.add(reviewStatsSplitter, String.valueOf(TAB_MCP));
        contentContainer.add(todoPanel, String.valueOf(TAB_TODOS));
        contentContainer.add(promptsPanel, String.valueOf(TAB_PROMPT_DB));
        cardLayout.show(contentContainer, String.valueOf(TAB_MCP));

        todoPanel.setOnProgressChanged(() -> {
            int total = todoPanel.getTotal();
            int done = todoPanel.getDone();
            planBadge = total > 0 ? " (" + done + "/" + total + ")" : "";
            if (onPlanTitleChanged != null) onPlanTitleChanged.accept(getPlanTitle());
        });

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
     * When at TOP/BOTTOM, uses horizontal split (side-by-side columns).
     * When at LEFT/RIGHT, uses vertical split (top/bottom rows).
     */
    public void updateLayoutOrientation(@NotNull com.github.catatafishen.agentbridge.settings.SidePanelPosition position) {
        boolean vertical = !position.isVertical();
        reviewSplitter.setOrientation(vertical);
        reviewStatsSplitter.setOrientation(vertical);

        if (position.isVertical()) {
            // TOP/BOTTOM: MCP 2/7, Diff 4/7, Stats 1/7.
            reviewSplitter.setProportion(2.0f / 7.0f);
            reviewStatsSplitter.setProportion(6.0f / 7.0f);
        } else {
            // LEFT/RIGHT: keep old stacked proportions.
            reviewSplitter.setProportion(0.5f);
            reviewStatsSplitter.setProportion(0.8f);
        }

        reviewPanel.updateLayoutOrientation(position);
        todoPanel.updateLayoutOrientation(position);
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
        if (index == TAB_TODOS) todoPanel.refresh();
        updateStatsVisibility();
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

    public @NotNull String getPlanTitle() {
        return "Plan" + planBadge;
    }

    public void setOnPlanTitleChanged(@Nullable Consumer<String> callback) {
        this.onPlanTitleChanged = callback;
    }

    /**
     * Switches to the review tab (merged MCP + Diff + Stats). Safe to call from the EDT.
     */
    public void selectReviewTab() {
        selectTab(TAB_MCP);
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
