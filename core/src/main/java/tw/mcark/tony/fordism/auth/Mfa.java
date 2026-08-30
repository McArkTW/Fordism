package tw.mcark.tony.fordism.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.security.SecureRandom;

/**
 * An account's second factor: the TOTP secret, and the recovery codes that get someone back in
 * when the authenticator is gone.
 *
 * <p>Both are stored the way a password is — never returned. The {@code totpSecret} is Base32 and,
 * like {@code passwordHash}, has no field in any {@code Views} shape, so no endpoint can hand it
 * back; the recovery codes are kept only as SHA-256 hashes, so the plaintext exists once, in the
 * response that enrols them, and never again.
 *
 * <p>Plain SHA-256 for the recovery codes, not the password KDF: each is 80 random bits this
 * server generated, so there is nothing to brute-force and no reason to pay a slow hash. A code is
 * single-use — {@link #redeemRecoveryCode} returns the remaining set with the used one gone — so a
 * captured-and-replayed code fails the second time.
 */
public record Mfa(String totpSecret, List<String> recoveryCodeHashes) {

    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int RECOVERY_CODE_BYTES = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    public Mfa {
        totpSecret = totpSecret == null ? "" : totpSecret;
        recoveryCodeHashes = recoveryCodeHashes == null ? List.of() : List.copyOf(recoveryCodeHashes);
    }

    public boolean enabled() {
        return !totpSecret.isBlank();
    }

    /** A freshly enrolled factor plus the plaintext recovery codes to show the person exactly once. */
    public static Enrolled enrol(String totpSecret) {
        List<String> plaintext = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = newRecoveryCode();
            plaintext.add(code);
            hashes.add(hash(normalize(code)));   // hash the normalized form so redeem matches
        }
        return new Enrolled(new Mfa(totpSecret, hashes), plaintext);
    }

    /** True if the TOTP code is valid now. */
    public boolean verifyTotp(String code) {
        return Totp.verify(totpSecret, code);
    }

    /**
     * If {@code code} is an unused recovery code, the same factor with that code spent; otherwise
     * empty. The caller persists the returned {@link Mfa}, which is how single-use is enforced —
     * a replay of the same code finds its hash already gone.
     */
    public java.util.Optional<Mfa> redeemRecoveryCode(String code) {
        if (code == null || code.isBlank()) {
            return java.util.Optional.empty();
        }
        String wanted = hash(normalize(code));
        List<String> remaining = new ArrayList<>();
        boolean found = false;
        for (String held : recoveryCodeHashes) {
            if (!found && MessageDigest.isEqual(held.getBytes(StandardCharsets.US_ASCII),
                    wanted.getBytes(StandardCharsets.US_ASCII))) {
                found = true;   // drop exactly this one
            } else {
                remaining.add(held);
            }
        }
        return found ? java.util.Optional.of(new Mfa(totpSecret, remaining)) : java.util.Optional.empty();
    }

    public int remainingRecoveryCodes() {
        return recoveryCodeHashes.size();
    }

    private static String newRecoveryCode() {
        byte[] raw = new byte[RECOVERY_CODE_BYTES];
        RANDOM.nextBytes(raw);
        // Lowercased Base32-ish via url-encoder is fine; group with a dash for legibility.
        String flat = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
                .toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return flat.substring(0, 5) + "-" + flat.substring(5, 10);
    }

    /** A recovery code compared case- and dash-insensitively, so how the person types it back does not matter. */
    private static String normalize(String code) {
        return code.trim().toLowerCase(Locale.ROOT).replace("-", "").replace(" ", "");
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no SHA-256 — recovery codes cannot be stored", e);
        }
    }

    /** A newly enrolled factor and the one-time plaintext recovery codes for it. */
    public record Enrolled(Mfa mfa, List<String> recoveryCodes) {}
}
