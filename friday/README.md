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

* **HUD**: AMOLED 블랙 + 보라/마젠타/블루 에너지, 원형 AI Core(IDLE · LISTENING · THINKING · SPEAKING · EXECUTING · OFFLINE · ERROR별 애니메이션) + 상태 링 · 오디오 링(마이크/TTS 진폭) · 컨텍스트 링(단기 문맥 보유량), 필요할 때만 뜨는 정보 카드(날씨·일정·미디어·알림·기기), 짧은 부팅 시퀀스, **Ambient Mode**(상시 표시 시계 + 번인 방지 미세 이동).
* **FRIDAY Voice Engine 2.0**: Tier A 클라우드(OpenAI 호환) → Tier B 클라우드(ElevenLabs 호환) → Tier C Android TTS 자동 폴백(키가 없는 티어는 건너뜀, 우선순위는 설정에서 변경). 문장 단위 `SpeechQueue`, **AudioFocus**(음악 낮춤/일시정지/끔), `SpeechInterruptController`(barge-in), `VoiceProfile`(독창적인 FRIDAY 음색 지시문, 경고 문장은 더 또렷하게), 한국어 자막 **문장별 동기화**(+표시 시간·크기·항상 표시 설정).
* **대화 흐름**: Wake Word → STT → 컨텍스트 → 로컬 의도/AI → 명령 → 결과 → 영어 TTS + 한국어 자막 → **Follow-up Mode**(웨이크워드 없이 이어서 말하기, 기본 8초). 말하는 도중 "FRIDAY"로 끊기(barge-in, Background Assistant), "그만"으로 중지. FRIDAY가 스스로 "Friday"라고 말할 때는 호출로 오인하지 않음.
* **ContextEngine**: 직전 날씨/결과 같은 작은 요약만 10분간(최대 4개) AI 프롬프트에 포함 → "그럼 우산 필요할까?" 이해. 일정 목록은 기기 안에서만 기억해 "첫 번째 일정 몇 시야?"를 AI 없이 답변.
* **Command System 2.0**: 카테고리(SYSTEM·APP·MEDIA·COMMUNICATION·NOTIFICATION·CALENDAR·WEATHER·WEB·DEVICE·UTILITY), 명령별 위험 등급·필요 권한·타임아웃, **ConfirmationManager**(LEVEL 2/3은 `ConfirmableExecutor`가 아니면 등록 자체가 거부되어 확인 전 실행이 구조적으로 불가능).
* **알림(NotificationListenerService)**: 기본 OFF, 사용자가 시스템 설정에서 직접 허용. 개수/목록/최신 읽기/앱별 읽기/열기/지우기(확인 필요). 메모리 버퍼만 사용(디스크 저장·로그·AI 전송 없음). 한글 본문은 영어 음성으로 읽지 않고 자막/카드에만 표시.
* **캘린더(Calendar Provider)**: 오늘/내일/다음 일정, 검색, 일정 추가(확인 후; WRITE_CALENDAR가 없으면 캘린더 앱의 "새 일정" 화면을 미리 채워 열기). 일정 내용은 AI로 보내지 않음.
* **미디어**: MediaSession(알림 접근 허용 시) 기반 재생/일시정지/다음/이전/정지/현재 곡 정보, 미허용 시 미디어 키 폴백(효과가 없으면 성공이라고 말하지 않음).
* **기기 상태**: 배터리·충전·네트워크(Wi-Fi/데이터)·블루투스(알 수 있을 때만)·볼륨/벨소리·저장 공간.
* **앱 제어**: 앱 열기, 앱 설정, 설치 여부 검색, 카메라·시계·캘린더·지도·브라우저, 시스템 설정 화면.
* **브리핑**: "오늘 브리핑"(시간·날씨·강수·일정·배터리·알림), "오늘 정리해줘"(남은 일정·내일 첫 일정·내일 날씨·배터리·알림). 사용할 수 없는 항목은 만들어내지 않고 생략.
* **Proactive 구조**: 낮은 배터리·다가오는 일정·날씨 경고(기능별 ON/OFF, 기본 OFF, 알림으로 전달). 배터리 부족 브로드캐스트 + 15분 타이머(Background Assistant가 켜져 있을 때만), 상시 폴링 없음.
* **Permission Center**: 마이크·알림·알림 접근·위치·캘린더(읽기/쓰기)·연락처·전화·오버레이·포그라운드 서비스 상태(GRANTED / DENIED / NOT REQUESTED / SETTINGS REQUIRED)와 이유, 시스템 설정 화면으로 바로 이동.
* **오프라인/비용 절감**: 시간·날짜·배터리·볼륨·손전등·앱·알람·음악·일정·알림·브리핑·기기 상태는 `LocalIntentParser`로 AI 없이 처리. AI 호출은 25초 타임아웃, 시간초과/5xx만 1회 재시도(키 오류·한도는 재시도 없음), 대화 문맥은 최근 8턴·2000자로 제한.
* **Diagnostics 2.0**: 21개 항목 + 최근 응답의 실측 지연(Wake / STT / AI / Command / TTS first-audio / Total, 측정하지 않은 값은 "—").

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


## V1.0 확장 패스: 설정 방법

### Notification Access (알림·미디어 정보)
설정 → Permission Center → *Notification Access* → **OPEN SETTINGS** → FRIDAY를 켭니다(Android의 "알림 접근" 화면). 켜기 전에는 알림 명령이 "권한이 필요합니다"라고 안내하고, FRIDAY는 알림을 전혀 읽지 않습니다. 끄면 즉시 중단됩니다. 제한된 설정(Restricted settings)이 걸린 사이드로드 앱은 *앱 정보 → ⋮ → 제한된 설정 허용*을 먼저 해야 할 수 있습니다.

