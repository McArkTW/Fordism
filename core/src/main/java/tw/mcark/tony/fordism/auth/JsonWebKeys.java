package tw.mcark.tony.fordism.auth;

import java.security.interfaces.RSAPublicKey;
import java.util.Optional;

/**
 * The public keys an issuer signs its id tokens with, by key id.
 *
 * <p>An interface because verification must be testable without reaching Google: a test supplies a
 * key it generated and signed with itself, and exercises the same code the callback runs.
 */
public interface JsonWebKeys {

    /** The key with this id, or empty when the issuer publishes no such key. */
    Optional<RSAPublicKey> find(String keyId);
}
