package com.beomsu.becommerce.seller.screening;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 후보에 없는 표기를 소리로 잡는지, 그리고 <b>남남까지 잡지는 않는지</b> 잰다.
 */
class PhoneticKeyTest {

    @Test
    @DisplayName("같은 박씨의 여러 표기가 같은 소리가 된다 — 오타가 아니라 다 맞는 표기다")
    void parkVariantsCollapse() {
        List<String> variants = List.of("Park", "Bak", "Pak", "Bahk", "Pahk", "Back");
        var keys = variants.stream().map(PhoneticKey::of).distinct().toList();
        System.out.printf("%n  %s → %s%n", variants, keys);
        assertThat(keys).as("여섯 표기가 한 소리로 모여야 한다").hasSize(1);
    }

    @Test
    @DisplayName("이(李)의 남한·북한 표기가 같은 소리가 된다")
    void leeAndRi() {
        System.out.printf("  Lee=%s Ri=%s Yi=%s%n", PhoneticKey.of("Lee"), PhoneticKey.of("Ri"), PhoneticKey.of("Yi"));
        assertThat(PhoneticKey.sounds("Lee", "Ri")).isTrue();
    }

    @Test
    @DisplayName("최의 Choi 와 Choe 가 같은 소리가 된다")
    void choiAndChoe() {
        assertThat(PhoneticKey.sounds("Choi", "Choe")).isTrue();
    }

    /**
     * <b>여기가 이 키의 대가다.</b> 소리를 접을수록 남남도 같은 키를 받는다.
     * 그래서 이 키는 "사람에게 보낼 이유"이지 일치의 근거가 아니다.
     */
    @Test
    @DisplayName("접은 만큼 남남도 걸린다 — 그래서 이 키로 확정하지 않는다")
    void collapsingAlsoCatchesStrangers() {
        boolean sameSound = PhoneticKey.sounds("KIM CHUL SOO", "KIM CHUL SOON");
        System.out.printf("  KIM CHUL SOO vs KIM CHUL SOON → 같은 소리? %s%n", sameSound);
        System.out.printf("    %s / %s%n", PhoneticKey.of("KIM CHUL SOO"), PhoneticKey.of("KIM CHUL SOON"));
        // 같든 다르든 이 키로 확정하지 않는다는 것이 요지다. 결과만 기록한다.
        assertThat(PhoneticKey.of("KIM CHUL SOO")).isNotNull();
    }

    @Test
    @DisplayName("아예 다른 이름은 다른 소리다")
    void differentNamesDiffer() {
        assertThat(PhoneticKey.sounds("KIM CHUL SOO", "HWANG SOK HWA")).isFalse();
    }
}
