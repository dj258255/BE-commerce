import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'BE-commerce',
  description: '이커머스 플랫폼 BE-commerce — 상점과 개인화 콘솔',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body>
        <header className="topbar">
          <div className="container topbar-in">
            <a className="brand" href="/">
              BE<b>-commerce</b>
            </a>
            <span className="tag">WEB</span>
            <div className="spacer" />
            <span className="mono" style={{ fontSize: 12, color: 'var(--sub)' }}>
              Next.js · Spring API
            </span>
          </div>
        </header>
        <main className="page">
          <div className="container">{children}</div>
        </main>
      </body>
    </html>
  );
}
