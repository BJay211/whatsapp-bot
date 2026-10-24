import type { Config } from "tailwindcss";

const config: Config = {
  darkMode: ["class"],
  content: [
    "./pages/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
    "./app/**/*.{ts,tsx}",
    "./lib/**/*.{ts,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        void: "hsl(var(--void) / <alpha-value>)",
        panel: "hsl(var(--panel) / <alpha-value>)",
        line: "hsl(var(--line) / <alpha-value>)",
        ink: "hsl(var(--ink) / <alpha-value>)",
        "ink-dim": "hsl(var(--ink-dim) / <alpha-value>)",
        "ink-faint": "hsl(var(--ink-faint) / <alpha-value>)",
        cyan: "hsl(var(--cyan) / <alpha-value>)",
        violet: "hsl(var(--violet) / <alpha-value>)",
        orange: "hsl(var(--orange) / <alpha-value>)",
        lime: "hsl(var(--lime) / <alpha-value>)",
        amber: "hsl(var(--amber) / <alpha-value>)",
        rose: "hsl(var(--rose) / <alpha-value>)",
      },
      fontFamily: {
        sans: ["Bahnschrift", "Segoe UI Variable Display", "Inter", "Segoe UI", "system-ui", "sans-serif"],
        display: ["Bahnschrift", "Segoe UI Variable Display", "Inter", "system-ui", "sans-serif"],
        mono: ["Cascadia Code", "JetBrains Mono", "SFMono-Regular", "Consolas", "monospace"],
      },
      letterSpacing: {
        hud: "0.24em",
      },
      borderRadius: {
        xl: "12px",
        "2xl": "16px",
        "3xl": "20px",
      },
      boxShadow: {
        glow: "0 0 40px -12px rgba(0, 229, 255, 0.6)",
        "glow-violet": "0 0 50px -16px rgba(124, 77, 255, 0.6)",
        "glow-orange": "0 0 50px -16px rgba(255, 111, 0, 0.55)",
        panel: "0 30px 80px -40px rgba(0,0,0,0.9)",
      },
      keyframes: {
        "accordion-down": { from: { height: "0" }, to: { height: "var(--radix-accordion-content-height)" } },
        "accordion-up": { from: { height: "var(--radix-accordion-content-height)" }, to: { height: "0" } },
      },
      animation: {
        "accordion-down": "accordion-down 0.2s ease-out",
        "accordion-up": "accordion-up 0.2s ease-out",
      },
    },
  },
  plugins: [],
};

export default config;
