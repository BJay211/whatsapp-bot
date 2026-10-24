import { getSnapshot } from "@/lib/store";
import { authEnabled } from "@/lib/auth";
import { SettingsForm } from "@/components/settings/settings-form";

export const dynamic = "force-dynamic";

export default async function SettingsPage() {
  const snapshot = await getSnapshot();
  return (
    <SettingsForm
      user={snapshot.user}
      mode={snapshot.mode}
      modeReason={snapshot.modeReason}
      connections={snapshot.connections}
      authEnabled={authEnabled()}
    />
  );
}
