# ADR-006. 필드 암호화를 Envelope Encryption(DEK/KEK)으로 확장한다

- 상태: 채택 (Accepted)
- 날짜: 2026-07-07
- 관련: [shared/crypto/FieldCipher](../../src/main/java/com/beomsu/BE-commerce/shared/crypto/FieldCipher.java), `AesGcmFieldCipher`, `EnvelopeFieldCipher`, `MasterKeyProvider`

## 맥락

계좌번호처럼 「개인정보의 안전성 확보조치 기준」상 암호화 의무 대상 필드는 `FieldCipher`로 암호화한다. 최초 구현 `AesGcmFieldCipher`는 프로퍼티(`app.crypto.key`)로 주입한 **단일 키**로 AES-256-GCM 암호화한다(데모). 이 방식엔 두 가지 운영상 한계가 있다.

1. **키 로테이션이 곧 전수 재암호화다.** 마스터키를 바꾸려면 그 키로 암호화된 모든 행을 복호화→재암호화해야 한다. 수백만 행이면 위험하고 느리다.
2. **키가 애플리케이션 프로세스 안에 상주한다.** 마스터키가 메모리·설정에 그대로 있으므로 유출 표면이 넓다.

`AesGcmFieldCipher`의 Javadoc은 이미 이 전환을 예고했다("인터페이스는 그대로 두고 키 공급만 KMS로"). 실서비스/금융권 표준인 **envelope encryption**으로 확장한다.

## 결정

`FieldCipher`를 구현하는 **`EnvelopeFieldCipher`**를 추가한다. 인터페이스(`encrypt`/`decrypt`)는 그대로다.

- **DEK(Data Encryption Key):** 데이터는 매번 **새 무작위 DEK**(AES-256)로 GCM 암호화한다.
- **KEK(Key Encryption Key, 마스터키):** 그 DEK를 **KEK로 감싸(wrap)** 암호문과 함께 저장한다. KEK는 `MasterKeyProvider`가 공급하며, 실 운영에서는 이 인터페이스를 **AWS KMS·Vault** 구현으로 교체해 마스터키를 프로세스 밖에 둔다. 로컬은 프로퍼티 KEK(`PropertyMasterKeyProvider`).

### 암호문 포맷 (버전 프리픽스)

```
env:{version}:{base64(wrapBlob)}:{base64(dataBlob)}
```

- `version`: DEK를 감싼 KEK의 버전. 복호화 때 어느 KEK로 unwrap할지 안다.
- `dataBlob`: 데이터 암호문 `iv(12)+ct+tag`, DEK로 GCM.
- `wrapBlob`: 감싼 DEK `iv(12)+wrappedDek+tag`, KEK로 GCM.

버전을 암호문에 실으므로, 로테이션 후에도 옛 암호문을 옛 버전 KEK로 복호화할 수 있다.

## 근거

1. **로테이션 = 재암호화가 아니라 재-wrap이다.** `rewrapToCurrent(ciphertext)`는 옛 KEK로 DEK만 unwrap해 **current KEK로 다시 wrap**하고, `dataBlob`(실제 데이터 암호문)은 **그대로** 둔다. 데이터 자체는 손대지 않으므로 수백만 행을 재암호화하는 대신 각 행의 작은 DEK(32바이트)만 재-wrap하면 된다. 이것이 envelope의 핵심 이점이다.
2. **마스터키를 프로세스 밖에 둘 수 있다.** `MasterKeyProvider`는 "버전으로 KEK를 얻는" 좁은 추상화다. 실 KMS 구현은 wrap/unwrap만 KMS에 위임하고 마스터키 원문을 노출하지 않는다.
3. **키 침해 반경 축소.** DEK는 행마다 다르므로 한 DEK가 노출돼도 그 행만 영향을 받는다.

## AesGcm과 공존: @Primary + 조건부 활성

`AesGcmFieldCipher`는 **남긴다**(기존 테스트·단일 키 경로 보존). 빈 선택은 프로퍼티로 전환한다.

- `EnvelopeFieldCipher`에 `@ConditionalOnProperty(name="app.crypto.mode", havingValue="envelope", matchIfMissing=true)` + `@Primary`.
- `application.yml`이 `app.crypto.mode: envelope`(기본)이라 envelope 빈이 만들어지고, `@Primary`라 `FieldCipher` 주입 시 우선한다. `AesGcmFieldCipher` 빈은 그대로 있으되 주입되지 않는다(`EncryptedStringConverter`는 envelope을 받는다).
- `app.crypto.mode: simple`로 두면 envelope 빈이 생성되지 않아 AesGcm이 단독 빈으로 주입된다.

즉 **설정 한 줄로** 단일 키 ↔ envelope을 전환하며, 도메인 코드·컨버터·인터페이스는 무수정이다.

## 포기한 것 / 주의

- **암호문이 길어진다.** wrap된 DEK와 버전 prefix를 함께 실으므로 단일 키 방식보다 base64 60여 바이트 + prefix만큼 길다. 민감 컬럼 길이를 넉넉히 잡아야 한다. (실제 적용: `BillingKey`·`Subscription`·`VirtualAccount`가 `@Convert(EncryptedStringConverter)`로 암호화 저장하고, 값 검색이 필요한 빌링키는 블라인드 인덱스로 동등검색한다. 컬럼 길이를 그에 맞게 잡았다.)
- **DEK-per-value.** 값마다 DEK를 새로 만들어 wrap하므로 저장 오버헤드가 있다. 대신 로테이션·침해 반경 이점을 얻는다(필드 암호화엔 타당한 트레이드오프).
- **로컬 KEK는 데모다.** 운영은 반드시 실 KMS로 `MasterKeyProvider`를 교체하고, KEK를 코드·리포지토리에 두지 않는다(`app.crypto.kek.*`는 env 오버라이드).
