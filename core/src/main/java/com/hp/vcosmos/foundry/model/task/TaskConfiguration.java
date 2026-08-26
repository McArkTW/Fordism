package com.hp.vcosmos.foundry.model.task;

/** Per-task configuration from the workflow step (model / timeout / network / attempts). */
public record TaskConfiguration(String model, int timeoutSeconds, NetworkPolicy network, int maximumAttempts) {
    public static TaskConfiguration defaults() {
        return new TaskConfiguration("qwen3", 600, NetworkPolicy.FOUNDRY_ONLY, 3);
    }
}
