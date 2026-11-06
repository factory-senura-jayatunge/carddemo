package org.carddemo.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** A throw-away RSA key in the PKCS#8 PEM form make setup writes. */
public final class TestKeys {

    private static final Path PATH = write(generate());

    private TestKeys() {
    }

    public static String path() {
        return PATH.toString();
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Path write(KeyPair pair) {
        try {
            Path file = Files.createTempFile("identity-jwt", ".pem");
            file.toFile().deleteOnExit();
            String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(pair.getPrivate().getEncoded());
            Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n",
                    StandardCharsets.US_ASCII);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
