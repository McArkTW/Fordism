package com.hp.vcosmos.foundry.skill;

import java.util.List;

/**
 * A skill as the browser sees it. The list rows carry name/description/enabled; opening one adds
 * its {@code SKILL.md} and the files beside it.
 */
public record SkillView(String name, String description, boolean enabled, Boolean exists,
                        String content, List<String> files) {

    public static SkillView summary(String name, String description, boolean enabled) {
        return new SkillView(name, description, enabled, null, null, null);
    }
}
