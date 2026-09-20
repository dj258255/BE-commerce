package probe.flush;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * 이 테스트에서만 쓰는 엔티티.
 *
 * <p><b>{@code @Entity}는 패키지 스캔에 잡힌다.</b> 처음에는 그냥 뒀더니
 * {@code @SpringBootTest}로 실 MySQL을 띄우는 통합 테스트가 스키마 검증에서
 * {@code missing table [thing]}으로 죽었다. 테스트 클래스패스 전체를 스캔하기 때문이다.
 *
 * <p>단위 테스트만 돌리면 안 걸린다. 통합 테스트는 도커가 필요해 로컬에서 잘 안 돌리고,
 * 그래서 <b>CI 에서만 터졌다.</b> 그 자체가 이 시리즈의 주제이기도 하다 —
 * 안 돌려본 경로는 초록불이 아니라 안 재본 것이다.
 *
 * <p>그래서 <b>{@code com.beomsu.becommerce} 밖의 패키지</b>로 옮겼다. 앱의 컴포넌트 스캔이
 * 그 패키지를 훑지 않으므로 통합 테스트의 스키마 검증에 걸리지 않고,
 * 이 테스트는 자기 {@code @ContextConfiguration} 으로 직접 등록해서 쓴다.
 */
@Entity(name = "thing")
@jakarta.persistence.Table(name = "thing")
public class Thing {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String name;

    public void setName(String name) {
        this.name = name;
    }
}
