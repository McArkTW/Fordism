package tw.mcark.tony.fordism.auth;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238), for a second factor on local accounts.
 *
 * <p>Chosen over an email code because it needs nothing outbound — Fordism has no mail, and an
 * install on a locked-down network is exactly where a second factor matters most. The secret is
 * shared once, at enrolment, as an {@code otpauth://} URI a phone app scans; after that the phone
 * and the server derive the same six digits from the secret and the clock, and nothing travels.
 *
 * <p>HMAC-SHA1 from the JDK ({@code javax.crypto.Mac}) — the algorithm every authenticator app
 * implements, and no new dependency. A ±1 step window absorbs the clock skew between a phone and
 * a server; a wider window would trade brute-force resistance for tolerance nobody needs.
 */
public final class Totp {

    /** RFC 6238 defaults, and what every authenticator app assumes unless the URI says otherwise. */
    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;
    private static final int SKEW_STEPS = 1;

    /** 160 bits, the SHA-1 block, Base32 with no padding — the shape Google Authenticator expects. */
    private static final int SECRET_BYTES = 20;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** A fresh Base32 secret to enrol an account with. */
    public static String newSecret() {
        byte[] raw = new byte[SECRET_BYTES];
        RANDOM.nextBytes(raw);
        return base32Encode(raw);
    }

    /**
     * The {@code otpauth://} URI an authenticator app scans. The label carries the issuer and the
     * account so a person with several tokens can tell them apart; the issuer parameter is repeated
     * because some apps read the label and some read the parameter.
     */
    public static String provisioningUri(String secret, String account, String issuer) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    /** Whether this code is valid for the secret right now, allowing one step of clock skew. */
    public static boolean verify(String secret, String code) {
        return verifyAt(secret, code, System.currentTimeMillis() / 1000L);
    }

    /** Seam for tests: verify against a supplied epoch-second rather than the wall clock. */
    static boolean verifyAt(String secret, String code, long epochSeconds) {
        if (secret == null || secret.isBlank() || code == null) {
            return false;
        }
        String trimmed = code.trim();
        if (trimmed.length() != DIGITS) {
            return false;
        }
        byte[] key = base32Decode(secret);
        long step = epochSeconds / PERIOD_SECONDS;
        for (long offset = -SKEW_STEPS; offset <= SKEW_STEPS; offset++) {
            if (constantTimeEquals(trimmed, codeFor(key, step + offset))) {
                return true;
            }
        }
        return false;
    }

    /** The exact code for the step containing this epoch-second — a deterministic seam for tests. */
    static String codeForEpoch(String secret, long epochSeconds) {
        return codeFor(base32Decode(secret), epochSeconds / PERIOD_SECONDS);
    }

    private static String codeFor(byte[] key, long step) {
        byte[] counter = new byte[8];
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (step & 0xff);
            step >>>= 8;
        }
        byte[] hash = hmacSha1(key, counter);
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int modulo = binary % (int) Math.pow(10, DIGITS);
        return String.format(Locale.ROOT, "%0" + DIGITS + "d", modulo);
    }

    private static byte[] hmacSha1(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no HmacSHA1 — TOTP cannot be used", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("the stored TOTP secret is not a usable key", e);
        }
    }

    /** Length-independent compare so a wrong code cannot be timed digit by digit. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(BASE32.charAt((buffer >> bits) & 0x1f));
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    private static byte[] base32Decode(String secret) {
        String clean = secret.trim().replace("=", "").toUpperCase(Locale.ROOT);
        int buffer = 0;
        int bits = 0;
        byte[] out = new byte[clean.length() * 5 / 8];
        int index = 0;
        for (int i = 0; i < clean.length(); i++) {
            int value = BASE32.indexOf(clean.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("stored TOTP secret is not Base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out[index++] = (byte) ((buffer >> bits) & 0xff);
            }
        }
        return out;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
