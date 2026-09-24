package com.beomsu.becommerce.recommendation.internal;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * GenPage 모델 서버 하나의 동시 호출 자리(#271). 추천 행({@code /recommend})과 홈 다음 쪽({@code /page})이 같은 서버 CPU 를 쓰므로
 * 자리도 같이 쓴다.
 *
 * <p>나누지 않았을 때 무엇이 일어났나: 2쪽 부하(121/s)가 자리 없이 모델 서버로 갔고, 서버는 앱이 300ms 에 포기한 요청까지 끝까지
 * 계산했다. 버려진 계산이 CPU 를 먹어 추천 행 호출까지 전부 실패했다(추천 행 coverage 0%). 과부하 게이트는 추천 행만 보므로
 * 2쪽의 몫을 몰랐다.
 */
@ConditionalOnProperty(name = "app.recommendation.model.kind", havingValue = "genpage")
@Component
public class GenPageCapacity {

    private final Semaphore permits;

    public GenPageCapacity(@Value("${app.recommendation.model.concurrency:4}") int concurrency) {
        this.permits = new Semaphore(Math.max(concurrency, 1), true);
    }

    /** 자리를 얻으면 true. {@code waitMs} 가 0 이면 기다리지 않는다. */
    public boolean tryAcquire(long waitMs) {
        if (waitMs <= 0) {
            return permits.tryAcquire();
        }
        try {
            return permits.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        permits.release();
    }
}
