package tw.mcark.tony.fordism.model.task;

/** Per-task configuration from the workflow step (model / timeout / network / attempts). */
public record TaskConfiguration(String model, int timeoutSeconds, NetworkPolicy network, int maximumAttempts) {

    /**
     * A step gets NO network unless it asks. Egress is the difference between a compromised agent
     * that can exfiltrate and one that cannot, so it is opt-in: a step declares {@code network:
     * fordism-only} to reach core, or {@code full} for the open internet. This changed in the
     * hardening release — a workflow whose step quietly relied on reaching core must now say so.
     */
    public static TaskConfiguration defaults() {
        return new TaskConfiguration("qwen3", 600, NetworkPolicy.NONE, 3);
    }
}
