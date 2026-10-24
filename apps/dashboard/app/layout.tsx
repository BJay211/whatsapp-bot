import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "Bonjee OS — Personal Intelligence",
    template: "%s · Bonjee OS",
  },
  description:
    "A grounded personal AI cockpit for WhatsApp, tasks, deadlines and your day. Local-first, source-backed, privacy-minded.",
  applicationName: "Bonjee OS",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  themeColor: "#000814",
  colorScheme: "dark",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className="dark">
      <body>
        <div className="shell-grid" aria-hidden />
        <div className="shell-vignette" aria-hidden />
        <div className="scanline" aria-hidden />
        {children}
      </body>
    </html>
  );
}
