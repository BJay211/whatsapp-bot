-- ============================================================================
-- Bonjee Bot — canonical schema
-- Target: PostgreSQL 14+ (tested syntax against PG15/16 on Render)
--
-- This file is IDEMPOTENT: every statement is IF NOT EXISTS / CREATE OR REPLACE.
-- It is a SUPERSET of db/schema.sql. On an existing installation it creates the
-- new tables and leaves legacy data untouched; run
-- database/migrations/002_extend_legacy.sql to add the new columns to `messages`
-- and `notifications` that CREATE TABLE IF NOT EXISTS cannot add.
--
-- Conventions
--   * ids are UUID default gen_random_uuid()  (pgcrypto, built in since PG13)
--   * timestamps are TIMESTAMPTZ and always store UTC; user timezone lives in
--     user_preferences, never in the column type
--   * statuses are TEXT + CHECK rather than enums, so adding a value is a
--     one-line migration instead of ALTER TYPE
--   * original message content is never overwritten by generated content
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- pgvector is optional. Everything else works without it; semantic search is
-- skipped at runtime when the extension is absent.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
    BEGIN
      CREATE EXTENSION vector;
    EXCEPTION WHEN OTHERS THEN
      RAISE NOTICE 'pgvector unavailable - semantic search disabled, keyword search still works';
    END;
  END IF;
END $$;

-- ---------------------------------------------------------------- helpers ---

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE IF NOT EXISTS schema_migrations (
  version     TEXT PRIMARY KEY,
  applied_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  note        TEXT
);

-- =========================================================== core catalog ===

