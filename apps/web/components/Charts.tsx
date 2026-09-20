import type { BarItem, Span } from '@/lib/contracts';

export type { BarItem, Span };

/** 값 하나가 아니라 '누가 큰가'를 보는 분포·비교용. 표보다 한눈에 들어온다. */
export function Bars({ items, max }: { items: BarItem[]; max?: number }) {
  const m = max ?? Math.max(...items.map((i) => i.value), 1);
  return (
    <div className="bars">
      {items.map((it) => (
        <div className="row" key={it.label}>
          <span className="lb">{it.label}</span>
          <span className="track">
            <span
              className="fill"
              style={{ width: `${((it.value / m) * 100).toFixed(1)}%`, background: it.color ?? 'var(--accent)' }}
            />
          </span>
          <span className="val">{it.display ?? it.value.toLocaleString('ko-KR')}</span>
        </div>
      ))}
    </div>
  );
}

/** 요청 하나의 구간별 소모. 어디가 느린지 평균은 알려주지 않는다. */
export function Timeline({ spans, total }: { spans: Span[]; total?: number }) {
  const t = total ?? Math.max(...spans.map((s) => s.start + s.ms), 1);
  return (
    <div className="timeline">
      {spans.map((s) => (
        <div className="row" key={s.name}>
          <span className="lb">{s.name}</span>
          <span className="track">
            <span
              className="fill"
              style={{
                left: `${((s.start / t) * 100).toFixed(2)}%`,
                width: `${Math.max((s.ms / t) * 100, 0.4).toFixed(2)}%`,
                background: s.color ?? 'var(--accent)',
              }}
            />
          </span>
          <span className="val">
            {s.ms.toFixed(1)}ms{s.note ? ` · ${s.note}` : ''}
          </span>
        </div>
      ))}
    </div>
  );
}
