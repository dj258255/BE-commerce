/**
 * 개발 서버 래퍼.
 *
 * 왜 필요한가: 셸에 NODE_ENV=production 이 설정돼 있으면 next dev 가 그 값을 물려받아
 * "required-server-files.json 없음"으로 500을 낸다(Next는 dev에서 NODE_ENV=development 를 기대한다).
 * 환경에 의존하지 않게 여기서 고정한다.
 */
import { spawn } from 'node:child_process';

const port = process.env.PORT ?? '3000';
const child = spawn(process.execPath, ['node_modules/next/dist/bin/next', 'dev', '-p', port], {
  stdio: 'inherit',
  env: { ...process.env, NODE_ENV: 'development' },
});

child.on('exit', (code) => process.exit(code ?? 0));
