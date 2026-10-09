-- 화면 확인(스크린 체크)용 데모 방송 — 실 RTMP 송출·MediaMTX 없이도 /live/{id} 시청 화면이
-- 고정 상품 카드와 "바로 주문"(R11) 버튼을 보여줄 수 있어야 자동 화면 확인이 된다.
-- status=SCHEDULED로 둔다 — LivePlaybackController는 LIVE일 때만 hlsUrl을 돌려주므로,
-- SCHEDULED면 영상 없이 "방송 준비 중입니다" placeholder가 뜨고, 그래도 WS 스냅샷으로
-- 고정 카드는 뜬다(V82 바로 위 커밋에서 추가한 R9.2 폴백 틱 덕분에 영상 재생 없이도 적용된다).
insert into live_broadcasts
  (id, seller_id, created_at, updated_at, started_at, ended_at, disconnected_at, stream_key, title, status)
values
  (999001, 3, now(), now(), null, null, null, 'demo-pagecheck-stream-key-fixed', '화면 확인용 데모 방송', 'SCHEDULED');

-- product_id=1(V2 시드: "테스트 상품 A")을 고정 — seq=1, generation=1은 빈 LivePin에 pin()을
-- 한 번 호출했을 때와 같은 값이다(LivePin.pin() 참고).
insert into live_pins
  (id, broadcast_id, product_id, price, limited_quantity, seq, generation, effective_at, updated_at)
values
  (999001, 999001, 1, 9900, 50, 1, 1, now(), now());
