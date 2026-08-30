package tw.mcark.tony.fordism.auth;

/**
 * The claims of an {@code id_token} whose signature, issuer, audience, expiry and nonce all held.
 *
 * <p>Deliberately not an {@link ExternalIdentity}: which claim carries the person's address, and
 * what makes that address trustworthy, differs by issuer and is the provider's business. Google
 * says {@code email} plus {@code email_verified}; Entra says {@code preferred_username} and has no
 * verified flag at all, because a tenant-issued account IS the verification. A verifier that
 * decided this for both would have to encode one issuer's policy as the other's.
 *
 * <p>The components are the union of what the two issuers put in a token and Fordism reads. A field
 * an issuer does not mint arrives blank, or false.
 */
public record VerifiedClaims(String subject, String email, boolean emailVerified,
                             String preferredUsername, String displayName) {}
