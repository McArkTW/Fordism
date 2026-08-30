package tw.mcark.tony.fordism.skill;

/**
 * A skills repo the instance mirrors: a git URL and the ref to take. Its skills are unpacked into
 * their own folder under the library, so a sync only ever replaces what that plugin put there.
 *
 * <p>{@code lastError} is the reason the last sync failed, blank when it succeeded — the page shows
 * it on the row, because a plugin that silently stopped updating looks exactly like one that is
 * up to date.
 */
public record SkillPlugin(String id, String name, String url, String ref,
                          String lastSyncedAt, String lastError) {

    public SkillPlugin synced(String at) {
        return new SkillPlugin(id, name, url, ref, at, "");
    }

    public SkillPlugin failed(String error) {
        return new SkillPlugin(id, name, url, ref, lastSyncedAt, error);
    }
}
