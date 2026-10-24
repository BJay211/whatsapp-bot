export const env = {
  n8nBaseUrl: process.env.N8N_BASE_URL?.replace(/\/$/, "") || "",
  evolutionApiUrl: process.env.EVOLUTION_API_URL?.replace(/\/$/, "") || "",
  evolutionApiKey: process.env.EVOLUTION_API_KEY || "",
  databaseUrl: process.env.DATABASE_URL || "",
  dashboardPassphrase: process.env.DASHBOARD_PASSPHRASE || "",
};
