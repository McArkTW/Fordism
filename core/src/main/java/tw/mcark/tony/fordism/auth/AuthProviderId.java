package tw.mcark.tony.fordism.auth;

import java.util.Locale;
import java.util.Optional;

/** The login providers this instance can offer. The wire id is the lowercase form. */
public enum AuthProviderId {
    LOCAL("local"),
    GOOGLE("google"),
    GITHUB("github");

    private final String id;

    AuthProviderId(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** The provider a path segment names; empty for anything unrecognized. */
    public static Optional<AuthProviderId> from(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (AuthProviderId provider : values()) {
            if (provider.id.equals(normalized)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }
}
