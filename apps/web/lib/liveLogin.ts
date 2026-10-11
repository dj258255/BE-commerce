/**
 * 라이브 시청 화면(R5) 로그인 폼의 순수 로직 — 빈 입력 안내와 사람이 읽을 실패 메시지를
 * 컴포넌트 밖으로 꺼내서(DOM·fetch 없이) 테스트할 수 있게 한다.
 */
export type LoginForm = { username: string; password: string };

/** 서버에 보내기 전에 걸러낸다 — 비어 있으면 무엇을 입력해야 하는지 바로 알려준다. */
export function validateLoginForm(form: LoginForm): string | null {
  const username = form.username.trim();
  const password = form.password.trim();
  if (!username && !password) return '아이디와 비밀번호를 입력하세요.';
  if (!username) return '아이디를 입력하세요.';
  if (!password) return '비밀번호를 입력하세요.';
  return null;
}

/** 로그인 실패를 상태 코드가 아니라 사람이 읽을 말로 바꾼다. */
export function loginFailureMessage(status: number): string {
  if (status === 401 || status === 403) return '아이디 또는 비밀번호가 올바르지 않습니다.';
  if (status >= 500) return '로그인 서버에 문제가 생겼습니다. 잠시 뒤 다시 시도하세요.';
  return '로그인에 실패했습니다. 잠시 뒤 다시 시도하세요.';
}
