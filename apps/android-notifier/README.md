# Bonjee Notifier Android App

Android app that uses `NotificationListenerService` to capture all notifications and forward them to n8n for processing. 

## Features
- Captures ALL notifications from your device
- Forwards to n8n webhook with app info, title, content, timestamp
- Simple UI to configure webhook URL and test connection
- Runs as a background service
- Persists configuration

## Setup

### Prerequisites
- Android Studio
- Android SDK 21+
- Your n8n webhook URL (e.g `https://your-n8n.onrender.com/webhook/notifications-ingest`)

### Build Instructions
1. Open this project in Android Studio
2. Open `app/src/main/res/values/strings.xml` and update `n8n_webhook_url` or configure via the app
3. Build APK: `Build > Generate Signed Bundle / APK > APK`
4. Install on your device

### Usage
1. Open the app
2. Enter your n8n webhook URL
3. Tap "Grant Notification Access" - this will open system settings
4. Enable "Bonjee Notifier" in Notification Access settings
5. Go back to the app and tap "Start Service"
6. Test by receiving any notification

## Notes
- You MUST grant Notification Access permission. Without this, the app cannot read notifications
- The app needs internet permission to POST to n8n
- On Android 13+, you may need POST_NOTIFICATIONS permission (for the foreground service notification)
- For "all notifications", no filtering is done in the app by default - filtering happens in n8n
- Keep the app running (it will show a persistent notification when active)

## Security
- Only send to HTTPS endpoints you control
- The webhook URL is stored in SharedPreferences (local to device)
- No data is stored permanently on the device, it's forwarded immediately