### Calendar 권한
Permission Center의 *Calendar (read)* 를 허용하면 일정 조회가 됩니다. *Calendar (write)* 는 선택이며, 허용하지 않아도 "일정 추가"는 캘린더 앱의 새 일정 화면을 미리 채워 열어 줍니다(저장은 사용자가 누름). 추가는 항상 "…추가할까요?" 확인 뒤에 진행됩니다.

### FRIDAY Voice / TTS Provider
설정 → *FRIDAY Voice*: 티어 순서(▲▼)를 정하고 Tier A(OpenAI 호환: Endpoint/Model/Voice/Key), Tier B(ElevenLabs 호환: Voice ID/Key)를 입력합니다. 둘 다 비워 두면 Android TTS만 사용합니다. 음성이 말하는 동안 음악을 *낮춤 / 일시정지 / 그대로* 중에서 고릅니다. 자막: 표시 시간, 크기, 항상 표시.

### Follow-up Mode
기본 ON(8초). 답변 뒤에 마이크가 다시 열리고, 말하지 않으면 조용히 대기 상태로 돌아갑니다(오류 아님). 설정에서 끄거나 3~15초로 조절하세요.

### Barge-in (말 끊기)
Background Assistant가 켜져 있으면 FRIDAY가 말하는 중에도 "FRIDAY"를 들을 수 있어 바로 끊고 듣기 시작합니다. 스피커 소리가 마이크로 되돌아오는 환경에서는 오작동할 수 있으니 설정에서 끌 수 있습니다.

### Morning Brief / Evening Brief
"FRIDAY, 오늘 브리핑" · "오늘 정리해줘". 날씨(네트워크), 일정(캘린더 권한), 알림(알림 접근), 배터리 중 사용 가능한 것만 말합니다.

### Media Control
알림 접근을 허용하면 실제 MediaSession으로 제어하고 "지금 무슨 노래야?"에 곡/아티스트/앱을 알려 줍니다. 허용하지 않으면 미디어 키 이벤트로 제어하며 결과가 확인되지 않으면 그대로 알려 줍니다.

### Ambient Mode
HUD 오른쪽 위 달 아이콘. 검은 화면에 작은 Core와 시간만 표시하고 1분마다 위치·밝기를 조금씩 바꿉니다(화면은 켜진 상태 유지). 탭하면 나갑니다. 설정에서 "앱 시작 시 Ambient"도 가능합니다.

### Diagnostics
상태: PASS / FAIL / NOT CONFIGURED / **DEVICE TEST REQUIRED**(자동으로 판정할 수 없는 항목: Follow-up, 실제 마이크 barge-in, 곡 재생 중 MediaSession 등).

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
- [ ] Follow-up: "FRIDAY" → 질문 → 웨이크워드 없이 두 번째 질문, 8초 침묵 후 조용히 종료
- [ ] Barge-in: FRIDAY가 긴 답변을 말하는 중 "FRIDAY" → 즉시 멈추고 "Yes?"
- [ ] AudioFocus: 음악 재생 중 FRIDAY 응답 → 음악이 낮아졌다 복원됨(전화 수신 시 FRIDAY가 멈추는지)
- [ ] 자막이 문장 단위로 음성과 맞게 바뀌고 종료 후 사라지는지
- [ ] Notification Access 허용 → "새 알림 있어?", "카카오톡 알림 읽어줘", 끄면 안내 메시지
- [ ] 캘린더: 오늘/내일/다음 일정, "금요일 오후 4시에 코딩 일정 추가해줘" → 확인 → 실제로 캘린더에 생성
- [ ] MediaSession: 유튜브 뮤직/스포티파이 재생 중 "지금 무슨 노래야?", 일시정지/다음 곡
- [ ] 브리핑 두 가지, Proactive 알림 3종(켰을 때만), Ambient Mode(번인 방지 이동)
- [ ] Permission Center 각 항목 상태 표시 및 설정 화면 이동
- [ ] Diagnostics 21개 항목 전부 실행, 실측 지연 값 확인
- [ ] 제조사 절전(OEM battery optimization)에서의 서비스 유지

## Known Limitations

* **Barge-in/Follow-up은 SpeechRecognizer를 공유**합니다: FRIDAY가 말하는 동안 인식기가 스피커 소리를 들을 수 있어 일부 기기에서 오작동하거나 인식이 늦을 수 있습니다(실기기 검증 전).
* **알림 내용·캘린더 내용은 AI에 보내지 않기 때문에** "김 부장 메시지 요약해줘" 같은 AI 요약은 지원하지 않습니다. 한글 본문은 영어 음성이 아닌 자막으로만 보여 줍니다.
* **알림 지우기/열기**는 Android가 해당 알림에 허용할 때만 동작합니다(열기는 백그라운드에서 제한될 수 있음).
* **Proactive 알림은 알림(Notification)으로만 전달**되고 음성으로 먼저 말하지 않으며, Background Assistant가 켜져 있을 때만 평가됩니다. "중요 알림" 소스는 아직 구현되지 않았습니다(구조만 있음).
* **Overlay 권한은 V1에서 사용하지 않습니다**(상태만 표시). 백그라운드에서 앱을 직접 띄우는 대신 탭하면 열리는 알림을 사용합니다.
* 지연(latency) 값은 측정된 단계만 표시합니다. 탭/타이핑 입력에는 Wake·STT 값이, 로컬 명령에는 AI 값이 없습니다.
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
