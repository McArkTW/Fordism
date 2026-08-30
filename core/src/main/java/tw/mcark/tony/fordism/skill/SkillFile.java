package tw.mcark.tony.fordism.skill;

/**
 * One file inside a skill, as the browser reads it.
 *
 * <p>A skill folder is whatever the user uploaded or a plugin shipped, so it can hold a PNG, a
 * compiled binary or a 40 MB fixture beside its {@code SKILL.md}. Neither is an error — the page
 * asked what is in the folder and is entitled to an answer — so both come back {@code 200} with a
 * flag saying why the content is empty, rather than as a failure the client has to special-case.
 *
 * @param binary    the file holds a NUL byte in its first block, so it is not text and no content
 *                  is sent
 * @param truncated the file is longer than {@link SkillStore#MAX_FILE_BYTES} and {@code content}
 *                  stops there
 */
public record SkillFile(String path, long size, boolean binary, boolean truncated, String content) {
}
