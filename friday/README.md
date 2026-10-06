# FRIDAY V1.0 — Android 개인 AI 음성 비서

한국어로 말하면 → AI가 판단하고 → 실제 Android 기능을 실행하고 → **영어 여성 음성**으로 답하며 → 화면에는 **한국어 자막**을 보여주는 음성 비서입니다.

```
Wake Word ─▶ STT(ko-KR) ─▶ Intent/Context ─▶ AI ─▶ Command/Tool ─▶ Result ─▶ AI 응답 ─▶ English TTS + 한국어 자막
```

> 이 폴더(`friday/`)는 저장소 루트에 있던 기존 JARVIS 프로젝트와 **완전히 별개**의 새 Gradle 프로젝트입니다. JARVIS 코드는 건드리지 않았습니다.

## 상태 구분

| 구분 | 의미 |
|---|---|
| **BUILD VERIFIED** | lint · 컴파일 · APK 생성(debug/release, 서명 확인) 통과 |
| **AUTOMATED TEST VERIFIED** | 단위·Robolectric 통합·Compose 스모크 테스트 통과 (가짜 STT/AI/TTS 사용, 실제 API 없음) |
| **DEVICE QA REQUIRED** | 실제 스마트폰에서만 확인 가능 — 아래 체크리스트 참고. 확인 전에는 "작동한다"고 주장하지 않습니다. |

## 기능

* **HUD**: AMOLED 블랙 + 보라/마젤란/블루 에너지, 원형 AI Core(IDLE · LISTENING · THINKING · SPEAKING · EXECUTING · OFFLINE · ERROR 별 애니메이션), 마이크 레벨/TTS 진폭에 반응하는 파형, 유리 패널, 짧은 부팅 시퀀스(≈2초).
* **음성**: 한국어 STT(Android SpeechRecognizer, 교체 가능한 `SpeechRecognizerEngine`), 영어 TTS(`TTSProvider`: OpenAI 호환 · ElevenLabs 호환 · Android TTS 폴백), TTS 중 새 명령/STOP 시 즉시 중단.
* **AI**: `AIProvider` 인터페이스 — OpenAI 호환(OpenAI/Groq/OpenRouter/LM Studio …), Google Gemini, Ollama. 응답은 `{"speech","subtitle","action"}` JSON이며 파싱 실패 시 폴백.
* **Wake word "FRIDAY"**: `WakeWordEngine` 추상화 + V1 구현(`SpeechWakeWordEngine`). 전용 저전력 엔진(Porcupine, openWakeWord …)은 같은 인터페이스로 교체합니다.
* **Background Assistant**: 마이크 Foreground Service + 지속 알림 + STOP 버튼. 설정에서 언제든 끌 수 있음.
* **명령(허용 목록만 실행, 임의 코드/셸 없음)**: 시간·날짜·배터리, 앱 실행, URL, 웹 검색/웹 답변, YouTube 열기/검색, 알람(AlarmClock), 손전등, 볼륨(↑↓/지정), 미디어(재생/일시정지/다음/이전), 날씨(현재/오늘/내일, Open-Meteo), 설정 열기, 전화/문자 요청.
* **확인 단계**: 전화·문자는 항상 "…할까요?"로 먼저 물어보고 사용자가 "응/네"라고 해야 진행. 문자는 **작성만** 하고 전송 버튼은 사용자가 누릅니다.
* **오프라인**: 시간/배터리/손전등/볼륨/앱 실행 등은 AI 없이 로컬에서 처리(`LocalIntentParser`).
* **대화 메모리**(Room): 최근 대화만 AI에 전달(턴 수·글자 수 제한 + 이전 대화 한 줄 요약), 설정에서 끄기, 대화 화면에서 전체 삭제.
* **보안**: API 키는 Android Keystore(AES-256-GCM)로 암호화 저장, 소스/로그에 키 없음, 음성은 파일로 저장하지 않음(클라우드 TTS도 PCM 스트리밍 재생).
* **Diagnostics**: 마이크·STT·Wake Word·AI API·TTS·Foreground Service·알림·날씨·웹 검색·Command Router·DB·네트워크를 각각 PASS / FAIL / NOT CONFIGURED로 점검.

## 프로젝트 구조

```
friday/
├─ app/src/main/java/com/friday/assistant/
│  ├─ core/        FridayController(파이프라인), CoreState, Errors
│  ├─ ai/          AIProvider, OpenAICompatible/Gemini/Ollama, AiReplyParser, PromptBuilder
│  ├─ command/     CommandType(허용 목록), CommandRouter, AndroidExecutors, LocalIntentParser, KoreanTimeParser, AppResolver …
│  ├─ stt/ wake/ tts/   음성 입출력 + 교체 가능한 엔진
│  ├─ weather/ search/  Open-Meteo, DuckDuckGo/Wikipedia
│  ├─ data/ settings/ security/   Room, 설정, Keystore
│  ├─ service/     FridayService (Foreground)
│  ├─ diag/        DiagnosticsRunner
│  └─ ui/          Compose HUD, 화면, ViewModel (MVVM)
├─ app/src/test/   단위 · Robolectric 통합 · Compose 스모크 테스트
├─ keystore/       사이드로드용 서명 키 (스토어 배포용 아님)
└─ ../.github/workflows/friday-android.yml   CI
```

