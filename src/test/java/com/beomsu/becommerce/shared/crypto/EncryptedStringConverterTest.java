package com.beomsu.becommerce.shared.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptedStringConverterTest {

    private final EncryptedStringConverter converter =
            new EncryptedStringConverter(new AesGcmFieldCipher("0123456789abcdef0123456789abcdef"));

    @Test
    @DisplayName("DB 저장(암호화) → 조회(복호화) 왕복")
    void roundTrip() {
        String db = converter.convertToDatabaseColumn("계좌-999");
        assertThat(db).isNotEqualTo("계좌-999");
        assertThat(converter.convertToEntityAttribute(db)).isEqualTo("계좌-999");
    }
}
