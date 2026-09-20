import { MockBadge } from './ui';

export type NavKey = 'home' | 'personalization' | 'console';

const ITEMS: { key: NavKey; href: string; label: string }[] = [
  { key: 'home', href: '/', label: '개요' },
  { key: 'personalization', href: '/personalization', label: '개인화 홈' },
  { key: 'console', href: '/personalization/console', label: '실험 콘솔' },
];

export function SiteHeader({ active, mock }: { active: NavKey; mock: boolean }) {
  return (
    <header className="topbar">
      <div className="container topbar-in">
        <a className="brand" href="/">
          BE<b>-commerce</b>
        </a>
        <span className="tag">WEB</span>
        <nav className="nav">
          {ITEMS.map((it) => (
            <a key={it.key} href={it.href} className={it.key === active ? 'on' : undefined}>
              {it.label}
            </a>
          ))}
        </nav>
        <div className="spacer" />
        <MockBadge mock={mock} />
      </div>
    </header>
  );
}
