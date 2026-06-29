# Hermes Android Bridge — 프로젝트 개요

> **목표:** 안드로이드 폰에서 실행되는 브릿지 앱이 Termux(PRoot Ubuntu)의 Nick(Hermes Agent)과 통신하여, 
> 사용자가 Apple Vision Pro에서 Nick을 통해 스마트� 상태를 조회하고 제어할 수 있게 한다.

---

## �️ 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Apple Vision Pro                              │
│                    (Safari → Discord → Nick)                         │
└─────────────────�───────────────────────────────────────────────────┘
                  │ Discord DM / Web Chat
                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│              Termux (Android) → PRoot Ubuntu                         │
│                                                                      │
│  Nick (Hermes Agent)                                                │
│    │                                                                 │
│    ├── WebSocket Client (bridges to phone)                          │
│    ├── /data/dashboard/ → JSON state files                          │
│    ├── Android Bridge Plugin (receives commands from agent)         │
│    └── Cron jobs (trading, regime check)                            │
│                                                                      │
│         ▲ WebSocket (ws:// or wss://)                               │
│         │   protocol: JSON-RPC 2.0                                  │
│         │                                                           │
├─────────┼───────────────────────────────────────────────────────────┤
│         │  Android Host OS                                          │
│         ▼                                                           │
│  Hermes Bridge App (Kotlin)                                         │
│    ├── WebSocket Client → connects to Nick's WS server              │
│    ├── NotificationListenerService → captures all notifications     │
│    ├── AccessibilityService → screen interaction, UI automation     │
│    ├── BatteryManager → battery level, charging state               │
│    ├── SensorManager → accelerometer, gyroscope (optional)          │
│    ├── Camera (Camera2 API) → photo capture on demand               │
│    ├── LocationManager → GPS coordinates                            │
│    ├── MediaPlayer / TTS → sound output                             │
│    └── SystemInfo → storage, network, uptime                        │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 📡 통신 프로�콜: JSON-RPC 2.0 over WebSocket

### 연결 �름

1. **앱 시작** → `ws://<NICK_IP>:<PORT>/bridge`로 WebSocket 연결
2. **�드�이크** → �이 `register` 메시지 전송 (device ID, capabilities 포함)
3. **인증** → Nick가 pairing code �증
4. **�비 �료** → 양방향 명령/이벤트 교환 시작

### 메시지 형식

**Agent → Phone (request):**
```json
{
  "jsonrpc": "2.0",
  "id": 42,
  "method": "notification.list",
  "params": { "limit": 10, "since": 1719000000 }
}
```

**Phone → Agent (response):**
```json
{
  "jsonrpc": "2.0",
  "id": 42,
  "result": {
    "notifications": [
      { "package": "com.discord", "title": "Nick", "text": "Buy signal: BTC", "postedAt": 1719000001 }
    ]
  }
}
```

**Phone → Agent (event, no id):**
```json
{
  "jsonrpc": "2.0",
  "method": "notification.posted",
  "params": {
    "package": "com.discord",
    "title": "Home",
    "text": "Front door opened",
    "postedAt": 1719000050
  }
}
```

---

## 📋 명령어 스펙 (초기 �리즈 — Phase 1)

### �림 (Notifications)

| Method | Params | Description |
|--------|--------|-------------|
| `notification.list` | `limit`, `since`, `package?` | 최근 알림 목록 반환 |
| `notification.subscribe` | `packages[]` | 특정 앱 알림 실시간 구독 |
| `notification.clear` | `key?` | �림 �제 (특정/전체) |

### 디바이스 상태 (Device Status)

| Method | Params | Description |
|--------|--------|-------------|
| `device.battery` | — | 배터리 %, 충전 상태, 온도 |
| `device.storage` | — | 내장/외부 저장소 용량 |
| `device.network` | — | WiFi IP, 연결 상태, 신호 강도 |
| `device.system` | — | OS 버전, 모델명, 업타임, 메모리 |

### 화면 제어 (Screen Interaction)

| Method | Params | Description |
|--------|--------|-------------|
| `screen.screenshot` | `quality?` | 스크린� 캡처 → base64 반환 |
| `screen.read` | — | AccessibilityNode 트리 반환 |
| `screen.tap` | `x`, `y` | 좌� 탭 |
| `screen.swipe` | `x1`, `y1`, `x2`, `y2`, `duration` | 스와이프 제스처 |
| `screen.type` | `text` | 현재 포커스된 필드에 텍스트 입력 |

### � 제어 (App Control)

| Method | Params | Description |
|--------|--------|-------------|
| `app.list` | `system?` | 설치된 앱 목록 |
| `app.launch` | `package` | � 실행 |
| `app.current` | — | 현재 포그라운드 앱 |
| `app.close` | `package` | � 강제 종료 |

### 미디어 / 센서 (Media & Sensors)

| Method | Params | Description |
|--------|--------|-------------|
| `media.volume` | `level?` | 볼� 조회/설정 (0-100) |
| `media.tts` | `text` | TTS로 음성 출력 |
| `camera.snap` | `facing` | 전면/후면 카메라 �영 |
| `sensor.location` | — | GPS �표 반환 |
| `sensor.compass` | — | 방위각 반환 |

---

## � 프로젝트 구조

```
hermes-android-bridge/
├── AGENTS.md                    ← 이 파일
├── README.md
├── app/                         ← Android � (Kotlin)
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/nick/hermesbridge/
│       │   ├── HermesApp.kt              ← � 진입점
│       │   ├── ws/
│       │   │   ├── WebSocketManager.kt   ← WS 연결/재연결/�
│       │   │   ├── JsonRpcHandler.kt     ← 요청/응답/이벤트 직렬화
│       │   │   └── AuthManager.kt        ← pairing code 관리
│       │   ├── service/
│       │   │   ├── NotificationService.kt    ← NotificationListenerService
│       │   │   ├── AccessibilityBridge.kt    ← AccessibilityService
│       │   │   └── StatusService.kt          ← 배터리/네트워크/센서 폴링
│       │   ├── handler/
│       │   │   ├── NotificationHandler.kt    ← notification.* 명령 처리
│       │   │   ├── DeviceHandler.kt          ← device.* 명령 처리
│       │   │   ├── ScreenHandler.kt          ← screen.* 명령 처리
│       │   │   ├── AppHandler.kt             ← app.* 명령 처리
│       │   │   └── MediaHandler.kt           ← media.*, camera.*, sensor.*
│       │   ├── model/
│       │   │   ├── Models.kt                 ← 모든 data class 정의
│       │   │   └── Capabilities.kt           ← 디바이스 기능 레지스트리
│       │   └── ui/
│       │       ├── MainActivity.kt           ← 설정 화면
│       │       ├── ConnectionStatusView.kt   ← 연결 상태 �시
│       │       └── SettingsFragment.kt       ← 서버 주소, 포트 설정
│       └── res/
│           ├── layout/
│           ├── values/
│           └── xml/
│               └── accessibility_config.xml
│               └── notification_config.xml
├── server/                      ← WebSocket �레이 서버 (Kotlin/Native or Python)
│   └── ws_server.py             ← Nick가 실행하는 Python WS 서버
├── docs/
│   ├── PROTOCOL.md              ← JSON-RPC 2.0 프로토콜 상세 명세
│   ├── SETUP.md                 ← 설치/설정 가이드
│   └── PHASES.md                ← 개발 단계별 로드�
└── scripts/
    ├── build_apk.sh             ← APK 빌드 스크립트
    └── test_connection.py       ← 연결 테스트 스크립트
</longcat_think>
