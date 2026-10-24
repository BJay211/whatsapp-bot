-- 001_initial.sql
-- Fresh install: creates the canonical schema.
-- Run with psql so the \ir include works:
--     psql "$DATABASE_URL" -f database/migrations/001_initial.sql
-- (database/schema.sql is the same content; this file only exists so that
--  migrations/ can be applied in version order without duplication.)

\ir ../schema.sql

INSERT INTO schema_migrations (version, note) VALUES ('001_initial', 'canonical schema')
ON CONFLICT (version) DO NOTHING;
