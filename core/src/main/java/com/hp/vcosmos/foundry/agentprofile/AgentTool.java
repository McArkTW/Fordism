package com.hp.vcosmos.foundry.agentprofile;

import com.google.gson.annotations.SerializedName;

/**
 * Which CLI drives a task, and therefore which dialect its model speaks.
 *
 * <p>One agent image bakes both, so this is the only thing that decides: {@link #CLAUDE_CODE} is
 * given {@code ANTHROPIC_BASE_URL}/{@code ANTHROPIC_AUTH_TOKEN} and runs {@code claude -p};
 * {@link #QWEN_CODE} is given {@code OPENAI_*} and runs {@code qwen --yolo -p}.
 *
 * <p>The hyphenated wire names are persisted in each profile's JSON and accepted from the API, so
 * they are what the annotation and {@link #wireName()} both say. A profile written before this
 * field existed has no tool at all — {@link #from} reads that, and anything else it cannot place,
 * as claude-code, which is the default the store applied before.
 */
public enum AgentTool {
    @SerializedName("claude-code") CLAUDE_CODE("claude-code"),
    @SerializedName("qwen-code") QWEN_CODE("qwen-code");

    private final String wireName;

    AgentTool(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** The stored or submitted token; blank, absent or unrecognised means claude-code. */
    public static AgentTool from(String token) {
        if (token == null || token.isBlank()) {
            return CLAUDE_CODE;
        }
        String trimmed = token.trim().toLowerCase();
        return QWEN_CODE.wireName.equals(trimmed) ? QWEN_CODE : CLAUDE_CODE;
    }
}
