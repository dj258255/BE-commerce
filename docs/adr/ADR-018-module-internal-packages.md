# ADR-018. 모듈 루트에는 API만 두고 나머지는 `internal`로 내린다

- 상태: 채택
- 관련: `ModularityTests`, 이슈 #7 / PR #8

## 문제

모듈 루트가 평평했다. `payment` 21개, `order` 23개, `settlement` 19개가 한 폴더에 있었고,
**무엇이 밖에 내주는 것이고 무엇이 내부 구현인지 구분이 안 됐다.**

재보니 `order`는 23개 중 22개가, `payment`는 21개 중 11개가 **모듈 밖에서 아무도 안 쓰는 것**이었다.

## 대안

**① 계층으로 나눈다** (`presentation` · `application` · `domain` · `repository`). 기각.

- Spring Modulith 가 권하지 않는다. package-by-layer 는 경계가 없어 서로의 내부를 참조하게 된다
- **캡슐화를 구조적으로 막는다.** `PaymentRepository`가 `payment.repository`에,
  `PaymentService`가 `payment.application`에 있으면 서로 다른 패키지라 `public` 이어야 한다.
  같은 패키지면 `package-private`으로 둘 수 있다
- 전부 하위 패키지가 되므로 **루트가 API라는 표현이 사라진다.** 되살리려면 `@NamedInterface`를
  모든 모듈에 붙여야 하는데, 얻는 것은 익숙한 폴더 이름뿐이다

**② 그대로 둔다.** 기각. 21개에서는 루트를 봐도 약속이 안 읽힌다.

**③ `internal`로 내린다.** 채택. Modulith 문서가 정한 관례다.

> 모듈의 base package 가 API 패키지이고, 다른 모듈에서 들어오는 의존을 허용하는 유일한 패키지다.
> `order.internal` 같은 패키지는 내부로 보며 다른 모듈에서 참조해서는 안 된다

## 기준

`import com.beomsu.becommerce.<모듈>.<클래스>;`가 **그 모듈 폴더 밖에서** 한 번이라도 나오는가.
안 나오면 `internal`이다. 126개가 해당됐다.

```
order 23→1   payment 21→10   settlement 19→2   reconciliation 19→5
ledger 11→1  escrow 11→3     auth 10→3         subscription 9→0
wallet 9→2   point 9→2       fraud 8→0         dispute 8→2
```

`payment` 루트에 남은 10개가 이 모듈의 약속이다. `PaymentConfirmedEvent`(밖 11곳),
`PaymentService`(10), `PaymentCanceledEvent`(8), `PaymentException`(7), `ApprovalOutcome`(4),
`PaymentStatus`(3), `PaymentTimelineFacts`(3), `StuckPaymentInfo`(2), `PaymentDetailView`(2),
`ConfirmResult`(1).

`subscription` · `fraud`는 0이 됐다. 아무도 안 쓰는 잎 모듈이라는 뜻이고, 사실이다.

## 옮기다 알게 된 것

`ModularityTests`가 두 번 잡았다. **어떤 클래스가 모듈의 API 인지를 폴더를 옮겨 보고서야 알았다.**

```
assist.draft.DraftService          → reconciliation.cause.CauseSuggestion
order.recovery.CheckoutRecoveryService → payment.recovery.StuckPaymentInfo
```

`CauseSuggestion` · `ResolveCause`는 다른 모듈이 쓰는 공개 어휘였고, `StuckPaymentInfo`는
`payment`가 `order`에 내주는 타입이었다. 셋 다 루트로 되돌렸다.

## 남는 것

**`internal` 안에도 `public`이 있다.** 같은 모듈의 API가 참조하는 자리다. Modulith 문서가 짚는다.

> 구현 컴포넌트가 internal 패키지에 있어도 `public` 타입이면 다른 패키지에서 참조할 수 있고,
> 자바 컴파일러는 이걸 막지 못한다

컴파일러 대신 `allowedDependencies`가 막는다. 넓힌 자리마다 선언 위에 그 사유를 적었다.
테스트는 대응 패키지로 옮겨 `package-private`을 최대한 지켰다.

**루트에 남는 둘.** `BeCommerceApplication`과 `SecurityConfig`. 후자는 `auth` · `ratelimit` · `member`를
가로질러 조립하는 앱 껍데기라 어느 모듈에도 안 속한다. `auth`로 옮기면 인증 모듈이 유입 제어를
의존하게 된다.
