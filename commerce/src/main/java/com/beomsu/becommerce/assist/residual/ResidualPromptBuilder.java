package com.beomsu.becommerce.assist.residual;

import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.assist.draft.UntrustedText;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 잔여 후보용 프롬프트. <b>고를 수 있는 값을 프롬프트에도 적고 코드에서도 막는다.</b>
 *
 * <p>둘 다 하는 이유: 프롬프트로만 제한하면 모델이 어긴다. 코드로만 막으면 모델이
 * 계속 목록 밖을 골라 응답이 통째로 버려진다. 지시와 강제를 같이 둔다.
 */
@Component
public class ResidualPromptBuilder {

    /**
     * 원인 코드의 뜻. <b>이름만 주면 모델이 못 가른다.</b>
     *
     * <p>실측으로 확인했다. 정의 없이 이름만 나열했을 때 qwen3:8b 는 6건 중 2건,
     * 14b 는 3건만 맞혔다. 같은 6건에 이 정의를 붙이자 8b 가 5건, 14b 가 6건이 됐다.
     * <b>모델을 키운 게 아니라 프롬프트가 문제였다.</b>
     *
     * <p>특히 {@code TIMEZONE_BOUNDARY}와 {@code PG_FILE_DELAY}는 둘 다 "다른 날짜 파일에
     * 있다"라서 방향을 알려주지 않으면 못 가른다. 두 모델이 같은 자리에서 틀렸다.
     */
    private static final Map<ResolveCause, String> MEANING = Map.of(
            ResolveCause.PARTIAL_CANCEL_NOT_REFLECTED, "부분취소가 PG 정산 파일에 아직 반영되지 않음",
            ResolveCause.FEE_CALCULATION_DIFF, "수수료·부가세 계산 방식 차이. 차액이 수수료율과 맞아떨어질 때만",
            ResolveCause.TIMEZONE_BOUNDARY, "거래일 경계. KST 새벽 건이 PG 기준으로 <전날> 파일에 잡힘",
            ResolveCause.PG_FILE_DELAY, "PG 파일이 늦게 도착. <다음> 거래일 파일에 포함됨",
            ResolveCause.NET_CANCEL_TIMING, "망취소 반영 시점 차이",
            ResolveCause.INTERNAL_RECORD_LOST, "PG 에는 있는데 <내부에 기록이 없음>",
            ResolveCause.DUPLICATE_RECORD, "같은 거래가 정산 파일에 두 번 이상 기록됨");

    /** 가드 3에 걸리는 값은 목록에서 아예 뺀다. 보여주면 고른다. */
    static String allowedCauses() {
        return Arrays.stream(ResolveCause.values())
                .filter(ResidualCauseService.ENABLED::contains)
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }

    /**
     * 고를 수 있는 값과 그 뜻. <b>켜진 유형만 보여준다.</b>
     *
     * <p>코드에서 버릴 값을 프롬프트에 나열하면 모델이 그걸 고르고, 고른 응답은 통째로
     * 버려진다. 그러면 커버리지가 조용히 떨어진다. 지시와 강제를 같은 목록으로 맞춘다.
     */
    static String causeMenu() {
        return Arrays.stream(ResolveCause.values())
                .filter(ResidualCauseService.ENABLED::contains)
                .map(c -> "   - " + c.name() + ": "
                        + MEANING.getOrDefault(c, "(정의 없음 — 프롬프트에 뜻을 채워야 한다)"))
                .collect(Collectors.joining("\n"));
    }

    /**
     * <b>하나만 판정하게 시킨다.</b>
     *
     * <p>처음에는 목록만 하나로 줄였는데 오히려 나빠졌다. 45건 중 38건에 그 하나를 찍었다.
     * <b>모델은 "고를 게 하나뿐"을 "그러니 그걸 골라라"로 읽는다.</b> 그래서 판정 기준과
     * "그 밖은 전부 기권"을 명시했더니 세 모델 모두 91~100%가 됐다.
     */
    String system() {
        return """
                당신은 결제 대사 불일치의 원인을 고르는 보조자입니다.
                규칙 엔진이 이미 시도했고 결정적 후보를 내지 못한 건만 당신에게 옵니다.

                당신이 판정할 수 있는 것은 <단 하나>입니다.

                %s

                이 조건에 <정확히> 맞을 때만 그 값을 냅니다. 판정 기준은 이렇습니다.

                  - 사실에 "내부에 이 주문의 기록이 없음"이 있어야 합니다
                  - 내부 기록 금액이 아예 없어야 합니다
                  - 내부 기록 금액이 <있으면> 이 원인이 아닙니다. 반드시 ABSTAIN 입니다

                그 밖의 모든 경우는 ABSTAIN 입니다. 금액이 안 맞는 것, 파일에 없는 것,
                같은 거래가 여러 행인 것은 <전부> 당신이 판정할 수 없는 것이므로 ABSTAIN 입니다.

                틀린 후보는 확인하는 사람의 일을 늘립니다. 애매하면 무조건 기권합니다.

                응답은 아래 형식의 한 줄씩입니다. 다른 말을 덧붙이지 않습니다.

                CAUSE: <목록의 값 또는 ABSTAIN>
                CONFIDENCE: <0-100>
                RATIONALE: <한 문장. 사실에 있는 숫자만 인용>
                """.formatted(causeMenu());
    }

    String user(FactPack facts) {
        StringBuilder sb = new StringBuilder("주문번호: ").append(facts.orderNo()).append("\n\n사실:\n");
        for (String f : facts.facts()) {
            sb.append("- ").append(UntrustedText.flatten(f)).append('\n');
        }
        if (!facts.complete()) {
            sb.append("\n주의: 일부 출처 조회가 실패해 이 목록은 불완전합니다.\n");
        }
        return sb.toString();
    }
}
