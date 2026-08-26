package com.hp.vcosmos.foundry.workspace;

import java.util.List;

/** An Agent Template as the browser sees it. {@code exists} is absent on the list rows. */
public record TemplateView(String id, String name, Boolean exists, String agentProfile, String model,
                           List<String> skills, List<String> credentials, String instructions) {

    /** The list shape: identity only — the page fetches the rest when you open one. */
    public static TemplateView summary(AgentTemplate template) {
        return new TemplateView(template.id(), template.name(), null, null, null, null, null, null);
    }
}
