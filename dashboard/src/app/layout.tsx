import type { Metadata } from "next";
import { Analytics } from "@vercel/analytics/next";
import { SpeedInsights } from "@vercel/speed-insights/next";
import "./globals.css";

export const metadata: Metadata = {
  title: "MAPI · Minecraft control room",
  description: "A local browser dashboard for Minecraft API instances.",
};

const enableVercelObservability = process.env.VERCEL === "1";

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>
        {children}
        {enableVercelObservability && <Analytics />}
        {enableVercelObservability && <SpeedInsights />}
      </body>
    </html>
  );
}
