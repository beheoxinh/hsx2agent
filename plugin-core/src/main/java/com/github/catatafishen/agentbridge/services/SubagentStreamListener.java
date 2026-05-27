package com.github.catatafishen.agentbridge.services;

import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

public interface SubagentStreamListener {
    @Topic.ProjectLevel
    Topic<SubagentStreamListener> TOPIC = Topic.create("SubagentStream", SubagentStreamListener.class);

    void onStreamChunk(@NotNull String subagentId, boolean isThinking, @NotNull String content);
}
