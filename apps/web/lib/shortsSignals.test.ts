import { describe, expect, it } from 'vitest';
import {
  finalizeViewSession,
  getOrCreateAnonymousId,
  onProductTagTap,
  onTimeUpdate,
  shortsSignalPath,
  startViewSession,
  type IdStorage,
} from './shortsSignals';

describe('R27: 시청 신호 — 영상마다 시청시간·완료·다시보기·건너뛰기·상품탭을 이벤트로 보낸다', () => {
  it('시청 시간은 세션 시작부터 종료까지의 경과 시간(초)이다', () => {
    const session = startViewSession(1, 1_000);
    const payload = finalizeViewSession(session, 1_000 + 7_400, null);
    expect(payload.watchSeconds).toBe(7); // 7.4초 → 반올림 아니라 내림(Math.round(7.4)=7)
  });

  it('재생 위치가 끝 근처에서 처음 근처로 되감기면(loop) 완료로 본다', () => {
    let session = startViewSession(1, 0);
    session = onTimeUpdate(session, 19.6, 20); // 끝 근처(20초 영상, 1초 미만 남음)
    session = onTimeUpdate(session, 0.2, 20); // 처음으로 되감김 → 완료

    const payload = finalizeViewSession(session, 20_000, null);

    expect(payload.completed).toBe(true);
    expect(payload.replayCount).toBe(0); // 처음 완료는 replay가 아니다
  });

  it('완료 이후 같은 영상이 또 끝까지 돌면 replayCount가 올라간다', () => {
    let session = startViewSession(1, 0);
    session = onTimeUpdate(session, 19.6, 20);
    session = onTimeUpdate(session, 0.2, 20); // 1차 완료
    session = onTimeUpdate(session, 19.7, 20);
    session = onTimeUpdate(session, 0.1, 20); // 2차 완료 = 다시보기 1회

    const payload = finalizeViewSession(session, 40_000, null);

    expect(payload.completed).toBe(true);
    expect(payload.replayCount).toBe(1);
  });

  it('완료하지 못한 채 3초 미만만 보고 넘어가면 건너뛰기로 본다', () => {
    const session = startViewSession(1, 0);
    const payload = finalizeViewSession(session, 2_000, null);

    expect(payload.skippedWithin3s).toBe(true);
    expect(payload.completed).toBe(false);
  });

  it('3초 이상 봤으면 건너뛰기가 아니다(완료 여부와 무관)', () => {
    const session = startViewSession(1, 0);
    const payload = finalizeViewSession(session, 3_500, null);

    expect(payload.skippedWithin3s).toBe(false);
  });

  it('완료했으면 시청 시간이 짧아도(초반에 완료 판정) 건너뛰기가 아니다', () => {
    let session = startViewSession(1, 0);
    session = onTimeUpdate(session, 1.6, 2); // 2초짜리 영상, 끝 근처
    session = onTimeUpdate(session, 0.1, 2); // 완료
    const payload = finalizeViewSession(session, 2_000, null);

    expect(payload.completed).toBe(true);
    expect(payload.skippedWithin3s).toBe(false);
  });

  it('시청 동안 상품 태그를 누르면 productTagTapped가 담긴다', () => {
    let session = startViewSession(1, 0);
    session = onProductTagTap(session);
    const payload = finalizeViewSession(session, 5_000, null);

    expect(payload.productTagTapped).toBe(true);
  });

  it('상품 태그를 누르지 않았으면 false다', () => {
    const session = startViewSession(1, 0);
    const payload = finalizeViewSession(session, 5_000, null);

    expect(payload.productTagTapped).toBe(false);
  });

  it('로그인하지 않았으면 익명 식별자를 payload에 담는다', () => {
    const session = startViewSession(1, 0);
    const payload = finalizeViewSession(session, 1_000, 'anon-xyz');

    expect(payload.anonymousId).toBe('anon-xyz');
  });

  it('시청 신호 경로는 영상 id를 포함한다', () => {
    expect(shortsSignalPath(42)).toBe('/api/v1/shorts/42/signals');
  });
});

describe('R27: 익명 식별자 — 저장된 값이 있으면 재사용하고, 없으면 새로 만들어 저장한다', () => {
  function memoryStorage(initial: Record<string, string> = {}): IdStorage {
    const map = new Map(Object.entries(initial));
    return {
      getItem: (key) => map.get(key) ?? null,
      setItem: (key, value) => map.set(key, value),
    };
  }

  it('저장된 익명 식별자가 있으면 그대로 돌려준다(재생성하지 않는다)', () => {
    const storage = memoryStorage({ 'shorts-anonymous-id': 'existing-id' });

    const id = getOrCreateAnonymousId(storage, () => 'new-id');

    expect(id).toBe('existing-id');
  });

  it('저장된 값이 없으면 새로 만들고 저장소에 남긴다(다음 호출에서 재사용되도록)', () => {
    const storage = memoryStorage();

    const id = getOrCreateAnonymousId(storage, () => 'new-id');

    expect(id).toBe('new-id');
    expect(storage.getItem('shorts-anonymous-id')).toBe('new-id');
  });
});
