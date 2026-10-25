const clean = (value?: string) => (value ?? "").replace(/\/$/, "");

/**
 * Defaults for the infrastructure this deployment actually runs on.
 * Every value can still be overridden with an environment variable, so a fork
 * can point at its own services without touching code.
 */
const DEFAULT_EVOLUTION_URL = "https://evolution-api-rqy3.onrender.com";
const DEFAULT_EVOLUTION_INSTANCE = "whatsapp bot";
const DEFAULT_SUPABASE_URL = "https://hwpfxpfcixkykqpsiohi.supabase.co";

export const env = {
  n8nBaseUrl: clean(process.env.N8N_BASE_URL),
  evolutionApiUrl: clean(process.env.EVOLUTION_API_URL) || DEFAULT_EVOLUTION_URL,
  evolutionApiKey: process.env.EVOLUTION_API_KEY || "",
  evolutionInstance: process.env.EVOLUTION_INSTANCE || DEFAULT_EVOLUTION_INSTANCE,
  /**
   * Public Supabase project values. Safe to expose to the browser.
   * The service/secret key must NEVER live here or in the Android app - anyone
   * who unpacks an APK or opens devtools could read it.
   */
  supabaseUrl: clean(process.env.SUPABASE_URL) || DEFAULT_SUPABASE_URL,
  supabasePublishableKey: process.env.SUPABASE_PUBLISHABLE_KEY || "",
  databaseUrl: process.env.DATABASE_URL || "",
  dashboardPassphrase: process.env.DASHBOARD_PASSPHRASE || "",
};

/** Host part of a URL, for compact status lines. */
export function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}
