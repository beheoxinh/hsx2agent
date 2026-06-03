package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.ui.BillingCalculator;
import com.github.catatafishen.agentbridge.ui.BillingDisplayData;
import com.github.catatafishen.agentbridge.ui.BillingManager;
import com.github.catatafishen.agentbridge.ui.ProcessingTimerPanel;
import com.github.catatafishen.agentbridge.ui.SessionStatsSnapshot;
import com.github.catatafishen.agentbridge.ui.TimerDisplayFormatter;
import com.github.catatafishen.agentbridge.ui.UsageGraphPanel;
import com.github.catatafishen.agentbridge.ui.util.VerticalScrollablePanel;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public final class SessionStatsPanel extends JPanel implements Disposable {

    private static final DateTimeFormatter RESET_DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final String LABEL_TOKENS = "Tokens";
    private static final String LABEL_PREMIUM_REQ = "Premium req";
    private static final String TOKENS_IN_OUT_SEP = " in / ";
    private static final String TOKENS_OUT_SUFFIX = " out";

    private final transient ProcessingTimerPanel timerPanel;
    private final transient BillingManager billing;
    private Font smallFont;
    private final Color dimColor;

    private final List<JLabel> scalableLabels = new ArrayList<>();
    private final List<JLabel> boldScalableLabels = new ArrayList<>();

    // Current turn section
    private final JLabel turnHeaderLabel = new JLabel("Active turn");
    private final JLabel turnTimeValue = new JLabel();
    private final JLabel turnTokensRowLabel = new JLabel(LABEL_TOKENS);
    private final JLabel turnTokensValue = new JLabel();
    private final JPanel turnTokensRow;
    private final JPanel turnSection;

    // Session stats value labels
    private final JLabel timeValue = new JLabel();
    private final JLabel turnsValue = new JLabel();
    private final JLabel tokensValue = new JLabel();

    // Dynamic labels whose text changes based on provider mode
    private final JLabel tokensRowLabel = new JLabel(LABEL_TOKENS);
    private final JPanel turnsRow;
    private final JPanel tokensRow;

    // Billing section widgets
    private final JLabel usageValue = new JLabel();
    private final JLabel remainingValue = new JLabel();
    private final JLabel resetsValue = new JLabel();
    private final JPanel usageRow;
    private final JPanel remainingRow;
    private final JPanel resetsRow;
    private final JPanel billingSection;

    private final transient Project project;

    public SessionStatsPanel(
        @NotNull Project project,
        @NotNull ProcessingTimerPanel timerPanel,
        @NotNull UsageGraphPanel usageGraphPanel,
        @NotNull BillingManager billing
    ) {
        super(new BorderLayout());
        this.project = project;
        this.timerPanel = timerPanel;
        this.billing = billing;

        this.smallFont = UIManager.getFont("Label.font").deriveFont((float) JBUI.scale(11));
        this.dimColor = JBUI.CurrentTheme.Label.disabledForeground();

        // Current turn section
        JPanel turnHeader = createSectionHeader(turnHeaderLabel);

        JPanel turnGrid = new JPanel(new GridBagLayout());
        turnGrid.setOpaque(false);
        turnGrid.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(2), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)));

        int tRow = 0;
        addStatRow(turnGrid, tRow++, "Time", turnTimeValue);
        turnTokensRow = addStatRowWithLabel(turnGrid, tRow, turnTokensRowLabel, turnTokensValue);

        turnSection = new JPanel();
        turnSection.setLayout(new BoxLayout(turnSection, BoxLayout.Y_AXIS));
        turnSection.setOpaque(false);
        turnSection.add(turnHeader);
        turnSection.add(turnGrid);
        turnSection.setVisible(false);
        leftAlignSection(turnSection);
        leftAlignChild(turnGrid);

        // Session stats grid
        JPanel statsGrid = new JPanel(new GridBagLayout());
        statsGrid.setOpaque(false);
        statsGrid.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(4), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)));

        int row = 0;
        addStatRow(statsGrid, row++, "Time", timeValue);
        turnsRow = addStatRow(statsGrid, row++, "Turns", turnsValue);
        tokensRow = addStatRowWithLabel(statsGrid, row, tokensRowLabel, tokensValue);

        // Usage graph
        JPanel graphSection = new JPanel(new BorderLayout());
        graphSection.setOpaque(false);
        graphSection.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(6), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8)));
        int graphH = JBUI.scale(100);
        usageGraphPanel.setPreferredSize(new Dimension(0, graphH));
        usageGraphPanel.setMinimumSize(new Dimension(0, graphH));
        usageGraphPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, graphH));
        graphSection.add(usageGraphPanel, BorderLayout.CENTER);

        // Billing stats grid
        JPanel billingGrid = new JPanel(new GridBagLayout());
        billingGrid.setOpaque(false);
        billingGrid.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(2), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8)));

        JPanel billingHeader = createSectionHeaderWithSuffix("Monthly quota", "via gh CLI");

        int brow = 0;
        usageRow = addStatRow(billingGrid, brow++, "Used", usageValue);
        remainingRow = addStatRow(billingGrid, brow++, "Remaining", remainingValue);
        resetsRow = addStatRow(billingGrid, brow, "Resets", resetsValue);

        billingSection = new JPanel();
        billingSection.setLayout(new BoxLayout(billingSection, BoxLayout.Y_AXIS));
        billingSection.setOpaque(false);
        billingSection.add(billingHeader);
        billingSection.add(billingGrid);
        billingSection.add(graphSection);
        leftAlignSection(billingSection);
        leftAlignChild(billingGrid);
        leftAlignChild(graphSection);

        // Assemble the stats content
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        JPanel sessionHeader = createSectionHeader("Session");
        content.add(turnSection);
        content.add(sessionHeader);
        content.add(statsGrid);
        content.add(billingSection);
        leftAlignChild(sessionHeader);
        leftAlignChild(statsGrid);

        JPanel wrapper = new VerticalScrollablePanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setOpaque(false);
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.add(content);

        JBScrollPane scrollPane = new JBScrollPane(wrapper);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        add(scrollPane, BorderLayout.CENTER);

        timerPanel.setOnStatsChanged(this::refresh);
        billing.setOnBillingChanged(this::refresh);
        refresh();
    }

    @Override
    public void dispose() {
        timerPanel.setOnStatsChanged(null);
        billing.setOnBillingChanged(null);
    }

    @Override
    @SuppressWarnings("java:S2583")
    public void updateUI() {
        super.updateUI();
        if (scalableLabels == null) return;
        smallFont = UIManager.getFont("Label.font").deriveFont((float) JBUI.scale(11));
        for (JLabel label : scalableLabels) label.setFont(smallFont);
        for (JLabel label : boldScalableLabels) label.setFont(smallFont.deriveFont(Font.BOLD));
        revalidate();
        repaint();
    }

    private static void leftAlignSection(JComponent section) {
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    private static void leftAlignChild(JComponent child) {
        child.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension pref = child.getPreferredSize();
        child.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
    }

    private JPanel createSectionHeader(String title) {
        return createSectionHeader(new JLabel(title));
    }

    private JPanel createSectionHeader(JLabel label) {
        label.setFont(smallFont.deriveFont(Font.BOLD));
        label.setForeground(dimColor);
        boldScalableLabels.add(label);
        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        titleRow.setOpaque(false);
        titleRow.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(8), 0, JBUI.scale(2), 0));
        titleRow.add(label);

        JSeparator divider = new JSeparator(SwingConstants.HORIZONTAL);
        divider.setForeground(JBUI.CurrentTheme.ToolWindow.borderColor());
        divider.setOpaque(false);
        divider.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(
            0, JBUI.scale(8), JBUI.scale(2), JBUI.scale(8)));
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(titleRow);
        header.add(divider);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
        return header;
    }

    private JPanel createSectionHeaderWithSuffix(String title, String suffix) {
        JPanel header = createSectionHeader(title);
        JLabel suffixLabel = new JLabel(suffix);
        suffixLabel.setFont(smallFont);
        suffixLabel.setForeground(dimColor);
        scalableLabels.add(suffixLabel);
        if (header.getComponentCount() > 0 && header.getComponent(0) instanceof JPanel titleRow) {
            titleRow.add(suffixLabel);
        } else {
            header.add(suffixLabel);
        }
        return header;
    }

    private JPanel addStatRow(JPanel grid, int row, String labelText, JLabel value) {
        return addStatRowWithLabel(grid, row, new JLabel(labelText), value);
    }

    private JPanel addStatRowWithLabel(JPanel grid, int row, JLabel label, JLabel value) {
        label.setFont(smallFont);
        label.setForeground(UIManager.getColor("Label.foreground"));
        value.setFont(smallFont);
        scalableLabels.add(label);
        scalableLabels.add(value);
        value.setHorizontalAlignment(SwingConstants.RIGHT);

        JPanel rowPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        rowPanel.setOpaque(false);
        rowPanel.add(label, BorderLayout.WEST);
        rowPanel.add(value, BorderLayout.CENTER);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, JBUI.scale(4), 0);
        grid.add(rowPanel, gbc);
        return rowPanel;
    }

    private void refresh() {
        SessionStatsSnapshot snap = timerPanel.getSessionSnapshot();
        BillingDisplayData bill = billing.getBillingDisplayData();

        refreshTurnSection(snap);
        refreshSessionStats(snap);
        refreshBilling(bill);

        revalidate();
        repaint();
    }

    private void refreshTurnSection(SessionStatsSnapshot snap) {
        boolean hasTurn = snap.isRunning() || snap.getSessionTurnCount() > 0;
        if (!hasTurn) {
            turnSection.setVisible(false);
            return;
        }
        turnSection.setVisible(true);
        turnHeaderLabel.setText(snap.isRunning() ? "Active turn" : "Last turn");

        turnTimeValue.setText(TimerDisplayFormatter.INSTANCE.formatElapsedTime(snap.getTurnElapsedSec()));

        if (snap.getMultiplierMode()) {
            turnTokensRowLabel.setText(LABEL_PREMIUM_REQ);
            turnTokensValue.setText(BillingCalculator.INSTANCE.formatPremium(snap.getTurnPremiumRequests()));
            turnTokensRow.setVisible(true);
        } else {
            turnTokensRowLabel.setText(LABEL_TOKENS);
            turnTokensValue.setText(
                TimerDisplayFormatter.INSTANCE.formatTokenCount(snap.getTurnInputTokens()) +
                    TOKENS_IN_OUT_SEP +
                    TimerDisplayFormatter.INSTANCE.formatTokenCount(snap.getTurnOutputTokens()) +
                    TOKENS_OUT_SUFFIX);
            turnTokensRow.setVisible(true);
        }
    }

    private void refreshSessionStats(SessionStatsSnapshot snap) {
        timeValue.setText(TimerDisplayFormatter.INSTANCE.formatElapsedTime(snap.getSessionTotalTimeSec()));
        int turns = snap.getSessionTurnCount();
        turnsValue.setText(String.valueOf(turns));
        turnsRow.setVisible(turns > 0);

        if (snap.getMultiplierMode()) {
            tokensRowLabel.setText(LABEL_PREMIUM_REQ);
            tokensValue.setText(BillingCalculator.INSTANCE.formatPremium(snap.getLocalSessionPremiumRequests()));
            tokensRow.setVisible(true);
        } else {
            tokensRowLabel.setText(LABEL_TOKENS);
            tokensValue.setText(
                TimerDisplayFormatter.INSTANCE.formatTokenCount(snap.getSessionInputTokens()) +
                    TOKENS_IN_OUT_SEP +
                    TimerDisplayFormatter.INSTANCE.formatTokenCount(snap.getSessionOutputTokens()) +
                    TOKENS_OUT_SUFFIX);
            tokensRow.setVisible(true);
        }
    }

    private void refreshBilling(BillingDisplayData bill) {
        boolean hasBilling = bill.getEntitlement() > 0 || bill.getUnlimited();
        billingSection.setVisible(hasBilling);

        if (bill.getUnlimited()) {
            usageValue.setText("Unlimited");
            usageRow.setVisible(true);
            remainingRow.setVisible(false);
        } else if (bill.getEntitlement() > 0) {
            usageValue.setText(bill.getEstimatedUsed() + " / " + bill.getEntitlement());
            usageRow.setVisible(true);
            int remaining = bill.getEstimatedRemaining();
            if (remaining < 0) {
                remainingValue.setText("Over by " + (-remaining));
                remainingValue.setForeground(JBUI.CurrentTheme.Label.errorForeground());
            } else {
                remainingValue.setText(String.valueOf(remaining));
                remainingValue.setForeground(UIManager.getColor("Label.foreground"));
            }
            remainingRow.setVisible(true);
        } else {
            usageRow.setVisible(false);
            remainingRow.setVisible(false);
        }

        if (!bill.getResetDate().isEmpty()) {
            try {
                LocalDate reset = LocalDate.parse(bill.getResetDate(), DateTimeFormatter.ISO_LOCAL_DATE);
                resetsValue.setText(reset.format(RESET_DATE_FMT));
                resetsRow.setVisible(hasBilling);
            } catch (DateTimeParseException ignored) {
                resetsRow.setVisible(false);
            }
        } else {
            resetsRow.setVisible(false);
        }
    }

}
