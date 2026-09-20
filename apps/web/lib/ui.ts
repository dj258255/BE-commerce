/** 화면 공용 헬퍼 — 상품 타일용 결정적 그라디언트. */

export function gradient(seed: string): string {
  const digits = String(seed).replace(/\D/g, '') || '0';
  const h = (Number(digits) * 47) % 360;
  return `linear-gradient(140deg, hsl(${h}, 45%, 56%), hsl(${(h + 40) % 360}, 50%, 40%))`;
}

export const won = (v: number) => `₩${v.toLocaleString('ko-KR')}`;
