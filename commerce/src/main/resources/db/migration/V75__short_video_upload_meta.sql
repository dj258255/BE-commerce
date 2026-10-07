-- 숏폼 업로드 메타(R21). presigned 업로드 URL이 가리키는 객체 키와, 생성 시점에 검증을 통과한
-- 길이·크기·해상도를 남긴다. 실제 파일 내용 검증(코덱 등)은 PROBING 단계가 한다.

    alter table short_videos
        add column object_key varchar(300) not null,
        add column content_type varchar(100),
        add column duration_seconds integer not null,
        add column file_size_bytes bigint not null,
        add column width integer not null,
        add column height integer not null;

    alter table short_videos
        add constraint uk_short_video_object_key unique (object_key);
