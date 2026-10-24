-- ============================================================================
-- Bonjee Bot — indexes.sql
--
-- The hot-path indexes are created by database/schema.sql. This file holds
-- (a) optional tuning indexes you can add later, and (b) verification queries.
-- Everything here is IF NOT EXISTS, so it is safe to run repeatedly.
-- ============================================================================

-- ---------------------------------------------------------------- optional ---

-- Trigram search for partial words / typos ("CSC401" vs "CSC 401").
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_messages_content_trgm
  ON messages USING GIN (content gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_chats_name_trgm
  ON chats USING GIN (name gin_trgm_ops);

-- Covering index for the most common Inbox query: newest messages per chat.
CREATE INDEX IF NOT EXISTS idx_messages_inbox_cover
  ON messages (chat_id, "timestamp" DESC)
  INCLUDE (sender_name, message_type, content);

-- Tasks the briefing job reads every morning.
CREATE INDEX IF NOT EXISTS idx_tasks_briefing
  ON tasks (due_at, priority)
  WHERE status IN ('detected','needs_confirmation','confirmed','in_progress');

-- Recent failures for the recovery workflow.
CREATE INDEX IF NOT EXISTS idx_jobs_dead
  ON processing_jobs (updated_at DESC) WHERE status = 'dead';

-- ------------------------------------------------------------ verification ---

-- Tables that should exist (expect 26 rows on a fresh install).
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;

-- Indexes with zero scans since the last statistics reset (candidates for removal).
SELECT relname AS table_name,
       indexrelname AS index_name,
       idx_scan,
       pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes
WHERE idx_scan = 0
ORDER BY pg_relation_size(indexrelid) DESC
LIMIT 25;

-- Unused space / bloat signal on the big tables.
SELECT relname,
       n_live_tup,
       n_dead_tup,
       pg_size_pretty(pg_total_relation_size(relid)) AS total_size
FROM pg_stat_user_tables
WHERE relname IN ('messages','notifications','message_embeddings','processing_jobs')
ORDER BY pg_total_relation_size(relid) DESC;
