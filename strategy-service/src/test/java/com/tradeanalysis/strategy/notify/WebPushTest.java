package com.tradeanalysis.strategy.notify;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebPushTest {

    /** Encrypts as the server does, then decrypts as the browser would (RFC 8291), and gets the payload back. */
    @Test
    void encryptedPayloadDecryptsWithTheBrowsersKeys() throws Exception {
        KeyPair ua = WebPush.newKeyPair();
        byte[] uaPublic = WebPush.raw((ECPublicKey) ua.getPublic());
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        byte[] payload = "{\"title\":\"🎯 Target hit\",\"body\":\"P&L +₹1,250.00\"}".getBytes(StandardCharsets.UTF_8);

        byte[] body = WebPush.encrypt(payload, uaPublic, auth);

        ByteBuffer b = ByteBuffer.wrap(body);
        byte[] salt = new byte[16]; b.get(salt);
        assertThat(b.getInt()).isEqualTo(4096);
        byte[] asPublic = new byte[b.get()]; b.get(asPublic);
        byte[] cipher = new byte[b.remaining()]; b.get(cipher);

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(ua.getPrivate());
        ka.doPhase(WebPush.publicKey(asPublic), true);
        byte[] ecdh = ka.generateSecret();
        byte[] ikm = hmac(hmac(auth, ecdh), concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic, new byte[]{1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = c.doFinal(cipher);

        assertThat(plain[plain.length - 1]).isEqualTo((byte) 2);
        assertThat(Arrays.copyOf(plain, plain.length - 1)).isEqualTo(payload);
    }

    @Test
    void vapidHeaderIsAnEs256JwtForThePushServiceOrigin() throws Exception {
        KeyPair server = WebPush.newKeyPair();
        String pub = WebPush.B64.encodeToString(WebPush.raw((ECPublicKey) server.getPublic()));
        ECPrivateKey priv = WebPush.privateKey(WebPush.raw((ECPrivateKey) server.getPrivate()));   // raw round trip

        String header = WebPush.vapidHeader("https://fcm.googleapis.com/fcm/send/abc:def", priv, pub, "mailto:ops@example.com", Instant.now());

        assertThat(header).startsWith("vapid t=").endsWith(", k=" + pub);
        SignedJWT jwt = SignedJWT.parse(header.substring("vapid t=".length(), header.indexOf(',')));
        assertThat(jwt.verify(new ECDSAVerifier(WebPush.publicKey(WebPush.B64D.decode(pub))))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("https://fcm.googleapis.com");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("mailto:ops@example.com");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isAfter(new java.util.Date());
    }

    @Test
    void onlyKnownPushServicesAreAllowed() {
        assertThat(WebPush.allowedEndpoint("https://fcm.googleapis.com/fcm/send/x")).isTrue();
        assertThat(WebPush.allowedEndpoint("https://updates.push.services.mozilla.com/wpush/v2/x")).isTrue();
        assertThat(WebPush.allowedEndpoint("http://fcm.googleapis.com/fcm/send/x")).isFalse();
        assertThat(WebPush.allowedEndpoint("https://localhost:8080/api/users")).isFalse();
        assertThat(WebPush.allowedEndpoint("https://fcm.googleapis.com.evil.example/x")).isFalse();
        assertThat(WebPush.allowedEndpoint("not a url")).isFalse();
    }

    @Test
    void rejectsBadKeys() {
        assertThatThrownBy(() -> WebPush.encrypt(new byte[1], new byte[65], new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPush.encrypt(new byte[1], WebPush.raw((ECPublicKey) WebPush.newKeyPair().getPublic()), new byte[8]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteBuffer b = ByteBuffer.allocate(Arrays.stream(parts).mapToInt(p -> p.length).sum());
        for (byte[] p : parts) b.put(p);
        return b.array();
    }
}
