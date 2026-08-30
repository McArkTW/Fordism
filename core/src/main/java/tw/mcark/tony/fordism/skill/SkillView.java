package tw.mcark.tony.fordism.skill;

import java.util.List;

/**
 * A skill as the browser sees it. The list rows carry name/description/enabled plus the three
 * fields the page sorts and groups on; opening one adds its {@code SKILL.md} and the files beside
 * it.
 *
 * <p>{@code owner} is the plugin whose folder the skill sits in, or null when nobody but the user
 * put it there. It is <em>derived</em> at list time from the plugin registry rather than stored on
 * the skill: the registry is already the only record of who owns a folder, and a second copy could
 * disagree with it after a plugin is removed.
 *
 * <p>The nullable fields are absent from the JSON rather than null — gson omits them — so a list
 * row stays a list row and a detail response is the one that carries content.
 */
public record SkillView(String name, String description, boolean enabled, String owner,
                        Integer fileCount, String updatedAt, Boolean exists,
                        String content, List<String> files) {

    public static SkillView summary(String name, String description, boolean enabled,
                                    int fileCount, String updatedAt) {
        return new SkillView(name, description, enabled, null, fileCount, updatedAt, null, null, null);
    }

    /** The same row, attributed to the plugin that owns its folder. */
    public SkillView ownedBy(String plugin) {
        return new SkillView(name, description, enabled, plugin, fileCount, updatedAt,
                exists, content, files);
    }
}
