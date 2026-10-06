# JARVIS 1.3 — Hologram workspace

Installs over 1.0–1.2.1 (same application ID and signing key; data is kept).

* **Hologram workspace** – 4 to 11 holographic windows (weather, news, calendar, tasks, notes, music, system, clock,
  search, chat, map…) boot one after another with a scan / loading effect, each loading its own data. Move them by
  touch or with your hand in the air, click, close, maximize.
* **Air gestures v2** – real hand tracking (MediaPipe landmarks) instead of motion detection: smooth cursor, pinch to
  click / drag, dwell click, palm swipes, pose holds. Falls back to the old motion detector if tracking is unavailable.
* **Talk to a window** – point at a window and say "이거 여기서 이렇게 해줘"; JARVIS receives what the cursor is on.
  Voice window control: open / close / arrange / maximize / refresh.
* **Wake word that ignores mentions** – three modes (call / twice / anywhere) with vocative and mention filters.
* **Screen off or locked** – standby keeps a partial wake lock, wakes the screen and shows JARVIS over the lock
  screen (private content hidden while locked). Cannot work while the phone is powered off.

Not verified on a real device: hand-tracking accuracy, parallax, screen-off wake, battery use.
