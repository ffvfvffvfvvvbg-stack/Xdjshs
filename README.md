# Astra Live Agent — Android companion

This is the Android source project for the Astra Live Agent companion. It is designed to pair with the existing AppDeploy backend at:

`https://astra-live-agent-ymuzhh.v2.appdeploy.ai`

## Included

- Kotlin + Jetpack Compose Android application.
- Android AccessibilityService for visible taps, swipes, text entry, Back, and launching installed apps.
- MediaProjection foreground service for user-approved screen capture.
- Gemini planning requests sent to the AppDeploy `/api/agent/plan` endpoint.
- Speech-to-text through Android's installed speech-recognition provider.
- GitHub Actions workflow to build a debug APK.
- No Firebase dependency and no Gemini API key embedded in the APK.

## Important build and platform notes

This archive is a source project, not a prebuilt APK. The GitHub Actions workflow installs Gradle 8.9 and builds `:app:assembleDebug` on an Ubuntu runner with JDK 17 and Android SDK 35. A build can only be called verified after the workflow has actually completed successfully in your repository; no one can honestly guarantee a zero-error build before running it in your GitHub environment.

Android requires the user to enable the Accessibility service in Settings and approve MediaProjection screen sharing for each session. Android may stop screen capture when the app is backgrounded or the OS revokes the projection. The app does not bypass Android security prompts.

## Build on GitHub using your phone

1. Create a new repository named `Astra-Live-Agent-Android`.
2. Upload the contents of this ZIP to the repository root (not the ZIP itself).
3. Open the **Actions** tab and enable GitHub Actions if prompted.
4. Run **Build Astra Android APK** or push to `main`.
5. Download the `astra-debug-apk` artifact from the successful workflow run.

The workflow uses Gradle 8.9 directly through `gradle/actions/setup-gradle`, so the repository does not need a checked-in Gradle wrapper JAR.

## Run

1. Install the debug APK.
2. Open Astra and enable **Astra Live Agent** under Android Accessibility settings.
3. Enter a task and the AppDeploy backend URL.
4. Tap **Start live task** and approve Android's screen-sharing prompt.
5. Keep the session visible while the assistant works. Tap **Stop session** to end it.

## Privacy and sensitive actions

Screen images are sent to the configured AppDeploy backend and then to Gemini for planning. Do not run the assistant while a banking PIN, OTP, password, card details, private messages, or other highly sensitive information is visible. The app intentionally pauses when the planner returns `ask_user` or `requiresConfirmation`; it does not automatically submit payment forms or enter authentication secrets. Review consequential actions yourself in the target app.

This first Android companion uses one screenshot per planning step. It is not yet a low-latency, continuous audio-native Gemini Live session. Speech input uses Android's speech recognizer, and the task loop is screen-aware. Live two-way audio streaming, device pairing codes, encrypted device-to-dashboard WebSocket streaming, and release signing are future integration milestones.

## AppDeploy backend source

The `appdeploy/backend/agent.ts` file is the planner source used by this Android client. The deployed AppDeploy app already has an agent endpoint, but the revised confirmation wording in this ZIP must be merged into the existing AppDeploy backend and redeployed before relying on the exact-action approval flow. Do not replace the whole dashboard project with this single backend file.

The currently deployed planner endpoint does not yet provide device pairing or per-device authentication. Add those protections before exposing the endpoint to public or untrusted callers.

## Backend contract

The companion expects the existing AppDeploy endpoint:

`POST /api/agent/plan`

JSON body:
- `instruction`: natural-language task
- `screenshot`: `{ "mimeType": "image/jpeg", "data": "<base64>" }`

Expected response:
- `plan`: object with `action`, `explanation`, and optional action fields (`x`, `y`, `endX`, `endY`, `text`, `app`, `question`, `requiresConfirmation`).

Coordinates are normalized from 0 to 1000. The AppDeploy Gemini key must remain stored as a backend secret and must never be copied into this Android project.

## Known limitations to test on the target phone

- Some apps block accessibility interaction or screen capture.
- Text insertion requires a focused editable field.
- App launch by label uses the first installed app whose label matches the requested text; package names are more precise.
- Banking apps may block screenshots or accessibility access by design.
- The companion currently pauses at AI-requested confirmation and requires a user tap to continue. Always verify the actual destination and amount yourself.
# Xdjshs
