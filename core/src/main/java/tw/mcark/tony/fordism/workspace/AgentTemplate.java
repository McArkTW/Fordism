package tw.mcark.tony.fordism.workspace;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * An agent template: the preset a workflow step runs with. Identity is {@code id} (a UUID, the
 * on-disk folder); {@code name} is a mutable, unique label (rename edits the field, id unchanged).
 * {@code agentProfile}, {@code skills} and {@code credentials} are references by name; {@code model}
 * rides from the profile.
 *
 * <p>{@code credentials} is the grant: every task run from this template receives exactly these
 * environment variables, so an agent that only reads a work item never holds a token that can push.
 *
 * <p>{@code instructions} is standing guidance for every task this template runs — staged into the
 * workspace and prepended to the agent's prompt, so it applies whichever agent CLI drives the task.
 */
public record AgentTemplate(String id, String name, String agentProfile,
        String model, List<String> skills, List<String> credentials,
        @SerializedName(value = "instructions", alternate = {"memory"}) String instructions) {
    public AgentTemplate {
        skills = skills == null ? List.of() : List.copyOf(skills);
        credentials = credentials == null ? List.of() : List.copyOf(credentials);
    }
}
