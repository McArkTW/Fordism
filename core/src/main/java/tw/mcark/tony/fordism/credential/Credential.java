package tw.mcark.tony.fordism.credential;

/**
 * A stored credential: an environment-variable name, its value, and a note saying what it is for.
 *
 * <p>{@code value} is write-only — read by the launcher, never by anything browser-facing. Which
 * agents receive it is not recorded here: an Agent Template declares the credentials it needs.
 */
public record Credential(String key, String value, String note, long updatedAt) {

    public boolean hasValue() {
        return value != null && !value.isBlank();
    }
}
