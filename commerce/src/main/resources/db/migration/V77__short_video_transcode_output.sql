-- 숏폼 변환 산출물(R23). 세 화질 렌디션 경로·마스터 재생목록·썸네일 — READY가 되려면
-- 다섯 컬럼이 전부 채워져 있어야 한다(애플리케이션이 강제, R23.2). 전부 nullable인 이유는
-- TRANSCODING 전까지는 값이 없는 것이 정상이기 때문이다.

    alter table short_videos
        add column rendition_1080p_path varchar(300),
        add column rendition_720p_path varchar(300),
        add column rendition_480p_path varchar(300),
        add column master_playlist_path varchar(300),
        add column thumbnail_path varchar(300);
