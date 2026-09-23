package com.beomsu.becommerce.seller.screening;

/**
 * 이름 말고 함께 보는 것들 — <b>생년월일과 국적</b>.
 *
 * <p><b>왜 만들었나</b>: 임계를 고르려고 이름 점수를 쟀더니 <b>같은 사람의 다른 로마자 표기와
 * 아예 다른 사람이 같은 점수</b>를 받았다. 그때는 그것을 "문자열 거리의 한계"로 적고 사람
 * 검토로 보냈다. 그런데 실제 제재 스크리닝은 이름만으로 하지 않는다 —
 * 정확 일치, 퍼지 매칭, 그리고 <b>생년월일·국적·문서번호로 후보를 좁히는 층</b>이 따로 있다.
 * 경보의 90% 이상이 오탐이라 그 세 번째 층이 실무의 핵심인데,
 * <b>내가 만든 것은 두 번째 층까지였다.</b>
 *
 * <p>그러니 못 가른 이유는 거리 계산이 약해서가 아니라 <b>볼 것을 안 보고 있어서</b>다.
 * 명단 항목이 이름·프로그램·국가만 들고 있어 애초에 좁힐 재료가 없었다.
 *
 * <h3>모르는 것은 점수를 안 건드린다</h3>
 * 우리가 판매자의 생년월일을 모르거나 명단이 안 주면 <b>이름 점수를 그대로 둔다.</b>
 * 모르는 것을 "안 맞았다"로 읽으면 제재 대상이 조용히 통과한다 —
 * 이 시스템이 미확정 결제를 실패로 안 적는 것과 같은 이유다.
 */
public record SecondaryIdentifiers(String birthDate, String nationality) {

    /** 어긋났을 때 깎는 폭. 임계(80)를 확실히 넘겨 떨어뜨리도록 잡았다. */
    private static final int DOB_MISMATCH = -40;
    private static final int NATIONALITY_MISMATCH = -15;
    /** 맞았을 때 올리는 폭. 깎는 쪽보다 작다 — <b>맞다는 증거보다 아니라는 증거가 세다.</b> */
    private static final int DOB_MATCH = 10;
    private static final int NATIONALITY_MATCH = 5;

    /**
     * 국적은 <b>두 글자 코드로 맞춰서</b> 담는다. 우리는 {@code KR} 로 들고 있는데
     * 명단은 {@code Democratic Republic of the Congo} 처럼 이름으로 준다 —
     * 그대로 두면 항상 불일치가 되어 <b>맞는 사람의 점수를 깎는다.</b>
     * 못 알아보는 나라는 {@code null} 이 되고, 그때는 국적을 안 본다.
     */
    public static SecondaryIdentifiers of(String birthDate, String nationality) {
        return new SecondaryIdentifiers(blankToNull(birthDate), CountryNames.toCode(nationality));
    }

    public static SecondaryIdentifiers unknown() {
        return new SecondaryIdentifiers(null, null);
    }

    /**
     * 이름 점수를 2차 식별자로 보정한다.
     *
     * @param nameScore 이름만으로 낸 0~100
     * @param ours      우리가 아는 판매자의 식별자
     * @param theirs    명단 항목의 식별자
     */
    public static int adjust(int nameScore, SecondaryIdentifiers ours, SecondaryIdentifiers theirs) {
        int score = nameScore;
        score += compareBirthDate(ours.birthDate(), theirs.birthDate());
        score += compare(ours.nationality(), theirs.nationality(), NATIONALITY_MATCH, NATIONALITY_MISMATCH);
        return Math.max(0, Math.min(100, score));
    }

    /** 한쪽이라도 모르면 0 — 점수를 안 건드린다. */
    private static int compare(String a, String b, int onMatch, int onMismatch) {
        if (a == null || b == null) return 0;
        return a.equalsIgnoreCase(b) ? onMatch : onMismatch;
    }

    /**
     * 생년월일은 <b>겹치는 자리까지만</b> 본다.
     *
     * <p>실제 UN 명단을 받아 세어 보니 <b>연월일이 다 있는 항목이 0건</b>이었다.
     * {@code TYPE_OF_DATE} 가 EXACT 인 751건 중 263건이 <b>연도만</b> 주고 나머지는 아예 없다.
     * 전체 날짜를 맞대려던 처음 설계는 실제 데이터에서 <b>한 번도 발동하지 않는다.</b>
     *
     * <p>그래서 둘 다 가진 만큼만 비교한다 — 명단이 {@code 1971} 을 주고 우리가
     * {@code 1971-04-02} 를 알면 <b>연도만</b> 맞대고, 그 이상은 모르는 것으로 둔다.
     * 연도만 맞은 것을 전체가 맞은 것처럼 세면 <b>같은 해에 태어난 남을 끌어올린다.</b>
     */
    private static int compareBirthDate(String ours, String theirs) {
        if (ours == null || theirs == null) return 0;
        String a = ours.strip(), b = theirs.strip();
        int n = Math.min(a.length(), b.length());
        // 연도(4)만 겹치면 연도만, 연월(7)까지 겹치면 거기까지 본다
        int cut = n >= 10 ? 10 : n >= 7 ? 7 : n >= 4 ? 4 : 0;
        if (cut == 0) return 0;
        boolean same = a.regionMatches(true, 0, b, 0, cut);
        if (!same) return DOB_MISMATCH;
        // 겹치는 자리가 짧을수록 약한 증거다. 연도만 맞은 것은 전체가 맞은 것과 다르다.
        return cut >= 10 ? DOB_MATCH : cut >= 7 ? DOB_MATCH / 2 : DOB_MATCH / 4;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
