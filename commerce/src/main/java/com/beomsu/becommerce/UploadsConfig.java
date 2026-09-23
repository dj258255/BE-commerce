package com.beomsu.becommerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * 상품 이미지 서빙.
 *
 * <p>이미지는 <b>레포에 넣지 않는다</b>(수 GB). 데이터 파이프라인이 리포 밖 디렉터리에 풀어 두고,
 * 그 디렉터리를 {@code /uploads/**} 경로로 매핑해 정적으로 서빙한다. 클래스패스(static/)에 두면
 * 빌드 산출물이 무거워지고 실수로 커밋되기 쉽다.
 *
 * <p>경로는 {@code app.uploads.dir}로 바꾼다(기본: 리포 루트 기준 파이프라인 출력 디렉터리).
 * 파일이 없으면 404이고, 화면은 그라디언트로 폴백한다 — 이미지가 일부만 있어도 화면이 깨지지 않는다.
 */
@Configuration
public class UploadsConfig implements WebMvcConfigurer {

    private final String uploadsDir;

    public UploadsConfig(@Value("${app.uploads.dir:personalization/data/hm/images}") String uploadsDir) {
        this.uploadsDir = uploadsDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Path.of(uploadsDir).toAbsolutePath().normalize();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(dir.toUri().toString())
                // 이미지는 바뀌지 않으니 캐시를 길게 둔다(로컬 개발 편의를 위해 존재 여부는 매번 확인).
                .setCachePeriod(3600);
    }
}
