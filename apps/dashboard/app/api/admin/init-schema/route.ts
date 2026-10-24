import { NextResponse } from "next/server";
import { Pool } from "pg";

export const dynamic = "force-dynamic";

function getPool() {
  const connectionString = process.env.DATABASE_URL!;
  const needsSsl =
    /sslmode=require/i.test(connectionString) ||
    process.env.DATABASE_SSL === "require" ||
    /render\.com/i.test(connectionString) ||
    (process.env.NODE_ENV === "production" && !/localhost|127\.0\.0\.1/.test(connectionString));
  return new Pool({
    connectionString,
    max: 1,
    connectionTimeoutMillis: 5000,
    ssl: needsSsl && !/localhost|127\.0\.0\.1/.test(connectionString)
      ? { rejectUnauthorized: false }
      : undefined,
  });
}

export async function POST() {
  const db = getPool();
  try {
    await db.query(`
      CREATE TABLE IF NOT EXISTS user_preferences (
        user_id text PRIMARY KEY,
        display_name text,
        timezone text,
        locale text,
        llm_provider text,
        llm_model text,
        embedding_model text,
        auto_create_tasks boolean DEFAULT false,
        reminder_lead_minutes integer DEFAULT 1440,
        briefing_time text DEFAULT '07:00',
        retention_days integer DEFAULT 0,
        updated_at timestamptz DEFAULT now()
      );
    `);
    await db.query(`
      CREATE TABLE IF NOT EXISTS courses (
        code text PRIMARY KEY,
        title text,
        lecturer text
      );
    `);
    await db.query(`
      CREATE TABLE IF NOT EXISTS chats (
        id text PRIMARY KEY,
        name text,
        is_group boolean DEFAULT false,
        source text
      );
    `);
    await db.query(`
      CREATE TABLE IF NOT EXISTS messages (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        external_id text UNIQUE,
        source text,
        chat_id text,
        chat_name text,
        is_group boolean DEFAULT false,
        sender_id text,
        sender_name text,
        message_type text,
        content text,
        caption text,
        "timestamp" timestamptz,
        is_forwarded boolean DEFAULT false,
        mentioned_me boolean DEFAULT false,
        has_media boolean DEFAULT false,
        raw_payload jsonb
      );
    `);
    await db.query(`
      CREATE TABLE IF NOT EXISTS tasks (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        kind text,
        title text,
        description text,
        course_code text,
        due_at timestamptz,
        due_is_uncertain boolean DEFAULT false,
        starts_at timestamptz,
        venue text,
        priority text,
        status text DEFAULT 'open',
        confidence real,
        requirements jsonb DEFAULT '[]'::jsonb,
        missing_info jsonb DEFAULT '[]'::jsonb,
        issuer_name text,
        chat_id text,
        created_at timestamptz DEFAULT now(),
        updated_at timestamptz DEFAULT now()
      );
    `);
    await db.query(`
      CREATE TABLE IF NOT EXISTS task_evidence (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        task_id uuid REFERENCES tasks(id) ON DELETE CASCADE,
        message_id uuid REFERENCES messages(id) ON DELETE CASCADE,
        role text,
        quote text
      );
    `);
    await db.query(`
      CREATE TABLE IF NOT EXISTS task_revisions (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        task_id uuid REFERENCES tasks(id) ON DELETE CASCADE,
        message_id uuid REFERENCES messages(id) ON DELETE SET NULL,
        changed_fields jsonb,
        old_values jsonb,
        new_values jsonb,
        reason text,
        created_at timestamptz DEFAULT now()
      );
    `);
    await db.query(`
      CREATE TABLE IF NOT EXISTS message_references (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        from_message_id uuid REFERENCES messages(id) ON DELETE CASCADE,
        to_message_id uuid REFERENCES messages(id) ON DELETE CASCADE,
        relation text,
        confidence real
      );
    `);
    await db.query(`
      CREATE TABLE IF NOT EXISTS events (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        title text,
        event_type text,
        course_id text,
        starts_at timestamptz,
        ends_at timestamptz,
        all_day boolean DEFAULT false,
        venue text,
        status text DEFAULT 'scheduled'
      );
    `);
    await db.query(`
      CREATE TABLE IF NOT EXISTS audit_logs (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        actor text,
        action text,
        target_type text,
        target_id text,
        detail jsonb,
        created_at timestamptz DEFAULT now()
      );
    `);
    await db.query(`
      INSERT INTO user_preferences (user_id) VALUES ('owner') ON CONFLICT (user_id) DO NOTHING;
    `);
    return NextResponse.json({ ok: true, message: "Schema initialized" });
  } catch (error: any) {
    return NextResponse.json({ ok: false, error: error?.message || String(error) }, { status: 500 });
  }
}

export async function GET() {
  return POST();
}
