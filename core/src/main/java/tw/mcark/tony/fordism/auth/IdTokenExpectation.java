package tw.mcark.tony.fordism.auth;

import java.util.Set;

/**
 * What an {@code id_token} must say about where it came from and who it was minted for.
 *
 * <p>{@code issuers} is a set because Google mints two spellings of its own name and treats them as
 * the same issuer. It is never empty and never a wildcard: an issuer check that accepts anything
 * accepts a token from an issuer whose keys this instance would then go and fetch.
 *
 * <p>{@code audience} is this instance's own client id. Without it, a token some other application
 * obtained — trivially, by registering its own OAuth client — would sign its holder in here.
 */
public record IdTokenExpectation(Set<String> issuers, String audience) {

    public IdTokenExpectation {
        issuers = Set.copyOf(issuers);
        if (issuers.isEmpty() || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("an id_token expectation needs an issuer and an audience");
        }
    }

    /** Google, which signs with either spelling of its issuer name. */
    public static IdTokenExpectation google(String clientId) {
        return new IdTokenExpectation(
                Set.of("accounts.google.com", "https://accounts.google.com"), clientId);
    }

    /**
     * Microsoft Entra ID, for one tenant.
     *
     * <p>The issuer carries the tenant's own directory id, which is why {@code FORDISM_AUTH_MICROSOFT_TENANT_ID}
     * has to be that id and not {@code common}: in the multi-tenant endpoints the issuer is only
     * known from the token's own {@code tid} claim, so checking it would be checking the token
     * against itself.
     */
    public static IdTokenExpectation microsoft(String tenantId, String clientId) {
        return new IdTokenExpectation(
                Set.of("https://login.microsoftonline.com/" + tenantId + "/v2.0"), clientId);
    }

    public boolean issuedBy(String issuer) {
        return issuers.contains(issuer);
    }
}
