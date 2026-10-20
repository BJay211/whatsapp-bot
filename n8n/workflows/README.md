# n8n Workflows

These workflows are designed to be imported into your n8n instance. 

## Workflows

| Workflow | Purpose | Webhook Path |
| :-- | :-- | :-- |
| `notifications-ingest.json` | Receives POSTs from the Android notifier app. Normalizes and stores all notifications. Triggers summarizer on new items. | `/webhook/notifications-ingest` |
| `whatsapp-ingest.json` | Receives webhooks from Evolution API for new WhatsApp messages (DMs + Groups). Filters out your own messages. Triggers summarizer. | `/webhook/whatsapp-ingest` |
| `context-summarizer.json` | Context-aware summarization. Evaluates urgency, mentions, time entities, bursts and decides if a summary should be generated. Uses LLM to extract deadlines, action items, urgent and FYI. | Triggered internally via "Execute Workflow" | 

## Setup Instructions

1. Import each JSON file into n8n (`Workflows > Import from file`)
2. For `context-summarizer.json`, update the LLM credential node (`OpenAI/Gemini/Anthropic` etc) - replace `YOUR_OPENAI_CREDENTIAL_ID` with your own credential
3. Create a Postgres credential in n8n for your Render Postgres DB and assign it to all Postgres nodes in all workflows
4. Activate all workflows
5. Copy the webhook URLs from n8n and use them:
   - WhatsApp ingest URL -> Configure in Evolution API as the webhook for your instance
   - Notifications ingest URL -> Configure in the Android app

## Notes

- The summarizer uses a heuristic scoring system first (cheap) and then calls an LLM only if it should summarize. This keeps costs low while being context aware.
- Adjust thresholds in the `summary_config` table after first use (e.g `priority_threshold`, `debounce_minutes_nonurgent`, `burst_threshold`) to tune to your preferences.
- By default, the summarizer only processes messages/notifications that are marked as `processed=false`. After summarizing a batch, they're marked as processed.
- If you want to change the LLM model, edit the "Generate Summary" node in `context-summarizer.json`. I'd recommend starting with `gpt-4o-mini` or `gemini-2.5-flash` for cost efficiency.
