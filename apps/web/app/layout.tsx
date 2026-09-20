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
        <main className="page">
          <div className="container">{children}</div>
        </main>
      </body>
    </html>
  );
}
