-- ============================================================================
-- Synthetic seed data — reproduces the exact scenario from the specification:
--
--   Mon 2026-03-02  lecturer announces assignment, due Friday 2026-03-06
--   Tue 2026-03-03  another message explains the requirements
--   Thu 2026-03-05  lecturer moves the deadline to Monday 2026-03-09
--
-- It is entirely fictional. It exists so relationship detection, deadline
-- revision and RAG grounding can be tested without touching real conversations.
-- Idempotent: every insert has a fixed UUID and ON CONFLICT DO NOTHING.
-- ============================================================================

INSERT INTO courses (id, code, title, aliases, lecturer)
VALUES ('11111111-1111-4111-8111-111111111111', 'CSC 401', 'Software Engineering',
        ARRAY['CSC401', 'Software Eng'], 'Dr. Adeyemi')
ON CONFLICT (code) DO NOTHING;

INSERT INTO chats (id, source_id, external_id, chat_type, name, is_monitored, course_id, last_message_at)
SELECT '22222222-2222-4222-8222-222222222222', s.id, '120363000000000001@g.us',
       'group', 'CSC 401 — 2026 Set', TRUE, '11111111-1111-4111-8111-111111111111',
       '2026-03-05 18:40:00+00'
FROM app_sources s WHERE s.source_key = 'whatsapp'
ON CONFLICT (source_id, external_id) DO NOTHING;

INSERT INTO chat_members (chat_id, member_external_id, display_name, role)
VALUES ('22222222-2222-4222-8222-222222222222', '2348000000001@s.whatsapp.net', 'Dr. Adeyemi', 'lecturer'),
       ('22222222-2222-4222-8222-222222222222', '2348000000002@s.whatsapp.net', 'Chidi (Course Rep)', 'rep')
ON CONFLICT (chat_id, member_external_id) DO NOTHING;

INSERT INTO messages (id, external_id, source, source_id, chat_id, chat_uuid, chat_name,
                      is_group, sender_id, sender_name, message_type, content, "timestamp", processed)
SELECT v.id::uuid, v.ext, 'whatsapp', s.id, '120363000000000001@g.us',
       '22222222-2222-4222-8222-222222222222', 'CSC 401 — 2026 Set', TRUE,
       v.sender, v.sender_name, 'text', v.content, v.ts::timestamptz, TRUE
FROM (VALUES
  ('33333333-3333-4333-8333-333333333331', 'SEED-MON-001',
   '2348000000001@s.whatsapp.net', 'Dr. Adeyemi',
   'Good morning all. Your CSC 401 group project report is due this Friday, 6th March, 11:59pm. Submit on the portal.',
   '2026-03-02 09:15:00+00'),
  ('33333333-3333-4333-8333-333333333332', 'SEED-TUE-001',
   '2348000000002@s.whatsapp.net', 'Chidi (Course Rep)',
   'To add to what Dr Adeyemi said: the report must cover requirements analysis, architecture, testing and deployment. Max 20 pages. Include your GitHub link.',
   '2026-03-03 14:02:00+00'),
  ('33333333-3333-4333-8333-333333333333', 'SEED-THU-001',
   '2348000000001@s.whatsapp.net', 'Dr. Adeyemi',
   'Correction: the CSC 401 report deadline has been moved to Monday 9th March, 11:59pm. Same submission portal. Sorry for the change.',
   '2026-03-05 18:40:00+00')
) AS v(id, ext, sender, sender_name, content, ts)
JOIN app_sources s ON s.source_key = 'whatsapp'
ON CONFLICT (external_id) DO NOTHING;

-- The task as first created (Friday deadline), then revised to Monday.
INSERT INTO tasks (id, kind, title, description, course_id, course_code, chat_id,
                   issuer_name, requirements, due_at, due_is_uncertain, priority, status,
                   confidence, dedupe_key)
VALUES ('44444444-4444-4444-8444-444444444444', 'assignment',
        'CSC 401 group project report',
        'Final group project report for CSC 401.',
        '11111111-1111-4111-8111-111111111111', 'CSC 401',
        '22222222-2222-4222-8222-222222222222', 'Dr. Adeyemi',
        '[{"text":"Requirements analysis","done":false},
          {"text":"Architecture","done":false},
          {"text":"Testing","done":false},
          {"text":"Deployment","done":false},
          {"text":"Max 20 pages","done":false},
          {"text":"Include GitHub link","done":false}]'::jsonb,
        '2026-03-09 23:59:00+00', FALSE, 'high', 'confirmed', 0.950,
        'csc401:group-project-report:2026')
ON CONFLICT (dedupe_key) DO NOTHING;

INSERT INTO task_evidence (task_id, message_id, role, quote) VALUES
  ('44444444-4444-4444-8444-444444444444', '33333333-3333-4333-8333-333333333331', 'announcement',
   'your CSC 401 group project report is due this Friday, 6th March, 11:59pm'),
  ('44444444-4444-4444-8444-444444444444', '33333333-3333-4333-8333-333333333332', 'clarification',
   'the report must cover requirements analysis, architecture, testing and deployment'),
  ('44444444-4444-4444-8444-444444444444', '33333333-3333-4333-8333-333333333333', 'correction',
   'the CSC 401 report deadline has been moved to Monday 9th March, 11:59pm')
ON CONFLICT (task_id, message_id, role) DO NOTHING;

-- Revision history: the original Friday deadline is preserved, never overwritten.
INSERT INTO task_revisions (task_id, changed_fields, old_values, new_values, message_id, reason)
SELECT '44444444-4444-4444-8444-444444444444',
       ARRAY['due_at'], '{"due_at":"2026-03-06T23:59:00Z"}'::jsonb,
       '{"due_at":"2026-03-09T23:59:00Z"}'::jsonb,
       '33333333-3333-4333-8333-333333333333',
       'Lecturer explicitly moved the deadline in a later message'
WHERE NOT EXISTS (
  SELECT 1 FROM task_revisions
  WHERE task_id = '44444444-4444-4444-8444-444444444444'
    AND 'due_at' = ANY(changed_fields)
);

INSERT INTO message_references (from_message_id, to_message_id, relation, confidence, reason, detected_by) VALUES
  ('33333333-3333-4333-8333-333333333332', '33333333-3333-4333-8333-333333333331', 'CLARIFIES', 0.900,
   'Same course and assignment, adds requirements', 'rule'),
  ('33333333-3333-4333-8333-333333333333', '33333333-3333-4333-8333-333333333331', 'SUPERSEDES', 0.950,
   'Explicit "Correction" wording changing the due date', 'llm')
ON CONFLICT (from_message_id, to_message_id, relation) DO NOTHING;

INSERT INTO events (id, title, event_type, course_id, starts_at, ends_at, venue, status, dedupe_key)
VALUES ('55555555-5555-4555-8555-555555555555', 'CSC 401 Lecture', 'lecture',
        '11111111-1111-4111-8111-111111111111', '2026-03-06 08:00:00+00', '2026-03-06 10:00:00+00',
        'LT 3', 'scheduled', 'csc401:lecture:2026-03-06')
ON CONFLICT (dedupe_key) DO NOTHING;
