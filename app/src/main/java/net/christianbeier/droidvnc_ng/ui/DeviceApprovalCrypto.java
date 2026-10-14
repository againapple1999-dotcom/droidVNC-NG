/*
 * Copyright (C) 2026. Distributed under GPL-2.0 (see COPYING).
 *
 * Host-side, hardware-backed (where supported) proof that the Android device
 * locally approved a particular time-limited request.  A Firestore status
 * field by itself is NOT proof of a host approval when devices share one UID.
 */
package net.christianbeier.droidvnc_ng.ui;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

final class DeviceApprovalCrypto {
    private DeviceApprovalCrypto() {}

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS_PREFIX = "remote_assistance_approval_v1_";

    private static String alias(String deviceId) {
        return ALIAS_PREFIX + deviceId;
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        return store;
    }

    static String enrollmentPublicKey(String deviceId) throws Exception {
        KeyStore store = keyStore();
        String alias = alias(deviceId);
        if (!store.containsAlias(alias)) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
            gen.initialize(new KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build());
            gen.generateKeyPair();
            store = keyStore();
        }
        Certificate certificate = store.getCertificate(alias);
        if (certificate == null) {
            throw new IllegalStateException("Device approval key unavailable");
        }
        // X.509 DER SubjectPublicKeyInfo, base64 encoded. Private key never leaves AndroidKeyStore.
        return Base64.encodeToString(certificate.getPublicKey().getEncoded(), Base64.NO_WRAP);
    }

    static String payload(String userId, String deviceId, String requestId,
                          String decision, long expiresAtMillis) {
        return "remote-assistance-approval-v1\n" + userId + "\n" + deviceId
            + "\n" + requestId + "\n" + decision + "\n" + expiresAtMillis;
    }

    static String sign(String userId, String deviceId, String requestId,
                       String decision, long expiresAtMillis) throws Exception {
        enrollmentPublicKey(deviceId);
        PrivateKey key = (PrivateKey) keyStore().getKey(alias(deviceId), null);
        if (key == null) throw new IllegalStateException("Device signing key missing");
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(payload(userId, deviceId, requestId, decision, expiresAtMillis)
            .getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP);
    }
}
