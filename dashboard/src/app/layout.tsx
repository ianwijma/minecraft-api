import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "MAPI · Minecraft control room",
  description: "A local browser dashboard for Minecraft API instances.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
