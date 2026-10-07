package com.beomsu.becommerce.shorts;

import com.beomsu.becommerce.shared.Ulid;
import com.beomsu.becommerce.shorts.storage.ShortsStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 숏폼 애플리케이션 서비스 — shorts 모듈의 공개 진입점(R21·R25).
 *
 * <p>업로드 시작({@link #startUpload})에서 {@link UploadMeta}로 길이·크기·세로 비율을 검증하고,
 * 저장소에 presigned URL을 발급받아 {@link ShortVideo}를 UPLOADING으로 만든다. 업로드 완료 알림
 * ({@link #completeUpload})은 클라이언트 주장을 그대로 믿지 않고 {@link ShortsStorage#exists}로
 * 객체가 실제로 올라왔는지 확인한 뒤에만 UPLOADED로 전이한다.
 *
 * <p>상품 연결({@link #linkProduct}·{@link #unlinkProduct}, R25)은 카탈로그 실존 확인만 여기서
 * 하고(엔티티는 카탈로그를 모른다), 중복 방지·개수 상한은 {@link ShortVideo}가 막는다. 조회 응답의
 * 상품 카드(이름·가격)는 {@link ProductLookup}(media가 정의한 포트, R32·ADR-080)으로 한 번에
 * 읽는다 — 구현은 commerce 쪽에 있지만 이 클래스는 그 사실을 모른다.
 *
 * <p>소유권은 전부 {@code sellerId}(principal에서 얻은 값)로 검증한다(IDOR 방지) — 남의 영상
 * id를 넣어도 {@link ShortsException#forbidden}으로 막힌다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ShortsService {

    private final ShortVideoRepository repository;
    private final ShortsStorage storage;
    private final ProductLookup productLookup;

    /** 업로드 시작 — 메타를 검증하고 객체 키를 발급해 UPLOADING 레코드를 만든다. */
    public StartUploadResult startUpload(long sellerId, UploadMeta meta) {
        String objectKey = "shorts/%d/%s".formatted(sellerId, Ulid.generate());
        ShortVideo video = ShortVideo.upload(sellerId, objectKey, meta);
        repository.save(video);
        String uploadUrl = storage.issueUploadUrl(objectKey, meta.contentType());
        return new StartUploadResult(video.getId(), uploadUrl);
    }

    /** 업로드 완료 알림 — 저장소에 객체가 실제로 있을 때만 UPLOADED로 전이한다. */
    public ShortVideoView completeUpload(long sellerId, long id) {
        ShortVideo video = findOwned(sellerId, id);
        if (!storage.exists(video.getObjectKey())) {
            throw ShortsException.uploadNotFound(video.getObjectKey());
        }
        video.markUploaded();
        return toView(video);
    }

    @Transactional(readOnly = true)
    public ShortVideoView get(long sellerId, long id) {
        return toView(findOwned(sellerId, id));
    }

    /**
     * 상품 연결(R25) — 카탈로그에 없는 상품이면 {@code PRODUCT_NOT_FOUND}(404), 이미
     * {@link ShortVideo#MAX_LINKED_PRODUCTS}개가 연결된 영상에 새 상품을 더하면
     * {@code TOO_MANY_LINKED_PRODUCTS}(409)로 거절된다({@link ShortVideo#linkProduct}).
     */
    public ShortVideoView linkProduct(long sellerId, long id, long productId) {
        ShortVideo video = findOwned(sellerId, id);
        if (!productLookup.exists(productId)) {
            throw ShortsException.productNotFound(productId);
        }
        video.linkProduct(productId);
        return toView(video);
    }

    /** 상품 연결 해제(R25) — 연결돼 있지 않은 상품을 해제해도 성공한다(멱등). */
    public ShortVideoView unlinkProduct(long sellerId, long id, long productId) {
        ShortVideo video = findOwned(sellerId, id);
        video.unlinkProduct(productId);
        return toView(video);
    }

    private ShortVideo findOwned(long sellerId, long id) {
        ShortVideo video = repository.findById(id).orElseThrow(() -> ShortsException.notFound(id));
        if (video.getSellerId() != sellerId) {
            throw ShortsException.forbidden(id);
        }
        return video;
    }

    /** 연결 상품 카드를 한 번에 읽어(N+1 방지) 뷰에 담는다(R25). */
    private ShortVideoView toView(ShortVideo video) {
        return ShortVideoView.from(video, productLookup.findAll(video.getLinkedProductIds()));
    }

    /** 업로드 시작 응답 — 발급된 숏폼 id와 업로드 대상 URL. */
    public record StartUploadResult(Long shortVideoId, String uploadUrl) {
    }
}
