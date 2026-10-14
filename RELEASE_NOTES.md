# JARVIS 1.3.2 — AI error fix

Installs over earlier versions (same application ID and signing key; data is kept).

* **Gemini model fallback** – if Google retires or renames the configured model (HTTP 404), JARVIS now tries current
  model aliases automatically instead of failing.
* **Error details** – "AI server error" now shows the HTTP code and the server's reason, so the cause can be seen.
