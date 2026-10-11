-- 숏폼 영상 상태머신(R22). 업로드~변환 파이프라인의 현재 단계와 실패·재시도·격리를 적재한다.
-- 저장소 키(objectKey)·상품 연결 등은 다음 단계(R21 업로드 API)에서 컬럼이 붙는다.

    create table short_videos (
        seller_id bigint not null,
        retry_count integer not null,
        max_retries integer not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6) not null,
        failure_reason varchar(500),
        status enum ('UPLOADING','UPLOADED','PROBING','TRANSCODING','READY','FAILED','QUARANTINED') not null,
        primary key (id)
    ) engine=InnoDB;

    create index idx_short_video_seller_status on short_videos (seller_id, status);
