# AppDeploy backend integration

The current deployed Astra backend already exposes `GET /api/agent/status` and `POST /api/agent/plan`, and the Android app calls the latter.

Before using this Android build with real personal data, add server-side device authentication and rate limiting to `/api/agent/plan`. The initial endpoint currently accepts screenshots without a per-device pairing token. Do not expose a public production endpoint to arbitrary callers. Keep `GEMINI_API_KEY` in AppDeploy Secrets only.

Recommended next backend milestone:
- Create a one-time pairing code in the dashboard.
- Exchange it for a revocable device token.
- Require the token on Android planning requests.
- Add per-device rate limits and request size limits.
- Add a visible connected-device indicator and a session revoke button.
