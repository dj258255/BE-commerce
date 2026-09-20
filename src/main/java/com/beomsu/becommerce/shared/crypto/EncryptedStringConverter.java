package com.beomsu.becommerce.shared.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * 암호화 필드용 JPA 컨버터 — 엔티티 필드에 {@code @Convert(converter = EncryptedStringConverter.class)}로
 * 붙이면 저장 시 암호화, 조회 시 복호화된다.
 *
 * <p>Spring 빈으로 등록해 {@link FieldCipher}를 주입받는다(Spring Boot가 Hibernate에 빈 컨버터를 연결).
 * 계좌번호 등 민감 필드에 적용한다.
 */
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final FieldCipher cipher;

    public EncryptedStringConverter(FieldCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData);
    }
}
