package com.beomsu.becommerce.shorts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R26: 숏폼 피드 한 쪽 조립(정렬 순서 보존, 커서 경계, READY 외 제외)을 검증한다.
 *
 * <p>{@code ShortsFeedPage.assemble}은 DB를 몰라 실 MySQL 없이 테스트할 수 있다 — 아직 저장되지
 * 않은(id가 없는) {@link ShortVideo}에 {@link ReflectionTestUtils}로 id를 심어 "이미 저장된
 * 것처럼" 만든다({@code CompositeUserDetailsServiceTest}가 멤버 id를 심는 것과 같은 방식).
 */
class ShortsFeedPageTest {

    private static final UploadMeta META = new UploadMeta(20, 1_000_000L, 1080, 1920, "video/mp4");

    private static ShortVideo ready(long id) {
        ShortVideo v = ShortVideo.upload(1L, "shorts/1/" + id, META);
        ReflectionTestUtils.setField(v, "id", id);
        v.markUploaded();
        v.startProbing();
        v.startTranscoding();
        v.markReady();
        return v;
    }

    private static ShortVideo notReady(long id, ShortVideoStatus stopAt) {
        ShortVideo v = ShortVideo.upload(1L, "shorts/1/" + id, META);
        ReflectionTestUtils.setField(v, "id", id);
        if (stopAt == ShortVideoStatus.UPLOADING) {
            return v;
        }
        v.markUploaded();
        if (stopAt == ShortVideoStatus.UPLOADED) {
            return v;
        }
        v.startProbing();
        if (stopAt == ShortVideoStatus.FAILED) {
            v.fail("테스트 실패");
            return v;
        }
        v.startTranscoding();
        return v; // TRANSCODING에 멈춤
    }

    @Test
    @DisplayName("R26: 빈 피드 — 입력이 없으면 항목 없음, 다음 쪽 없음")
    void emptyFeedHasNoItemsAndNoNextPage() {
        ShortsFeedPage.Result result = ShortsFeedPage.assemble(List.of(), 10);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("R26: READY가 아닌 숏폼(TRANSCODING·FAILED·UPLOADED)은 피드에서 제외된다")
    void nonReadyVideosAreExcluded() {
        List<ShortVideo> fetched = List.of(
                ready(5),
                notReady(4, ShortVideoStatus.TRANSCODING),
                notReady(3, ShortVideoStatus.FAILED),
                notReady(2, ShortVideoStatus.UPLOADED),
                ready(1));

        ShortsFeedPage.Result result = ShortsFeedPage.assemble(fetched, 10);

        assertThat(result.items()).extracting(ShortVideo::getId).containsExactly(5L, 1L);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("R26: 입력 순서(id 내림차순)를 그대로 보존한다 — 최신순")
    void preservesInputOrder() {
        List<ShortVideo> fetched = List.of(ready(30), ready(20), ready(10));

        ShortsFeedPage.Result result = ShortsFeedPage.assemble(fetched, 10);

        assertThat(result.items()).extracting(ShortVideo::getId).containsExactly(30L, 20L, 10L);
    }

    @Test
    @DisplayName("R26 경계: 마지막 쪽 — READY 개수가 pageSize 이하이면 다음 쪽이 없다")
    void lastPageHasNoNextCursor() {
        List<ShortVideo> fetched = List.of(ready(3), ready(2), ready(1));

        ShortsFeedPage.Result result = ShortsFeedPage.assemble(fetched, 3);

        assertThat(result.items()).hasSize(3);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("R26 경계: READY 개수가 pageSize+1(미리 받아본 다음 쪽 표시)이면 "
            + "마지막 1개를 잘라내고 다음 커서를 이 쪽 마지막 항목의 id로 잡는다")
    void moreThanPageSizeYieldsNextCursor() {
        List<ShortVideo> fetched = List.of(ready(5), ready(4), ready(3), ready(2), ready(1));

        ShortsFeedPage.Result result = ShortsFeedPage.assemble(fetched, 4);

        assertThat(result.items()).extracting(ShortVideo::getId).containsExactly(5L, 4L, 3L, 2L);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isEqualTo(2L);
    }

    @Test
    @DisplayName("R26 경계: pageSize+1개 중 비READY가 섞여 실제 READY가 pageSize 이하로 줄면 "
            + "다음 쪽이 없다고 판단한다")
    void nonReadyAmongOverfetchDoesNotFalselyIndicateNextPage() {
        // pageSize=2인데 3개를 가져왔지만 그중 1개가 READY가 아니면 실제 READY는 2개뿐이다.
        List<ShortVideo> fetched = List.of(ready(3), notReady(2, ShortVideoStatus.TRANSCODING), ready(1));

        ShortsFeedPage.Result result = ShortsFeedPage.assemble(fetched, 2);

        assertThat(result.items()).extracting(ShortVideo::getId).containsExactly(3L, 1L);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }
}
