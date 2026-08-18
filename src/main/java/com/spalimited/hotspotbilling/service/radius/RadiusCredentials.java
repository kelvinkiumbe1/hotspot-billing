package com.spalimited.hotspotbilling.service.radius;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Getting the password out of an Access-Request.
 *
 * <p>Three ways a NAS can present one, and the difference matters:
 *
 * <ul>
 *   <li><b>PAP</b> — the password, obscured by XOR against MD5 of the shared
 *       secret. Recoverable by us, and by anyone who knows the secret. This is
 *       what hotspot logins almost always use.
 *   <li><b>CHAP</b> — the NAS sends a hash of a challenge and the password. We
 *       can only check it by hashing our own copy the same way, which means we
 *       must hold the password in the clear. Nothing is recoverable from the
 *       packet itself.
 *   <li><b>Neither</b> — an EAP or MS-CHAP login we do not implement. Rejected
 *       plainly rather than silently treated as a wrong password, because the
 *       operator needs to know their NAS is configured for something this
 *       server does not speak.
 * </ul>
 */
final class RadiusCredentials {

    private RadiusCredentials() {
    }

    /** What kind of proof the request carried. */
    enum Kind { PAP, CHAP, UNSUPPORTED }

    static Kind kindOf(RadiusPacket packet) {
        if (packet.raw(RadiusPacket.USER_PASSWORD).isPresent()) {
            return Kind.PAP;
        }
        return packet.raw(RadiusPacket.CHAP_PASSWORD).isPresent() ? Kind.CHAP : Kind.UNSUPPORTED;
    }

    /**
     * Recovers a PAP password.
     *
     * <p>The NAS split it into 16-byte blocks and XORed each against
     * MD5(secret + previous block), starting from the request authenticator.
     * Undoing that is the same walk in reverse.
     *
     * <p>A wrong shared secret does not fail here — it yields plausible-looking
     * bytes that simply do not match any password. That is why a mistyped
     * secret shows up as "wrong password" rather than as a configuration error,
     * and why the operator has to be told about it in those terms.
     */
    static String decodePap(byte[] encrypted, byte[] requestAuthenticator, String secret) {
        if (encrypted.length == 0 || encrypted.length % 16 != 0 || encrypted.length > 128) {
            return null;
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[encrypted.length];
        byte[] previous = requestAuthenticator;

        for (int block = 0; block < encrypted.length; block += 16) {
            byte[] toHash = new byte[secretBytes.length + 16];
            System.arraycopy(secretBytes, 0, toHash, 0, secretBytes.length);
            System.arraycopy(previous, 0, toHash, secretBytes.length, 16);
            byte[] pad = RadiusPacket.md5(toHash);

            for (int i = 0; i < 16; i++) {
                out[block + i] = (byte) (encrypted[block + i] ^ pad[i]);
            }
            previous = Arrays.copyOfRange(encrypted, block, block + 16);
        }

        // The password was null-padded up to a block boundary; the padding is
        // not part of it. Trailing NULs are stripped rather than the string cut
        // at the first one, so a password is never silently truncated.
        int end = out.length;
        while (end > 0 && out[end - 1] == 0) {
            end--;
        }
        return new String(out, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * Checks a CHAP response against the password we hold.
     *
     * <p>CHAP-Password is one identifier byte followed by MD5(id + password +
     * challenge). The challenge is either its own attribute or, when the NAS
     * omits it, the request authenticator.
     */
    static boolean chapMatches(byte[] chapPassword, byte[] challenge, String password) {
        if (chapPassword == null || chapPassword.length != 17 || password == null) {
            return false;
        }
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        byte[] toHash = new byte[1 + passwordBytes.length + challenge.length];
        toHash[0] = chapPassword[0];
        System.arraycopy(passwordBytes, 0, toHash, 1, passwordBytes.length);
        System.arraycopy(challenge, 0, toHash, 1 + passwordBytes.length, challenge.length);

        byte[] expected = RadiusPacket.md5(toHash);
        byte[] offered = Arrays.copyOfRange(chapPassword, 1, 17);
        // Constant-time: a timing difference here leaks the hash a byte at a time.
        return MessageDigest.isEqual(expected, offered);
    }
}