-- Which systems we ingest from. One row per source (whatsapp, android, sms...).
CREATE TABLE IF NOT EXISTS app_sources (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_key   TEXT NOT NULL UNIQUE,             -- 'whatsapp', 'android', 'sms'
  display_name TEXT NOT NULL,
  kind         TEXT NOT NULL DEFAULT 'message'
               CHECK (kind IN ('message', 'notification', 'system')),
  enabled      BOOLEAN NOT NULL DEFAULT TRUE,
  config       JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO app_sources (source_key, display_name, kind) VALUES
  ('whatsapp', 'WhatsApp (Evolution API)', 'message'),
  ('android',  'Android companion app',    'notification')
ON CONFLICT (source_key) DO NOTHING;

-- One row for the human using the assistant. Settings that must never be
-- hardcoded (timezone, retention, model choice) live here.
CREATE TABLE IF NOT EXISTS user_preferences (
  user_id            TEXT PRIMARY KEY DEFAULT 'owner',
  display_name       TEXT,
  timezone           TEXT NOT NULL DEFAULT 'Africa/Lagos',
  locale             TEXT NOT NULL DEFAULT 'en-NG',
  llm_provider       TEXT NOT NULL DEFAULT 'openai',
  llm_model          TEXT NOT NULL DEFAULT 'gpt-4o-mini',
  embedding_provider TEXT NOT NULL DEFAULT 'openai',
  embedding_model    TEXT NOT NULL DEFAULT 'text-embedding-3-small',
  embedding_dims     INTEGER NOT NULL DEFAULT 1536,
  auto_create_tasks  BOOLEAN NOT NULL DEFAULT FALSE,  -- only on high confidence
  reminder_lead_minutes INTEGER NOT NULL DEFAULT 1440,
  briefing_time      TIME NOT NULL DEFAULT '07:00',
  retention_days     INTEGER NOT NULL DEFAULT 0,      -- 0 = keep forever
  created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO user_preferences (user_id) VALUES ('owner') ON CONFLICT (user_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS courses (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code        TEXT NOT NULL UNIQUE,          -- 'CSC 401'
  title       TEXT,
  aliases     TEXT[] NOT NULL DEFAULT '{}',  -- 'CSC401', 'Software Eng'
  lecturer     TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- People, groups, projects. Used for entity resolution across messages.
CREATE TABLE IF NOT EXISTS entities (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  entity_type TEXT NOT NULL CHECK (entity_type IN ('person', 'group', 'project', 'course', 'organisation', 'other')),
  canonical   TEXT NOT NULL,                 -- normalised display name
  aliases     TEXT[] NOT NULL DEFAULT '{}',
  metadata    JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (entity_type, canonical)
);

-- ========================================================= message archive ===

CREATE TABLE IF NOT EXISTS chats (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_id     UUID NOT NULL REFERENCES app_sources(id) ON DELETE CASCADE,
  external_id   TEXT NOT NULL,               -- WhatsApp JID / Evolution remoteJid
  chat_type     TEXT NOT NULL DEFAULT 'group'
                CHECK (chat_type IN ('dm', 'group', 'channel', 'broadcast', 'unknown')),
  name          TEXT,
  is_monitored  BOOLEAN NOT NULL DEFAULT TRUE,
  course_id     UUID REFERENCES courses(id) ON DELETE SET NULL,
  last_message_at TIMESTAMPTZ,
  metadata      JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (source_id, external_id)
);

CREATE TABLE IF NOT EXISTS chat_members (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  chat_id            UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
  member_external_id TEXT NOT NULL,
  display_name       TEXT,
  role               TEXT,                   -- 'lecturer', 'rep', 'student', 'unknown'
  first_seen_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_seen_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (chat_id, member_external_id)
);

-- `messages` is the superset of the legacy table created by db/schema.sql.
-- Legacy columns are preserved exactly so existing n8n inserts keep working.
CREATE TABLE IF NOT EXISTS messages (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  external_id        TEXT NOT NULL UNIQUE,        -- Evolution message id (legacy UNIQUE)
  source             TEXT NOT NULL DEFAULT 'whatsapp',
  source_id          UUID REFERENCES app_sources(id) ON DELETE SET NULL,
  chat_id            TEXT NOT NULL,               -- legacy: WhatsApp JID
  chat_uuid          UUID REFERENCES chats(id) ON DELETE SET NULL,
  chat_name          TEXT,
  is_group           BOOLEAN NOT NULL DEFAULT FALSE,
  sender_id          TEXT,
  sender_name        TEXT,
  message_type       TEXT NOT NULL DEFAULT 'text',
  content            TEXT,
  caption            TEXT,
  quoted_external_id TEXT,
  is_forwarded       BOOLEAN NOT NULL DEFAULT FALSE,
  mentioned_me       BOOLEAN NOT NULL DEFAULT FALSE,
  has_media          BOOLEAN NOT NULL DEFAULT FALSE,
  "timestamp"        TIMESTAMPTZ NOT NULL,
  language           TEXT,
  content_hash       TEXT,
  edit_status        TEXT NOT NULL DEFAULT 'original'
                     CHECK (edit_status IN ('original', 'edited', 'deleted', 'unknown')),
  deleted_at         TIMESTAMPTZ,
  processed          BOOLEAN NOT NULL DEFAULT FALSE,
  raw_payload        JSONB,
  search_vector      TSVECTOR GENERATED ALWAYS AS (
                       to_tsvector('simple',
                         coalesce(content, '') || ' ' ||
                         coalesce(caption, '') || ' ' ||
                         coalesce(chat_name, '') || ' ' ||
                         coalesce(sender_name, ''))
                     ) STORED,
  ingested_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Explicit reply/quote and semantic links. Direction matters.
CREATE TABLE IF NOT EXISTS message_references (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  from_message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  to_message_id   UUID REFERENCES messages(id) ON DELETE SET NULL,
  relation        TEXT NOT NULL
                  CHECK (relation IN ('REPLIES_TO','REFERENCES','CLARIFIES','UPDATES',
                                      'SUPERSEDES','CONTRADICTS','CONFIRMS','CANCELS','RELATES_TO')),
  confidence      NUMERIC(4,3) CHECK (confidence BETWEEN 0 AND 1),
  reason          TEXT,
  detected_by     TEXT NOT NULL DEFAULT 'rule' CHECK (detected_by IN ('rule','llm','manual')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (from_message_id, to_message_id, relation)
);

CREATE TABLE IF NOT EXISTS message_media (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  message_id   UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  media_type   TEXT NOT NULL,                -- image, audio, video, document, sticker
  mime_type    TEXT,
  file_name    TEXT,
  remote_url   TEXT,
  storage_key  TEXT,                         -- object storage path, if archived
  size_bytes   BIGINT,
  sha256       TEXT,
  status       TEXT NOT NULL DEFAULT 'referenced'
               CHECK (status IN ('referenced','downloading','downloaded','missing','rejected','transcribed')),
  transcript   TEXT,
  ocr_text     TEXT,
  metadata     JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ====================================================== knowledge / tasks ===

CREATE TABLE IF NOT EXISTS tasks (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  kind            TEXT NOT NULL DEFAULT 'task'
                  CHECK (kind IN ('assignment','exam','quiz','lecture','meeting','event','task','reminder')),
  title           TEXT NOT NULL,
  description     TEXT,
  course_id       UUID REFERENCES courses(id) ON DELETE SET NULL,
  course_code     TEXT,                          -- denormalised when unresolved
  chat_id         UUID REFERENCES chats(id) ON DELETE SET NULL,
  issuer_name     TEXT,
  requirements    JSONB NOT NULL DEFAULT '[]'::jsonb,   -- [{text, done}]
  due_at          TIMESTAMPTZ,
  due_is_uncertain BOOLEAN NOT NULL DEFAULT TRUE,
  starts_at       TIMESTAMPTZ,
  ends_at         TIMESTAMPTZ,
  venue           TEXT,
  priority        TEXT NOT NULL DEFAULT 'normal' CHECK (priority IN ('low','normal','high','urgent')),
  status          TEXT NOT NULL DEFAULT 'detected'
                  CHECK (status IN ('detected','needs_confirmation','confirmed','in_progress',
                                    'completed','cancelled','overdue')),
  confidence      NUMERIC(4,3) CHECK (confidence BETWEEN 0 AND 1),
  missing_info    JSONB NOT NULL DEFAULT '[]'::jsonb,
  dedupe_key      TEXT UNIQUE,                   -- stable key to stop duplicate tasks
  calendar_event_id TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS task_evidence (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id    UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  role       TEXT NOT NULL DEFAULT 'supports'
             CHECK (role IN ('supports','announcement','clarification','correction','cancellation')),
  quote      TEXT,                              -- exact excerpt, never paraphrased
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (task_id, message_id, role)
);

-- History of every change to a task's dates/status, with the evidence.
CREATE TABLE IF NOT EXISTS task_revisions (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id        UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  changed_fields TEXT[] NOT NULL,
  old_values     JSONB NOT NULL,
  new_values     JSONB NOT NULL,
  message_id     UUID REFERENCES messages(id) ON DELETE SET NULL,
  reason         TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS decisions (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  title       TEXT NOT NULL,
  decision    TEXT NOT NULL,
  decided_at  TIMESTAMPTZ,
  course_id   UUID REFERENCES courses(id) ON DELETE SET NULL,
  status      TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','superseded','cancelled')),
  superseded_by UUID REFERENCES decisions(id) ON DELETE SET NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS events (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  title          TEXT NOT NULL,
  event_type     TEXT NOT NULL DEFAULT 'lecture'
                 CHECK (event_type IN ('lecture','exam','meeting','deadline','reminder','other')),
  course_id      UUID REFERENCES courses(id) ON DELETE SET NULL,
  starts_at      TIMESTAMPTZ NOT NULL,
  ends_at        TIMESTAMPTZ,
  all_day        BOOLEAN NOT NULL DEFAULT FALSE,
  venue          TEXT,
  status         TEXT NOT NULL DEFAULT 'scheduled'
                 CHECK (status IN ('scheduled','confirmed','cancelled','moved','completed')),
  calendar_event_id TEXT,
  dedupe_key     TEXT UNIQUE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ------------------------------------------------------------- embeddings ---

CREATE TABLE IF NOT EXISTS message_embeddings (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  message_id  UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  provider    TEXT NOT NULL,
  model       TEXT NOT NULL,
  dims        INTEGER NOT NULL,
  content_hash TEXT,
  -- `embedding` is added by the DO block below only when pgvector exists.
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (message_id, provider, model)
);

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
    BEGIN
      EXECUTE 'ALTER TABLE message_embeddings ADD COLUMN IF NOT EXISTS embedding vector(1536)';
    EXCEPTION WHEN OTHERS THEN
      RAISE NOTICE 'could not add vector column: %', SQLERRM;
    END;
    BEGIN
      EXECUTE 'CREATE INDEX IF NOT EXISTS idx_message_embeddings_hnsw
               ON message_embeddings USING hnsw (embedding vector_cosine_ops)';
    EXCEPTION WHEN OTHERS THEN
      RAISE NOTICE 'hnsw index unavailable (older pgvector?) - falling back to no vector index';
    END;
  END IF;
END $$;

-- ================================================================ workflow ===

CREATE TABLE IF NOT EXISTS processing_jobs (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  job_type       TEXT NOT NULL,              -- 'extract','embed','relate','reminder_sync'
  message_id     UUID REFERENCES messages(id) ON DELETE CASCADE,
  task_id        UUID REFERENCES tasks(id) ON DELETE CASCADE,
  payload        JSONB NOT NULL DEFAULT '{}'::jsonb,
  status         TEXT NOT NULL DEFAULT 'pending'
                 CHECK (status IN ('pending','running','done','failed','dead')),
  attempts       INTEGER NOT NULL DEFAULT 0,
  max_attempts   INTEGER NOT NULL DEFAULT 5,
  last_error     TEXT,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  locked_at      TIMESTAMPTZ,
  locked_by      TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (job_type, message_id, task_id)
);

CREATE TABLE IF NOT EXISTS reminders (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id       UUID REFERENCES tasks(id) ON DELETE CASCADE,
  event_id      UUID REFERENCES events(id) ON DELETE CASCADE,
  remind_at     TIMESTAMPTZ NOT NULL,
  channel       TEXT NOT NULL DEFAULT 'android' CHECK (channel IN ('android','email','whatsapp','none')),
  status        TEXT NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending','scheduled','sent','failed','cancelled')),
  dedupe_key    TEXT UNIQUE,
  payload       JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (task_id IS NOT NULL OR event_id IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS notification_deliveries (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  reminder_id    UUID REFERENCES reminders(id) ON DELETE CASCADE,
  channel        TEXT NOT NULL,
  provider_ref   TEXT,
  status         TEXT NOT NULL DEFAULT 'queued'
                 CHECK (status IN ('queued','sent','delivered','failed','unknown')),
  attempted_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  error          TEXT,
  detail         JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS workflow_errors (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workflow_name TEXT,
  execution_id TEXT,
  node_name    TEXT,
  error_type   TEXT,
  message      TEXT,
  payload      JSONB,
  resolved     BOOLEAN NOT NULL DEFAULT FALSE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS audit_logs (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  actor       TEXT NOT NULL DEFAULT 'system',
  action      TEXT NOT NULL,
  target_type TEXT,
  target_id   TEXT,
  detail      JSONB NOT NULL DEFAULT '{}'::jsonb,
  ip          INET,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ================================================== legacy (kept as-is) ====
-- These predate the canonical schema and are still written by Bonjee_bot.json.

CREATE TABLE IF NOT EXISTS settings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key VARCHAR(255) UNIQUE NOT NULL,
  value JSONB NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

INSERT INTO settings (key, value) VALUES
  ('summary_config', '{"debounce_minutes_nonurgent": 10, "debounce_minutes_urgent": 2, "burst_threshold": 5, "burst_window_minutes": 15, "priority_threshold": 60, "max_items_per_summary": 100}'),
  ('notification_filter', '{"mode": "all", "allowlist": [], "blocklist": []}'),
  ('whatsapp_filter', '{"ignore_own_messages": true, "ignore_groups": [], "priority_groups": []}')
ON CONFLICT (key) DO NOTHING;

CREATE TABLE IF NOT EXISTS notifications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  external_id VARCHAR(255) UNIQUE,
  app_package VARCHAR(255) NOT NULL,
  app_name VARCHAR(255),
  title TEXT,
  content TEXT,
  ticker TEXT,
  post_time TIMESTAMPTZ NOT NULL,
  is_clearable BOOLEAN DEFAULT TRUE,
  category VARCHAR(100),
  raw_payload JSONB,
  processed BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS context_state (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  last_summary_at TIMESTAMPTZ DEFAULT '1970-01-01 00:00:00+00',
  last_message_ts TIMESTAMPTZ DEFAULT '1970-01-01 00:00:00+00',
  last_notification_ts TIMESTAMPTZ DEFAULT '1970-01-01 00:00:00+00',
  debounce_until TIMESTAMPTZ DEFAULT NOW(),
  priority_score INTEGER DEFAULT 0,
  consecutive_bursts INTEGER DEFAULT 0,
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

INSERT INTO context_state (id) SELECT gen_random_uuid()
WHERE NOT EXISTS (SELECT 1 FROM context_state);

CREATE TABLE IF NOT EXISTS summaries (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  summary_text TEXT NOT NULL,
  summary_json JSONB NOT NULL,
  trigger_reason VARCHAR(100),
  message_count INTEGER DEFAULT 0,
  notification_count INTEGER DEFAULT 0,
  time_range_start TIMESTAMPTZ,
  time_range_end TIMESTAMPTZ,
  generated_at TIMESTAMPTZ DEFAULT NOW(),
  read BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS deadlines (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  summary_id UUID REFERENCES summaries(id) ON DELETE CASCADE,
  task TEXT NOT NULL,
  due_date TIMESTAMPTZ,
  due_text VARCHAR(255),
  source_type VARCHAR(50),
  source_id UUID,
  source_chat_app VARCHAR(255),
  priority VARCHAR(20) DEFAULT 'medium',
  completed BOOLEAN DEFAULT FALSE,
  confidence DECIMAL(3,2),
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS action_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  summary_id UUID REFERENCES summaries(id) ON DELETE CASCADE,
  action TEXT NOT NULL,
  assigned_to VARCHAR(255),
  source_type VARCHAR(50),
  source_id UUID,
  source_chat_app VARCHAR(255),
  priority VARCHAR(20) DEFAULT 'medium',
  completed BOOLEAN DEFAULT FALSE,
  due_date TIMESTAMPTZ,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ================================================================ indexes ===

CREATE INDEX IF NOT EXISTS idx_messages_timestamp      ON messages ("timestamp" DESC);
CREATE INDEX IF NOT EXISTS idx_messages_chat           ON messages (chat_id, "timestamp" DESC);
CREATE INDEX IF NOT EXISTS idx_messages_chat_uuid      ON messages (chat_uuid, "timestamp" DESC);
CREATE INDEX IF NOT EXISTS idx_messages_sender         ON messages (sender_id);
CREATE INDEX IF NOT EXISTS idx_messages_source_ext     ON messages (source_id, external_id);
CREATE INDEX IF NOT EXISTS idx_messages_processed      ON messages (processed) WHERE processed = FALSE;
CREATE INDEX IF NOT EXISTS idx_messages_mentioned      ON messages (mentioned_me) WHERE mentioned_me;
CREATE INDEX IF NOT EXISTS idx_messages_search         ON messages USING GIN (search_vector);
CREATE INDEX IF NOT EXISTS idx_messages_fts_legacy     ON messages USING GIN (to_tsvector('simple', coalesce(content, '')));
CREATE INDEX IF NOT EXISTS idx_messages_quoted         ON messages (quoted_external_id) WHERE quoted_external_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_refs_from               ON message_references (from_message_id);
CREATE INDEX IF NOT EXISTS idx_refs_to                 ON message_references (to_message_id);
CREATE INDEX IF NOT EXISTS idx_media_message           ON message_media (message_id);
CREATE INDEX IF NOT EXISTS idx_media_status            ON message_media (status) WHERE status <> 'downloaded';

CREATE INDEX IF NOT EXISTS idx_chats_source            ON chats (source_id);
CREATE INDEX IF NOT EXISTS idx_chats_last_msg          ON chats (last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_members_chat       ON chat_members (chat_id);

CREATE INDEX IF NOT EXISTS idx_tasks_due               ON tasks (due_at) WHERE status NOT IN ('completed','cancelled');
CREATE INDEX IF NOT EXISTS idx_tasks_status            ON tasks (status);
CREATE INDEX IF NOT EXISTS idx_tasks_course            ON tasks (course_id);
CREATE INDEX IF NOT EXISTS idx_tasks_open_due          ON tasks (due_at) WHERE status IN ('detected','needs_confirmation','confirmed','in_progress');
CREATE INDEX IF NOT EXISTS idx_task_evidence_task      ON task_evidence (task_id);
CREATE INDEX IF NOT EXISTS idx_task_evidence_msg       ON task_evidence (message_id);
CREATE INDEX IF NOT EXISTS idx_task_revisions_task     ON task_revisions (task_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_events_start            ON events (starts_at);
CREATE INDEX IF NOT EXISTS idx_decisions_status        ON decisions (status);

CREATE INDEX IF NOT EXISTS idx_jobs_pickable           ON processing_jobs (status, next_attempt_at)
  WHERE status IN ('pending','failed');
CREATE INDEX IF NOT EXISTS idx_jobs_message            ON processing_jobs (message_id);
CREATE INDEX IF NOT EXISTS idx_jobs_stale              ON processing_jobs (locked_at) WHERE status = 'running';

CREATE INDEX IF NOT EXISTS idx_reminders_due           ON reminders (remind_at) WHERE status IN ('pending','scheduled');
CREATE INDEX IF NOT EXISTS idx_deliveries_reminder     ON notification_deliveries (reminder_id);

CREATE INDEX IF NOT EXISTS idx_workflow_errors_open    ON workflow_errors (created_at DESC) WHERE resolved = FALSE;
CREATE INDEX IF NOT EXISTS idx_audit_target            ON audit_logs (target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_audit_created           ON audit_logs (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notifications_post_time ON notifications (post_time DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_app       ON notifications (app_package);
CREATE INDEX IF NOT EXISTS idx_notifications_processed ON notifications (processed) WHERE processed = FALSE;
CREATE INDEX IF NOT EXISTS idx_notifications_fts       ON notifications USING GIN (to_tsvector('simple', coalesce(content, '')));
CREATE INDEX IF NOT EXISTS idx_deadlines_due           ON deadlines (due_date);
CREATE INDEX IF NOT EXISTS idx_summaries_generated     ON summaries (generated_at DESC);

-- =============================================================== triggers ===

DO $$
DECLARE t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'app_sources','user_preferences','courses','entities','chats','messages',
    'message_media','tasks','decisions','events','processing_jobs','reminders'
  ] LOOP
    EXECUTE format('DROP TRIGGER IF EXISTS trg_%1$s_updated ON %1$I', t);
    EXECUTE format('CREATE TRIGGER trg_%1$s_updated BEFORE UPDATE ON %1$I
                    FOR EACH ROW EXECUTE FUNCTION set_updated_at()', t);
  END LOOP;
END $$;

INSERT INTO schema_migrations (version, note) VALUES ('001_initial', 'canonical schema')
ON CONFLICT (version) DO NOTHING;
