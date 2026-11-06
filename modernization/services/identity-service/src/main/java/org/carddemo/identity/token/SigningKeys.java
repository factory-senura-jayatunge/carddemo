package org.carddemo.identity.token;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

/** The RS256 signing key from the PKCS#8 PEM that make setup writes (D-ARCH-11). */
public final class SigningKeys {

    private final RSAKey rsaKey;

    private SigningKeys(RSAKey rsaKey) {
        this.rsaKey = rsaKey;
    }

    public static SigningKeys load(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalStateException("identity-service: no JWT signing key configured. Set "
                    + "carddemo.identity.jwt.private-key-path or CARDDEMO_JWT_KEY_FILE (make setup in the main "
                    + "checkout writes modernization/var/secrets/identity-jwt.pem).");
        }
        Path path = Path.of(file);
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("identity-service: JWT signing key not found or not readable at "
                    + path + ". Run make -C modernization setup in the main checkout, or set CARDDEMO_JWT_KEY_FILE.");
        }
        try {
            String pem = Files.readString(path, StandardCharsets.US_ASCII);
            if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
                throw new IllegalStateException("identity-service: " + path + " is not a PKCS#8 PEM private key");
            }
            String body = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey priv = (RSAPrivateCrtKey) rsa.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            RSAPublicKey pub = (RSAPublicKey) rsa.generatePublic(
                    new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
            RSAKey key = new RSAKey.Builder(pub)
                    .privateKey(priv)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint()
                    .build();
            return new SigningKeys(key);
        } catch (IOException | GeneralSecurityException | JOSEException | ClassCastException
                | IllegalArgumentException e) {
            throw new IllegalStateException("identity-service: cannot read the JWT signing key at " + path
                    + ": " + e.getClass().getSimpleName(), e);
        }
    }

    public RSAKey signingKey() {
        return rsaKey;
    }

    public String keyId() {
        return rsaKey.getKeyID();
    }

    public RSAPublicKey publicKey() {
        try {
            return rsaKey.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The JWKS document: public parts only. */
    public Map<String, Object> publicJwks() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject(true);
    }
}
