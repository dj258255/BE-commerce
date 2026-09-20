export type Column<T> = {
  key: string;
  label: string;
  num?: boolean;
  fmt?: (v: unknown, row: T) => React.ReactNode;
};

/** 모노 숫자·오른쪽 정렬을 기본으로 둔 표. 데이터 화면이 많아 표를 1급으로 쓴다. */
export function DataTable<T extends Record<string, unknown>>({
  columns,
  rows,
}: {
  columns: Column<T>[];
  rows: T[];
}) {
  return (
    <table className="t">
      <thead>
        <tr>
          {columns.map((c) => (
            <th key={c.key} className={c.num ? 'num' : undefined}>
              {c.label}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={i}>
            {columns.map((c) => (
              <td key={c.key} className={c.num ? 'num' : undefined}>
                {c.fmt ? c.fmt(r[c.key], r) : String(r[c.key] ?? '')}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
