package com.tradeanalysis.strategy.notify;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * The Web Push wire format, with the JDK's own crypto: payload encryption (RFC 8291, "aes128gcm" content coding of
 * RFC 8188) and the VAPID authorization header (RFC 8292, an ES256 JWT). No state; {@link PushService} sends.
 */
final class WebPush {

    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    static final Base64.Decoder B64D = Base64.getUrlDecoder();   // accepts input with or without padding

    /** Push services the browsers use. Only these get requests, so a user can't make the server call other hosts. */
    private static final Set<String> PUSH_HOSTS = Set.of("fcm.googleapis.com", "android.googleapis.com",
            "updates.push.services.mozilla.com", "web.push.apple.com");
    private static final int RECORD_SIZE = 4096;

    private static final ECParameterSpec P256;
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            P256 = p.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private WebPush() {}

    /** Whether the endpoint is an https URL of a known push service. */
    static boolean allowedEndpoint(String endpoint) {
        try {
            URI u = URI.create(endpoint);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(u.getScheme())
                    && (PUSH_HOSTS.contains(host) || host.endsWith(".notify.windows.com") || host.endsWith(".push.apple.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static KeyPair newKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The uncompressed point (0x04 ‖ X ‖ Y, 65 bytes): the form browsers and VAPID use. */
    static byte[] raw(ECPublicKey k) {
        byte[] out = new byte[65];
        out[0] = 4;
        put32(k.getW().getAffineX(), out, 1);
        put32(k.getW().getAffineY(), out, 33);
        return out;
    }

    /** The private scalar as 32 bytes. */
    static byte[] raw(ECPrivateKey k) {
        byte[] out = new byte[32];
        put32(k.getS(), out, 0);
        return out;
    }

    static ECPublicKey publicKey(byte[] raw) {
        if (raw.length != 65 || raw[0] != 4) throw new IllegalArgumentException("Not an uncompressed P-256 point");
        try {
            BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
            BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a valid P-256 public key", e);
        }
    }

    static ECPrivateKey privateKey(byte[] raw) {
        if (raw.length != 32) throw new IllegalArgumentException("A P-256 private key is 32 bytes");
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a valid P-256 private key", e);
        }
    }

    /**
     * Encrypts a payload for one browser (RFC 8291): ECDH with a fresh key pair, HKDF with the browser's auth secret,
     * then one AES-128-GCM record. Returns the request body (header with salt and our public key, then the record).
     */
    static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret) {
        if (authSecret.length != 16) throw new IllegalArgumentException("The auth secret is 16 bytes");
        if (payload.length > RECORD_SIZE - 16 - 1 - 86) throw new IllegalArgumentException("Payload too large for one push");
        try {
            KeyPair as = newKeyPair();
            byte[] asPublic = raw((ECPublicKey) as.getPublic());
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(as.getPrivate());
            ka.doPhase(publicKey(uaPublic), true);
            byte[] ecdh = ka.generateSecret();

            byte[] prkKey = hmac(authSecret, ecdh);
            byte[] ikm = hmac(prkKey, ascii("WebPush: info\0"), uaPublic, asPublic, new byte[]{1});
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, ascii("Content-Encoding: aes128gcm\0"), new byte[]{1}), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, ascii("Content-Encoding: nonce\0"), new byte[]{1}), 12);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] plain = Arrays.copyOf(payload, payload.length + 1);
            plain[payload.length] = 2;   // delimiter of the last (only) record, no padding
            byte[] cipher = c.doFinal(plain);

            return ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + cipher.length)
                    .put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).put(cipher)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Push encryption failed", e);
        }
    }

    /** "vapid t=…, k=…": a JWT for the push service's origin, valid 12 hours, signed with the server's key. */
    static String vapidHeader(String endpoint, ECPrivateKey key, String publicKeyB64, String subject, Instant now) {
        URI u = URI.create(endpoint);
        String aud = u.getScheme() + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .audience(aud).subject(subject).expirationTime(Date.from(now.plus(Duration.ofHours(12)))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new ECDSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("VAPID signing failed", e);
        }
        return "vapid t=" + jwt.serialize() + ", k=" + publicKeyB64;
    }

    private static byte[] hmac(byte[] key, byte[]... parts) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] p : parts) mac.update(p);
        return mac.doFinal();
    }

    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    /** Writes an unsigned big integer right-aligned into 32 bytes at off (drops BigInteger's sign byte). */
    private static void put32(BigInteger v, byte[] out, int off) {
        byte[] b = v.toByteArray();
        int len = Math.min(b.length, 32);
        System.arraycopy(b, b.length - len, out, off + 32 - len, len);
    }
}
