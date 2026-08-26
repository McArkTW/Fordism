package com.hp.vcosmos.foundry.launch;

import com.hp.vcosmos.foundry.agentprofile.AgentTool;

/**
 * Everything the launcher needs to point an agent at a model: where to call, what to authenticate
 * with, which CLI drives the task, and which model to ask for.
 *
 * <p>{@code tool} decides the dialect — {@code claude-code} speaks Anthropic, {@code qwen-code}
 * speaks OpenAI-chat — so it selects both the entrypoint branch and the environment variable names.
 */
public record AgentBackend(String baseUrl, String authToken, AgentTool tool, String model) {

    public boolean isQwenCode() {
        return tool == AgentTool.QWEN_CODE;
    }
}
