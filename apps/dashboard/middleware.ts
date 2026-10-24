import { NextResponse, type NextRequest } from "next/server";
import { AUTH_COOKIE, authEnabled, isValidToken } from "@/lib/auth";

export async function middleware(request: NextRequest) {
  if (!authEnabled()) return NextResponse.next();

  const token = request.cookies.get(AUTH_COOKIE)?.value;
  if (await isValidToken(token)) return NextResponse.next();

  if (request.nextUrl.pathname.startsWith("/api/")) {
    return NextResponse.json(
      { ok: false, error: "Unauthorized. Sign in through the cockpit." },
      { status: 401 },
    );
  }

  const url = request.nextUrl.clone();
  url.pathname = "/login";
  url.search = "";
  const next = request.nextUrl.pathname + request.nextUrl.search;
  if (next && next !== "/") url.searchParams.set("next", next);
  return NextResponse.redirect(url);
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|icon.svg|login|api/auth|api/health).*)"],
};
