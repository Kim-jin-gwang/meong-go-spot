package com.meonggo.backend.auth.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

final class TestRsaKeys {
    private TestRsaKeys() {}

    static KeyPair generate(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static RSAPrivateKey privateKey(KeyPair pair) {
        return (RSAPrivateKey) pair.getPrivate();
    }

    static RSAPublicKey publicKey(KeyPair pair) {
        return (RSAPublicKey) pair.getPublic();
    }
}