## 빌드

요구: JDK 17+, Android SDK(platform 35, build-tools 35). `local.properties`에 `sdk.dir=...` 지정.

```bash
cd friday
./gradlew lint
./gradlew test
./gradlew assembleDebug assembleRelease
```

APK 위치:

* `friday/app/build/outputs/apk/debug/app-debug.apk`
* `friday/app/build/outputs/apk/release/app-release.apk` (R8 축소, 약 2.5 MB)

CI(GitHub Actions)는 push마다 lint → test → debug/release APK 빌드 후 APK를 artifact로 올립니다.

## 설치

1. 폰: 설정 → 보안 → "알 수 없는 앱 설치" 허용(사용하는 브라우저/파일앱에 대해).
2. APK를 폰으로 옮기거나 CI artifact `friday-apk`를 폰에서 내려받아 열기. 또는 USB 디버깅 후 `adb install -r app-release.apk`.
3. debug/release APK는 같은 서명키를 쓰므로 서로 덮어 업데이트됩니다.

## 권한

| 권한 | 용도 | 필수 |
|---|---|---|
| RECORD_AUDIO | 음성 인식, Wake Word | 필수 |
| POST_NOTIFICATIONS (Android 13+) | Background Assistant 알림 | Background 사용 시 |
| FOREGROUND_SERVICE(_MICROPHONE) | 백그라운드 마이크 서비스 | Background 사용 시 |
| ACCESS_COARSE_LOCATION | "현재 위치 날씨" (마지막 위치만 사용) | 선택 |
| READ_CONTACTS | 연락처로 전화/문자 | 선택 |
| CALL_PHONE | 확인 후 바로 전화(없으면 다이얼러만 열림) | 선택 |
| com.android.alarm.permission.SET_ALARM | 알람 앱 연동 | 자동 |

## AI Provider 설정

설정 → **AI Core**에서 Provider(OpenAI 호환 / Gemini / Ollama), Model, Endpoint, API Key를 입력합니다. 키는 저장 즉시 암호화되며 화면에는 다시 표시되지 않습니다. 기본값은 Gemini(`gemini-2.5-flash`)이지만 **키는 포함되어 있지 않으며** 사용자가 직접 넣어야 합니다. 키가 없으면 로컬 명령(시간/배터리 등)만 동작하고 FRIDAY가 설정이 필요하다고 안내합니다.

* OpenAI 호환: Endpoint `https://api.openai.com/v1`, Groq `https://api.groq.com/openai/v1` 등.
* Ollama: PC에서 `OLLAMA_HOST=0.0.0.0 ollama serve` 후 Endpoint를 `http://<PC IP>:11434`로. (LAN용으로 cleartext HTTP를 허용합니다.)

## TTS 설정

설정 → **Voice (TTS)**.

* **Android TTS**(기본/폴백, 오프라인): *LIST ENGLISH VOICES*로 설치된 영어 음성을 보고 가장 마음에 드는 여성 음성을 직접 고르세요. Android는 음성의 성별을 알려주지 않아 자동 선택은 이름 힌트 기반의 추정입니다. Pitch를 약간 올려(기본 1.08) 더 가볍게 들리게 합니다.
* **OpenAI 호환 TTS**: Endpoint, Model(`gpt-4o-mini-tts`), Voice(`nova`, `shimmer` …), TTS API Key. PCM 24 kHz 스트리밍.
* **ElevenLabs 호환**: Endpoint `https://api.elevenlabs.io/v1`, Voice ID, API Key.
* 클라우드 TTS가 실패하면 자동으로 Android TTS로 폴백합니다. 특정 배우/성우의 목소리를 복제하지 않습니다.

## Background Assistant 설정

설정 → *Background Assistant* 켜기 → 마이크/알림 권한 허용 → 상단 알림 "FRIDAY — Listening for wake word"(STOP 버튼 포함)이 보이면 동작 중입니다. "FRIDAY"라고 부르면 "Yes?" 후 듣기 시작하고, "FRIDAY 지금 몇 시야"처럼 한 번에 말해도 됩니다.

* 배터리 최적화에서 FRIDAY를 "제한 없음"으로 두면 오래 유지됩니다(제조사별 상이).
* 앱을 강제 종료하면 서비스도 종료되며 자동으로 되살아나지 않습니다(의도된 정책 준수). 앱을 다시 열면 설정에 따라 재시작됩니다.

## 실기기 QA 체크리스트 (DEVICE QA REQUIRED)

