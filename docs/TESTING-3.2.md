# JARVIS 3.2 - manual test checklist

This build was compiled and statically verified, but there was no phone, emulator or
`adb` available, so none of the runtime behaviour below has been observed on a device.
Use this list on the phone and treat any unchecked line as unverified.

Install `Bonjee-JARVIS-3.2-assistant.apk` (versionCode 14, versionName 3.2). Upgrading
over 3.1 keeps your data; the new stores are separate files.

## 1. Install and open

- [ ] Installs over 3.1 without "app not installed".
- [ ] Version shown in Android app info is 3.2.
- [ ] Home screen opens; the drawer shows Memory Core, Ask JARVIS, Inbox, Tasks,
      Calendar, Automations, Devices & n8n, Message Rules, Settings.
- [ ] Every drawer item opens a real screen (no blank white WebView, no "site can't be
      reached").

## 2. Chained commands

Type these in **Ask JARVIS** (TYPE mode) with WhatsApp installed and contacts permission
granted:

- [ ] `open WhatsApp then message John` -> "Done - all 2 steps", WhatsApp opens, then
      John's chat opens. If John is not in contacts, the reply says it opened WhatsApp
      to search and does **not** claim the chat opened.
- [ ] `open WhatsApp and message John` -> same two steps.
- [ ] `open WhatsApp and search for John then open the chat` -> "Done - all 3 steps".
      (Requires John to resolve to a number; otherwise step 2 honestly says he isn't in
      contacts and the chain stops.)
- [ ] `open contacts then call Ada` -> contacts opens, then the dialer/call.
- [ ] `torch on then brightness 60 percent` -> torch on, brightness changed.
- [ ] `message John saying I will call you then come home` -> **one** step. The message
      body is not cut at "then".
- [ ] `play next song` -> one step (media next), not a chain.
- [ ] `open WhatsApp then message John` with contacts permission denied: you are asked
      once, and after granting, the whole sentence runs from the start (nothing had run
      yet).
- [ ] `open WhatsApp then call John` with CALL_PHONE denied *after* WhatsApp opened:
      the reply says the first step already ran and the sentence will **not** be
      replayed automatically. No double call after granting.
- [ ] `what is on my calendar tomorrow` (no device match) -> goes to n8n and shows the
      real network/404 error, not a local guess.

## 3. Memory Core

- [ ] Drawer -> Memory Core. Graph draws a centre YOU node; with no data it says the
      graph is empty rather than inventing nodes.
- [ ] ADD NOTE saves; the note appears in the list and as a cyan graph node.
- [ ] Tap a note -> delete confirmation; deleting removes it from list and graph.
- [ ] Filter field narrows the list.
- [ ] Long-press a captured message -> opens Reply.
- [ ] SEARCH N8N with a query: shows `memory.search · request <id>` plus either the
      workflow's answer or the real HTTP error. Nothing is added to the local graph on
      failure.
- [ ] ASK opens Ask JARVIS with the query prefilled.

## 4. Tasks

- [ ] ADD TASK with a title and optional due string; it appears as an open task.
- [ ] Tap a task toggles done; it dims and moves to the bottom.
- [ ] Long-press deletes after confirmation.
- [ ] CLEAR DONE removes only completed tasks.
- [ ] SEND PENDING with the workflow undeployed: status shows "0 accepted, N failed"
      with the real error, and tasks still read "workflow: not sent". It must **not**
      say accepted.
- [ ] Restart the app: tasks persist.

## 5. Calendar

- [ ] Without permission, status says NOT granted and the list is empty.
- [ ] ALLOW -> grants READ_CALENDAR, upcoming events for 60 days appear.
- [ ] A recurring event appears on its next real date.
- [ ] All-day events show "all day"; same-day events show a start-end range.
- [ ] NEW EVENT opens the system calendar insert screen with times prefilled; the app
      itself does not save silently.

## 6. Automations

- [ ] Toggling "Capture WhatsApp notifications" persists across reopening the screen,
      and Inbox capture stops/starts accordingly.
- [ ] With the switch on but notification access missing, the hint says nothing is
      captured yet.
- [ ] Notification access button opens the system listener settings.
- [ ] Quiet hours: enabling stores the times; entering `25:99` does not overwrite them.
- [ ] TEST ANNOUNCEMENT speaks only when the current audio state allows it; the status
      line names the reason when it is held.
- [ ] Auto-reply card states the truth: local switch on but zero server-authorised
      numbers means nothing is sent.
- [ ] Tap assist card reflects the accessibility service state, not a hardcoded "on".
- [ ] REGISTER THIS DEVICE with the workflow undeployed shows the real HTTP error and
      does not set a registration time.

## 7. Regressions to confirm still work

- [ ] Calling, SMS, contacts search, email, alarms, timers, torch, volume, brightness,
      media, flashlight, device info.
- [ ] Important WhatsApp notification announcement obeying silent/DND/quiet hours and
      earphones. JARVIS must never claim to override DND.
- [ ] Reply flow: Reply myself / Let JARVIS reply / instructions / read aloud, then
      Edit - Approve - Cancel with the exact final text and the real send status.
- [ ] Message rules: Important / Auto-Reply / Other, E.164 dedupe, kill switch.
- [ ] Ask JARVIS falls back to n8n for non-device questions, with honest errors.
- [ ] Settings save, test connection, start capture service.

## What "pass" looks like

Every cloud feature either succeeds with a real 2xx body or shows the real HTTP error.
No screen may display a fake success, a fake "connected" state, or a graph node that
does not correspond to local data.
