package com.beomsu.becommerce.shared.crypto;

/** 필드 암호화 추상화. 구현을 갈아끼워 데모 키 → KMS envelope로 전환한다. */
public interface FieldCipher {
    String encrypt(String plaintext);
    String decrypt(String ciphertext);
}
