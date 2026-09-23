package com.beomsu.becommerce.assist.draft;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 내부 용어를 고객이 읽을 말로 옮기는 사전. <b>프롬프트와 검사가 같은 표를 쓴다.</b>
 *
 * <p><b>왜 필요한가</b>: 실측에서 모델이 이렇게 썼다.
 * <blockquote>대사 <b>AMOUNT_MISMATCH</b>로 인해 … 주문 상태 <b>PAID</b> 확인 중입니다</blockquote>
 * 숫자는 전부 맞았다. 그런데 고객에게 보낼 문장에 시스템 enum 이 그대로 들어 있다.
 * {@link NumericProvenanceGuard} 는 이걸 못 잡는다 — 사실이 틀린 게 아니기 때문이다.
 *
 * <p><b>지시와 검사를 둘 다 둔다.</b> 지시만 있고 검사가 없으면 모델이 지킬 때만 지켜지고,
 * 검사만 있고 지시가 없으면 반려율만 오른다. 숫자에 대해 이미 그렇게 하고 있고,
 * 용어도 같다. 번역 실무의 권고도 같은 방향이다 — <i>글로서리와 스타일 가이드를
 * 프롬프트 안에 넣고, 출하 전에 용어 문제를 잡는 검수를 따로 둔다.</i>
 *
 * <p><b>숫자와 심각도가 다르다.</b> 지어낸 숫자는 고객을 잘못 인도하므로 초안을 <b>버린다</b>.
 * 용어 누출은 창피하지만 <b>틀린 것이 아니고</b>, 상담원이 발송 전에 고칠 수 있다.
 * 그래서 버리지 않고 <b>표시하고 센다</b>. 같은 잣대로 버리면 쓸 만한 초안까지 사라진다.
 *
 * <p><b>사전은 닳는다.</b> enum 이 늘어나면 여기가 뒤처진다. 그래서 사전에 없는 것도
 * 잡도록 {@link #findJargon} 은 <b>모양</b>으로 판단한다 — 한국어 문장 안의 대문자 라틴
 * 토큰은 사실상 전부 내부 용어다.
 */
@Component
public class CustomerGlossary {

    /**
     * 내부 용어 → 고객 표현. 실측에서 실제로 샌 것들을 우선 넣었다.
     *
     * <p>순서가 있는 맵인 이유: 프롬프트에 싣는 순서가 매번 같아야 한다.
     * 순서가 흔들리면 같은 입력에 다른 프롬프트가 나가고, 결과 차이가
     * 모델 탓인지 프롬프트 탓인지 알 수 없게 된다.
     */
    private static final Map<String, String> TERMS = new LinkedHashMap<>();

    static {
        // 대사 결과
        TERMS.put("AMOUNT_MISMATCH", "결제사 기록과 금액이 다름");
        TERMS.put("INTERNAL_ONLY", "결제사 정산 내역에 아직 없음");
        TERMS.put("EXTERNAL_ONLY", "저희 기록에서 확인되지 않음");
        TERMS.put("MATCHED", "정상 확인됨");
        // 결제 상태
        TERMS.put("IN_PROGRESS", "승인 처리 중");
        TERMS.put("DONE", "승인 완료");
        TERMS.put("READY", "결제 준비");
        TERMS.put("CANCELED", "취소됨");
        // 주문 상태
        TERMS.put("PAID", "결제 완료");
        TERMS.put("PENDING_CONFIRMATION", "정산 확정 대기");
        // 원장·포인트
        TERMS.put("PAYMENT_APPROVED", "결제 승인 기록");
        TERMS.put("EARN", "적립");
        // 원인
        TERMS.put("FEE_CALCULATION_DIFF", "결제 수수료 차감");
        TERMS.put("PARTIAL_CANCEL_NOT_REFLECTED", "부분취소가 아직 반영되지 않음");
        TERMS.put("PG_FILE_DELAY", "결제사 정산 자료 도착 지연");
        TERMS.put("TIMEZONE_BOUNDARY", "정산 기준일 경계");
        TERMS.put("NET_CANCEL_TIMING", "승인 직후 취소");
        TERMS.put("INTERNAL_RECORD_LOST", "저희 기록 누락 가능성");
        TERMS.put("SUSPECTED_TAMPERING", "원인 확인 중");
        // 확신 등급 — 고객에게 나갈 말이 아니다
        TERMS.put("DECISIVE", "확인됨");
        TERMS.put("LIKELY", "추정");
        TERMS.put("WEAK", "확인 중");
    }

    /**
     * 대문자 라틴 토큰. {@code PAID}, {@code AMOUNT_MISMATCH}, {@code TOSS_PAYMENTS}.
     *
     * <p>두 글자 이상만 본다. 한 글자는 목록 기호나 단위와 섞인다.
     */
    private static final Pattern JARGON =
            Pattern.compile("\\b([A-Z][A-Z0-9]+(?:_[A-Z0-9]+)*)\\b");

    /** 대문자지만 고객 문장에 나와도 되는 것들. */
    private static final Set<String> ALLOWED = Set.of("PG", "AI", "URL", "ID", "KRW", "VAT");

    /**
     * 초안에 남은 내부 용어들. 비어 있으면 깨끗하다.
     *
     * <p>사전에 있든 없든 <b>모양으로</b> 잡는다. 사전은 enum 이 늘면 뒤처지지만
     * 모양은 안 뒤처진다.
     */
    public List<String> findJargon(String draft) {
        if (draft == null || draft.isBlank()) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher m = JARGON.matcher(draft);
        while (m.find()) {
            String token = m.group(1);
            if (!ALLOWED.contains(token)) {
                found.add(token);
            }
        }
        return List.copyOf(found);
    }

    /** 프롬프트에 실을 사전. 모델이 옮겨 쓸 말을 미리 준다. */
    public String asPromptSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("[용어 바꿔 쓰기] 왼쪽은 시스템 내부 표기입니다. ")
          .append("고객에게는 오른쪽 표현으로 쓰십시오.\n")
          .append("표에 없더라도 대문자 영문 코드(예: PAID, AMOUNT_MISMATCH)는 ")
          .append("절대 그대로 쓰지 말고 우리말로 풀어 쓰십시오.\n");
        TERMS.forEach((k, v) -> sb.append("- ").append(k).append(" → ").append(v).append('\n'));
        return sb.toString();
    }

    /** 사전이 아는 용어 수. 문서와 테스트가 같은 수를 보게 한다. */
    public int size() {
        return TERMS.size();
    }
}
