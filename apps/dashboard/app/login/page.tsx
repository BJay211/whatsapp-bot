import { authEnabled } from "@/lib/auth";
import { LoginForm } from "@/components/auth/login-form";

export const dynamic = "force-dynamic";

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ next?: string }>;
}) {
  const params = await searchParams;
  const raw = params.next ?? "/";
  // Only allow same-origin absolute paths.
  const nextPath = raw.startsWith("/") && !raw.startsWith("//") ? raw : "/";

  return <LoginForm authEnabled={authEnabled()} nextPath={nextPath} />;
}
