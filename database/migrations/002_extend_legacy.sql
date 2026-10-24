-- ============================================================================
-- 002_extend_legacy.sql
-- Upgrades a database created by the original db/schema.sql to the canonical
-- shape WITHOUT touching existing rows. Every statement is IF NOT EXISTS, so
-- it is safe to run more than once.
--
-- Run this ONLY if you previously executed db/schema.sql.
-- On a fresh database, database/schema.sql already creates everything.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE messages ADD COLUMN IF NOT EXISTS source_id          UUID;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS chat_uuid          UUID;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS caption            TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS quoted_external_id TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS is_forwarded       BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS content_hash       TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS edit_status        TEXT NOT NULL DEFAULT 'original';
ALTER TABLE messages ADD COLUMN IF NOT EXISTS deleted_at         TIMESTAMPTZ;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS language           TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS ingested_at        TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE messages ADD COLUMN IF NOT EXISTS updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Generated column: PostgreSQL 12+ supports adding STORED generated columns.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'messages' AND column_name = 'search_vector'
  ) THEN
    EXECUTE $sql$
      ALTER TABLE messages ADD COLUMN search_vector TSVECTOR
      GENERATED ALWAYS AS (
        to_tsvector('simple',
          coalesce(content, '') || ' ' ||
          coalesce(caption, '') || ' ' ||
          coalesce(chat_name, '') || ' ' ||
          coalesce(sender_name, ''))
      ) STORED
    $sql$;
  END IF;
END $$;

-- Constraints that were not present on the legacy table.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'messages_edit_status_chk') THEN
    ALTER TABLE messages ADD CONSTRAINT messages_edit_status_chk
      CHECK (edit_status IN ('original','edited','deleted','unknown'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'messages_source_id_fk') THEN
    ALTER TABLE messages ADD CONSTRAINT messages_source_id_fk
      FOREIGN KEY (source_id) REFERENCES app_sources(id) ON DELETE SET NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'messages_chat_uuid_fk') THEN
    ALTER TABLE messages ADD CONSTRAINT messages_chat_uuid_fk
      FOREIGN KEY (chat_uuid) REFERENCES chats(id) ON DELETE SET NULL;
  END IF;
END $$;

-- Backfill the chat catalogue from whatever is already in messages.
INSERT INTO chats (source_id, external_id, chat_type, name, last_message_at)
SELECT s.id,
       m.chat_id,
       CASE WHEN m.is_group THEN 'group' ELSE 'dm' END,
       max(m.chat_name),
       max(m."timestamp")
FROM messages m
JOIN app_sources s ON s.source_key = coalesce(m.source, 'whatsapp')
WHERE m.chat_id IS NOT NULL
GROUP BY s.id, m.chat_id, m.is_group
ON CONFLICT (source_id, external_id) DO NOTHING;

UPDATE messages m
SET source_id = coalesce(m.source_id, s.id),
    chat_uuid = c.id
FROM app_sources s, chats c
WHERE s.source_key = coalesce(m.source, 'whatsapp')
  AND c.source_id = s.id
  AND c.external_id = m.chat_id
  AND (m.source_id IS NULL OR m.chat_uuid IS NULL);

INSERT INTO schema_migrations (version, note) VALUES ('002_extend_legacy', 'added canonical columns to legacy messages table')
ON CONFLICT (version) DO NOTHING;
