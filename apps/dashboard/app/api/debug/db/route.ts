import { NextResponse } from "next/server";
import { Pool } from "pg";

export const dynamic = "force-dynamic";

export async function GET() {
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) {
    return NextResponse.json({ ok: false, error: "DATABASE_URL not set" }, { status: 500 });
  }
  
  const needsSsl =
    /sslmode=require/i.test(connectionString) ||
    process.env.DATABASE_SSL === "require" ||
    /render\.com/i.test(connectionString) ||
    process.env.NODE_ENV === "production";
  
  const pool = new Pool({
    connectionString,
    max: 1,
    connectionTimeoutMillis: 5000,
    ssl: needsSsl && !/localhost|127\.0\.0\.1/.test(connectionString)
      ? { rejectUnauthorized: false }
      : undefined,
  });
  
  try {
    const res = await pool.query("SELECT 1 as ok, now() as now");
    await pool.end();
    return NextResponse.json({ ok: true, result: res.rows[0], hasUrl: true }, { status: 200 });
  } catch (error: any) {
    await pool.end().catch(() => {});
    return NextResponse.json(
      { 
        ok: false, 
        error: error?.message || String(error),
        code: error?.code,
        hasUrl: true,
        urlPreview: connectionString.substring(0, 30) + "..."
      }, 
      { status: 500 }
    );
  }
}
