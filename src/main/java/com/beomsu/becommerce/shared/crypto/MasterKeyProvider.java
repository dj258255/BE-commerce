package com.beomsu.becommerce.shared.crypto;

import javax.crypto.SecretKey;

/**
 * 마스터키(KEK, Key Encryption Key) 공급자 — KMS 추상화.
 *
 * <p>Envelope encryption에서 데이터는 매번 새 <b>DEK(Data Encryption Key)</b>로 암호화하고,
 * 그 DEK를 여기서 공급하는 <b>KEK(마스터키)</b>로 감싸(wrap) 함께 저장한다. KEK는 절대 밖으로
 * 꺼내지 않고 wrap/unwrap만 수행하는 게 원칙이므로, 실 운영에서는 이 인터페이스를
 * <b>AWS KMS·HashiCorp Vault</b> 등 실 KMS 구현으로 교체할 수 있는 자리로 둔다.
 * (로컬 데모는 프로퍼티로 주입한 KEK를 쓰는 {@link PropertyMasterKeyProvider}.)
 *
 * <p>키는 <b>버전</b>으로 식별한다. 로테이션 시 새 버전을 current로 올리고, 옛 버전 키는
 * 옛 암호문을 unwrap(재-wrap)할 수 있게 남겨둔다. 암호문에 버전을 실어(prefix) 복호화 때
 * 어느 KEK로 wrap됐는지 알 수 있다.
 */
public interface MasterKeyProvider {

    /** 현재 wrap에 쓸 KEK 버전(예: {@code "v1"}). 새로 암호화하는 DEK는 이 버전으로 감싼다. */
    String currentVersion();

    /**
     * 주어진 버전의 KEK를 반환한다. 등록되지 않은 버전이면 예외.
     *
     * @throws IllegalArgumentException 알 수 없는 버전
     */
    SecretKey keyFor(String version);
}
