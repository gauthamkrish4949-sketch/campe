package com.campusone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.bouncycastle.crypto.generators.SCrypt;

public final class PasswordUtil {
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(10);

    private PasswordUtil() {}

    public static String hash(String password) {
        return BCRYPT.encode(password);
    }

    public static boolean matches(String password, String stored) {
        if (stored == null) return false;
        try {
            if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
                return BCRYPT.matches(password, stored);
            }
            if (stored.startsWith("scrypt:")) return werkzeugScrypt(password, stored);
            if (stored.startsWith("pbkdf2:")) return werkzeugPbkdf2(password, stored);
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean werkzeugScrypt(String password, String encoded) {
        // Werkzeug format: scrypt:N:r:p$salt$base64hash
        String[] pieces = encoded.split("\\$");
        if (pieces.length != 3) return false;
        String[] params = pieces[0].split(":");
        int n = Integer.parseInt(params[1]);
        int r = Integer.parseInt(params[2]);
        int p = Integer.parseInt(params[3]);
        byte[] salt = pieces[1].getBytes(StandardCharsets.UTF_8);
        byte[] expected = HexFormat.of().parseHex(pieces[2]);
        byte[] actual = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, n, r, p, expected.length);
        return MessageDigest.isEqual(actual, expected);
    }

    private static boolean werkzeugPbkdf2(String password, String encoded) throws Exception {
        // Werkzeug format: pbkdf2:sha256:iterations$salt$base64hash
        String[] pieces = encoded.split("\\$");
        if (pieces.length != 3) return false;
        String[] params = pieces[0].split(":");
        String algorithm = params[1].equalsIgnoreCase("sha256") ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA256";
        int iterations = Integer.parseInt(params[2]);
        byte[] expected = Base64.getDecoder().decode(pieces[2]);
        KeySpec spec = new PBEKeySpec(password.toCharArray(), pieces[1].getBytes(StandardCharsets.UTF_8), iterations, expected.length * 8);
        byte[] actual = SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
        return MessageDigest.isEqual(actual, expected);
    }
}
