export type Series = { name: string; cls?: string; points: [number, number][] };

type Props = {
  series: Series[];
  width?: number;
  height?: number;
  xLabel?: string;
  xTicks?: number[];
  y0?: boolean;
  xMin?: number;
  xMax?: number;
};

const n = (v: number, digits = 0) => v.toLocaleString('ko-KR', { maximumFractionDigits: digits });

/** 인라인 SVG 라인 차트. 차트 라이브러리를 넣지 않는다(의존성 0). */
export function LineChart({ series, width = 720, height = 240, xLabel, xTicks, y0, xMin, xMax }: Props) {
  const pts = series.flatMap((s) => s.points);
  if (!pts.length) return <p className="muted">데이터가 없습니다.</p>;

  const P = { t: 14, r: 14, b: 34, l: 52 };
  const xs = pts.map((p) => p[0]);
  const ys = pts.map((p) => p[1]);
  let lo = xMin ?? Math.min(...xs);
  let hi = xMax ?? Math.max(...xs);
  let yLo = y0 ? 0 : Math.min(...ys);
  let yHi = Math.max(...ys);
  if (hi === lo) hi = lo + 1;
  if (yHi === yLo) yHi = yLo + 1;

  const iw = width - P.l - P.r;
  const ih = height - P.t - P.b;
  const X = (v: number) => P.l + ((v - lo) / (hi - lo)) * iw;
  const Y = (v: number) => P.t + (1 - (v - yLo) / (yHi - yLo)) * ih;

  const gridlines = [0, 1, 2, 3, 4].map((i) => {
    const v = yLo + ((yHi - yLo) * i) / 4;
    return { v, y: Y(v) };
  });

  const ticks = xTicks ?? [lo, (lo + hi) / 2, hi];

  return (
    <svg className="chart" viewBox={`0 0 ${width} ${height}`} role="img" preserveAspectRatio="xMidYMid meet">
      {gridlines.map((g, i) => (
        <g key={i}>
          <line className="gridline" x1={P.l} y1={g.y} x2={width - P.r} y2={g.y} />
          <text className="tick" x={P.l - 8} y={g.y + 3} textAnchor="end">
            {n(g.v, yHi - yLo < 10 ? 1 : 0)}
          </text>
        </g>
      ))}
      {ticks.map((t, i) => (
        <text key={i} className="tick" x={X(t)} y={height - 12} textAnchor="middle">
          {n(t)}
        </text>
      ))}
      <line className="axis" x1={P.l} y1={height - P.b} x2={width - P.r} y2={height - P.b} />
      {series.map((s) => (
        <g key={s.name} className={s.cls ?? 's-a'}>
          <path
            className={`series ${s.cls ?? 's-a'}`}
            d={s.points.map((p, i) => `${i ? 'L' : 'M'}${X(p[0]).toFixed(1)} ${Y(p[1]).toFixed(1)}`).join(' ')}
          />
          {s.points.map((p, i) => (
            <circle key={i} cx={X(p[0]).toFixed(1)} cy={Y(p[1]).toFixed(1)} r={2.6} fill="currentColor" />
          ))}
        </g>
      ))}
      {xLabel ? (
        <text className="label" x={width / 2} y={height - 1} textAnchor="middle">
          {xLabel}
        </text>
      ) : null}
    </svg>
  );
}

export function ChartLegend({ series }: { series: Series[] }) {
  return (
    <div className="legend">
      {series.map((s) => (
        <span key={s.name}>
          <i className={s.cls ?? 's-a'} style={{ background: 'currentColor' }} />
          {s.name}
        </span>
      ))}
    </div>
  );
}
