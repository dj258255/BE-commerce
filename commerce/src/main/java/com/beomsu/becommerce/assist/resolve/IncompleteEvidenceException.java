package com.beomsu.becommerce.assist.resolve;

import java.util.List;

/**
 * 자료가 빠진 채로 확정하려 했다. <b>버그가 아니라 설계된 거절</b>이라 전용 코드로 나간다.
 *
 * <p>500 으로 나가면 호출자가 "내가 절차를 어겼나, 서버가 깨졌나"를 못 가른다.
 * 잔여 후보 리뷰에서 이미 한 번 겪은 구분이다.
 */
public class IncompleteEvidenceException extends RuntimeException {

    private final List<String> missing;

    private IncompleteEvidenceException(String message, List<String> missing) {
        super(message);
        this.missing = List.copyOf(missing);
    }

    static IncompleteEvidenceException of(String orderNo, List<String> missing) {
        return new IncompleteEvidenceException(
                "자료를 못 가져온 출처가 있습니다: " + String.join(", ", missing)
                        + ". 그대로 확정하려면 이 목록을 확인했다고 함께 보내십시오.", missing);
    }

    public List<String> missing() {
        return missing;
    }

    public String code() {
        return "RESOLVE_EVIDENCE_INCOMPLETE";
    }
}
