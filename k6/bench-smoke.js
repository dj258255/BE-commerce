import http from 'k6/http';
import { check } from 'k6';

/**
 * 하네스 자체를 검증하는 최소 실행 — <b>성능을 재는 스크립트가 아니다.</b>
 *
 * <p>왜 필요한가: 본 측정(capacity)은 계단을 다 올리는 데 20분 가까이 걸린다. 그런데
 * 인프라 기동·헬스 대기·프로세스 검증·요약 파싱 중 하나라도 어긋나 있으면 그 20분이 통째로
 * 날아간다. 그 배관이 맞는지를 <b>1분</b>에 확인하는 것이 이 스크립트의 유일한 목적이다.
 *
 * <p>그래서 부하를 걸지 않는다. 여기서 나온 숫자를 성능 근거로 인용하면 안 된다 —
 * 도착률이 5/s라 어떤 서버든 여유롭게 처리한다.
 *
 * <pre>
 * ./gradlew -p commerce bench -Pprofile=smoke
 * </pre>
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
    scenarios: {
        smoke: {
            executor: 'constant-arrival-rate',   // 본 측정과 같은 열린 루프 — 배관을 똑같이 태운다
            rate: 5,
            timeUnit: '1s',
            duration: '30s',
            preAllocatedVUs: 10,
            maxVUs: 20,
        },
    },
    // 여기서 실패하면 본 측정을 돌릴 이유가 없다. 그래서 임계를 빡빡하게 둔다.
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<2000'],
    },
};

export default function () {
    const res = http.get(`${BASE}/actuator/health`);
    check(res, {
        'health 200': (r) => r.status === 200,
        'status UP': (r) => String(r.body).includes('UP'),
    });
}
