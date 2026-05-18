package com.github.catatafishen.agentbridge.session.db;

import com.intellij.util.messages.Topic;

/**
 * Listener for conversation history changes (deletions, etc.).
 */
public interface ConversationListener {

    @Topic.ProjectLevel
    Topic<ConversationListener> TOPIC = Topic.create("ConversationHistoryChanged", ConversationListener.class);

    /**
     * Called when history is modified (e.g. sessions deleted).
     *
     * @param allHistoryCleared true if EVERYTHING was deleted (including current session)
     */
    default void historyChanged(boolean allHistoryCleared) {
    }

    /**
     * Called when the active connection state of the agent changes.
     *
     * @param connected true if an agent is now connected
     */
    default void connectionChanged(boolean connected) {
    }
}
