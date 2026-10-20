-- Bonjee Bot Database Schema
-- Designed for Postgres (works on Render Postgres)

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Settings table for configuration
CREATE TABLE IF NOT EXISTS settings (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  key VARCHAR(255) UNIQUE NOT NULL,
  value JSONB NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Insert default settings
INSERT INTO settings (key, value) VALUES
  ('summary_config', '{"debounce_minutes_nonurgent": 10, "debounce_minutes_urgent": 2, "burst_threshold": 5, "burst_window_minutes": 15, "priority_threshold": 60, "max_items_per_summary": 100}'),
  ('notification_filter', '{"mode": "all", "allowlist": [], "blocklist": []}'),
  ('whatsapp_filter', '{"ignore_own_messages": true, "ignore_groups": [], "priority_groups": []}')
ON CONFLICT (key) DO NOTHING;

-- Messages from WhatsApp (DMs and Groups)
CREATE TABLE IF NOT EXISTS messages (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  external_id VARCHAR(255) UNIQUE NOT NULL, -- Evolution API message ID
  source VARCHAR(50) NOT NULL DEFAULT 'whatsapp',
  chat_id VARCHAR(255) NOT NULL, -- WhatsApp JID
  chat_name VARCHAR(255), -- Group name or contact name
  is_group BOOLEAN DEFAULT FALSE,
  sender_id VARCHAR(255),
  sender_name VARCHAR(255),
  message_type VARCHAR(50) DEFAULT 'text', -- text, image, video, audio, etc
  content TEXT,
  has_media BOOLEAN DEFAULT FALSE,
  mentioned_me BOOLEAN DEFAULT FALSE,
  timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
  raw_payload JSONB,
  processed BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Notifications from Android
CREATE TABLE IF NOT EXISTS notifications (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  external_id VARCHAR(255) UNIQUE, -- Optional deduplication key from app
  app_package VARCHAR(255) NOT NULL,
  app_name VARCHAR(255),
  title TEXT,
  content TEXT,
  ticker TEXT,
  post_time TIMESTAMP WITH TIME ZONE NOT NULL,
  is_clearable BOOLEAN DEFAULT TRUE,
  category VARCHAR(100),
  raw_payload JSONB,
  processed BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Context state for timing decisions
CREATE TABLE IF NOT EXISTS context_state (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  last_summary_at TIMESTAMP WITH TIME ZONE DEFAULT '1970-01-01 00:00:00+00',
  last_message_ts TIMESTAMP WITH TIME ZONE DEFAULT '1970-01-01 00:00:00+00',
  last_notification_ts TIMESTAMP WITH TIME ZONE DEFAULT '1970-01-01 00:00:00+00',
  debounce_until TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  priority_score INTEGER DEFAULT 0,
  consecutive_bursts INTEGER DEFAULT 0,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Insert initial context state
INSERT INTO context_state (id) VALUES (uuid_generate_v4()) ON CONFLICT DO NOTHING;

-- Summaries with extracted info
CREATE TABLE IF NOT EXISTS summaries (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  summary_text TEXT NOT NULL,
  summary_json JSONB NOT NULL, -- Contains urgent, deadlines, action_items, fyi, etc
  trigger_reason VARCHAR(100), -- urgent_keyword, mention, burst, time_entity, etc
  message_count INTEGER DEFAULT 0,
  notification_count INTEGER DEFAULT 0,
  time_range_start TIMESTAMP WITH TIME ZONE,
  time_range_end TIMESTAMP WITH TIME ZONE,
  generated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  read BOOLEAN DEFAULT FALSE
);

-- Deadlines extracted from messages/notifications
CREATE TABLE IF NOT EXISTS deadlines (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  summary_id UUID REFERENCES summaries(id) ON DELETE CASCADE,
  task TEXT NOT NULL,
  due_date TIMESTAMP WITH TIME ZONE,
  due_text VARCHAR(255), -- Original text like "tomorrow at 5pm"
  source_type VARCHAR(50), -- whatsapp, notification
  source_id UUID, -- Reference to message or notification
  source_chat_app VARCHAR(255), -- Chat name or app name
  priority VARCHAR(20) DEFAULT 'medium', -- low, medium, high, urgent
  completed BOOLEAN DEFAULT FALSE,
  confidence DECIMAL(3,2), -- 0.00 to 1.00
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Action items extracted
CREATE TABLE IF NOT EXISTS action_items (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  summary_id UUID REFERENCES summaries(id) ON DELETE CASCADE,
  action TEXT NOT NULL,
  assigned_to VARCHAR(255), -- Who needs to do it (could be "me")
  source_type VARCHAR(50),
  source_id UUID,
  source_chat_app VARCHAR(255),
  priority VARCHAR(20) DEFAULT 'medium',
  completed BOOLEAN DEFAULT FALSE,
  due_date TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_messages_timestamp ON messages(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_messages_chat_id ON messages(chat_id);
CREATE INDEX IF NOT EXISTS idx_messages_processed ON messages(processed);
CREATE INDEX IF NOT EXISTS idx_messages_mentioned_me ON messages(mentioned_me);

CREATE INDEX IF NOT EXISTS idx_notifications_post_time ON notifications(post_time DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_app_package ON notifications(app_package);
CREATE INDEX IF NOT EXISTS idx_notifications_processed ON notifications(processed);

CREATE INDEX IF NOT EXISTS idx_summaries_generated_at ON summaries(generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_deadlines_due_date ON deadlines(due_date);
CREATE INDEX IF NOT EXISTS idx_deadlines_completed ON deadlines(completed);
CREATE INDEX IF NOT EXISTS idx_action_items_completed ON action_items(completed);

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ language 'plpgsql';

-- Triggers
CREATE TRIGGER update_settings_updated_at BEFORE UPDATE ON settings
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_context_state_updated_at BEFORE UPDATE ON context_state
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