- [ ] 마이크 권한 → HUD 마이크 버튼 → 한국어 인식, 부분 결과 표시
- [ ] "FRIDAY" 호출 → "Yes?" + 자막 "네, 말씀하세요." → 명령 인식 (조용한/시끄러운 환경, 화면 꺼짐)
- [ ] Background Assistant: 알림 표시, STOP 동작, 다른 앱 사용 중 호출, 화면 잠금, 배터리 소모(1시간 idle)
- [ ] 영어 TTS 음질/성별, 설정에서 음성 선택, 클라우드 TTS 키 입력 후 품질, 말하는 중 STOP/새 명령으로 즉시 중단
- [ ] 한국어 자막이 음성과 동시에 나타나고 종료 시 사라지는지
- [ ] 날씨(위치 권한 허용/거부 → 기본 도시), 웹 검색 요약
- [ ] 알람: "내일 오전 7시에 알람 맞춰줘" → 시계 앱에 7:00 알람 생성
- [ ] 손전등, 볼륨, 미디어(YouTube Music/Spotify 재생 중 일시정지·다음 곡), 앱 실행(카카오톡 등)
- [ ] 전화/문자: 확인 질문 → "응" → 다이얼러/문자 앱, "아니" → 취소
- [ ] 알림 권한 거부, 위치 권한 거부, 인터넷 끔(OFFLINE HUD, 로컬 명령 동작)
- [ ] Diagnostics 12개 항목 전부 실행
- [ ] 제조사 절전(OEM battery optimization)에서의 서비스 유지

## Known Limitations

* **Wake Word는 V1 구현입니다**: Android SpeechRecognizer를 반복 실행하는 방식이라 전용 키워드 엔진보다 배터리를 더 쓰고, 인식기 재시작 사이(수백 ms) 및 오프라인 상태에서 놓칠 수 있습니다. 구조는 `WakeWordEngine`으로 분리되어 있어 저전력 엔진으로 교체 가능합니다. 일부 기기에서는 인식기 시작 시 효과음이 날 수 있습니다.
* **한국어 STT 품질/오프라인**은 기기의 음성 인식 서비스(Google 등)에 달려 있습니다.
* **백그라운드에서 앱/액티비티 실행 금지(Android 10+)**: 화면에 FRIDAY가 없을 때 음성으로 "유튜브 열어줘"를 하면 Android 정책상 직접 열 수 없어 **탭하면 열리는 알림**을 띄웁니다(우회하지 않음). 정보 응답·볼륨·손전등·미디어 키는 백그라운드에서도 동작합니다.
* **알람**: AlarmClock API는 "다음에 오는 해당 시각"만 지원합니다. "내일 7시"가 다음 7시와 같으면 바로 생성하고, 아니면(예: 새벽 5시에 "내일 7시") 시계 앱을 띄워 사용자가 확인하게 합니다.
* **미디어 제어**는 미디어 키 이벤트를 보내는 방식이라 재생 앱이 반응해야 합니다. 반응이 없으면(재생 상태 변화 없음) 성공이라 말하지 않고 그대로 알려줍니다.
* **문자는 작성만** 하고 사용자가 전송을 누릅니다(`SEND_SMS` 미사용). 전화는 `CALL_PHONE` 허용 시 확인 후 바로 발신, 아니면 다이얼러.
* 웹 검색은 DuckDuckGo HTML(비공식, 변경될 수 있음) → 한국어 Wikipedia 순으로 시도하고, 실패하면 브라우저 검색으로 폴백합니다.
* 부팅 후 자동 시작 없음(Android 14+는 부팅 시 마이크 서비스 시작을 허용하지 않음).
* 서명 키(`keystore/friday.keystore`)는 사이드로드용으로 저장소에 포함되어 있습니다. Play 스토어 배포 전에 교체하세요.

## Troubleshooting

| 증상 | 확인 |
|---|---|
| "AI가 설정되지 않았습니다" | 설정 → AI Core에서 Provider/Model/API Key 입력 → Diagnostics → AI API Test |
| 말을 해도 인식 안 됨 | 마이크 권한, Diagnostics → Microphone/STT Test, 기기의 Google 음성 인식 서비스 업데이트·한국어 언어팩 |
| Wake Word 무반응 | Background Assistant 알림 표시 여부, 배터리 최적화 제외, Diagnostics → Wake Word Test(서비스는 먼저 끄기) |
| 영어 음성이 남성/어색함 | 설정에서 영어 음성 목록 중 선택, 또는 클라우드 TTS 사용 |
| 목소리가 안 나옴 | 미디어 볼륨, Diagnostics → TTS Test, 기기 TTS 엔진 설치 |
| 앱 실행이 안 되고 알림만 뜸 | 백그라운드 실행 제한 — 알림을 탭하거나 화면을 FRIDAY로 두고 명령 |
| 연락처를 못 찾음 | 연락처 권한 허용, 저장된 이름으로 말하기 |
| 빌드 중 Maven 429 | 저장소가 일시적으로 요청을 제한 — 잠시 후 재시도 |
