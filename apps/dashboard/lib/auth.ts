export const AUTH_COOKIE = "bonjee_session";

/** Auth is opt-in so local demo mode works without configuration. */
export function authEnabled(): boolean {
  return Boolean(process.env.DASHBOARD_PASSPHRASE);
}

async function hmac(passphrase: string): Promise<string> {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(passphrase),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, enc.encode("bonjee-session-v1"));
  return Array.from(new Uint8Array(signature))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export async function expectedToken(): Promise<string | null> {
  const passphrase = process.env.DASHBOARD_PASSPHRASE;
  if (!passphrase) return null;
  return hmac(passphrase);
}

function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export async function isValidToken(token: string | undefined | null): Promise<boolean> {
  if (!authEnabled()) return true;
  if (!token) return false;
  const expected = await expectedToken();
  return Boolean(expected && safeEqual(token, expected));
}

export async function isValidPassphrase(passphrase: string): Promise<boolean> {
  const expected = process.env.DASHBOARD_PASSPHRASE;
  if (!expected) return true;
  if (passphrase.length !== expected.length) return false;
  return safeEqual(passphrase, expected);
}
