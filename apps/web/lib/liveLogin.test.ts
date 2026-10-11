import { describe, expect, it } from 'vitest';
import { loginFailureMessage, validateLoginForm } from './liveLogin';

describe('R5.3: 로그인 빈 입력 안내', () => {
  it('아이디·비밀번호가 모두 비어 있으면 둘 다 입력하라고 안내한다', () => {
    expect(validateLoginForm({ username: '', password: '' })).toBe('아이디와 비밀번호를 입력하세요.');
  });

  it('아이디만 비어 있으면 아이디를 입력하라고 안내한다', () => {
    expect(validateLoginForm({ username: '  ', password: 'secret' })).toBe('아이디를 입력하세요.');
  });

  it('비밀번호만 비어 있으면 비밀번호를 입력하라고 안내한다', () => {
    expect(validateLoginForm({ username: 'alice', password: '' })).toBe('비밀번호를 입력하세요.');
  });

  it('둘 다 채워져 있으면 통과한다(null)', () => {
    expect(validateLoginForm({ username: 'alice', password: 'secret' })).toBeNull();
  });
});

describe('R5.3: 로그인 실패 메시지', () => {
  it('401이면 아이디·비밀번호가 올바르지 않다고 안내한다(상태 코드를 그대로 보여주지 않는다)', () => {
    const message = loginFailureMessage(401);
    expect(message).toBe('아이디 또는 비밀번호가 올바르지 않습니다.');
    expect(message).not.toContain('401');
  });

  it('403도 자격 증명 문제로 같은 안내를 준다', () => {
    expect(loginFailureMessage(403)).toBe('아이디 또는 비밀번호가 올바르지 않습니다.');
  });

  it('서버 오류(5xx)는 서버 문제로 안내한다', () => {
    expect(loginFailureMessage(503)).toBe('로그인 서버에 문제가 생겼습니다. 잠시 뒤 다시 시도하세요.');
  });

  it('그 외 상태 코드는 일반 실패 안내로 떨어진다', () => {
    expect(loginFailureMessage(418)).toBe('로그인에 실패했습니다. 잠시 뒤 다시 시도하세요.');
  });
});
