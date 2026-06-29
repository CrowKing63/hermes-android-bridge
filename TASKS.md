# Hermes Android Bridge — 코드 작성 작업 지시서

> 이 문서는 OpenCode CLI(또는 �한 코딩 에이전트)가 코드를 생성할 때 따라야 할 작업 지시사항이다.
> AGENTS.md의 아키텍처와 프로�콜 명세를 �저 �은 후, 아래 작업을 순서대로 수행한다.

---

## � �심 제약사항

1. **언어:** Kotlin (Android), Python (서버 � �레이)
2. **minSdk:** 26 (Android 8.0), **targetSdk:** 34
3. **WebSocket 라이브러리:** OkHttp (이미 Android에서 � 동작)
4. **JSON:** kotlinx.serialization
5. **코루틴:** 모든 비동기 작업에 Kotlin Coroutines 사용
6. **패키지 이름:** `com.nick.hermesbridge`
7. **빌드 시스템:** Gradle Kotlin DSL
8. **실제 동작하는 코드:** 스�이나 TODO 없이 �파일 가능한 코드 작성

---

## 📝 Phase 1: 프로젝트 스�레톤 + WebSocket 연결

### Task 1.1: Gradle 프로젝트 설정

**파일:**
- `app/build.gradle.kts`
- `settings.gradle.kts` (최상위)
- `gradle.properties`
- `app/proguard-rules.pro`

**요구사항:**
- Kotlin 1.9.x
- AGP 8.x
- OkHttp 4.12.0
- kotlinx.serialization 1.6.x
- AndroidX Core, AppCompat, ConstraintLayout, Lifecycle
- Coroutines 1.7.x
- minSdk 26, targetSdk 34, compileSdk 34

### Task 1.2: AndroidManifest.xml

**파일:** `app/src/main/AndroidManifest.xml`

**요구사항 (권한):**
```xml
<!-- 네트워크 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />

<!-- 위치 -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- 카메라 -->
<uses-permission android:name="android.permission.CAMERA" />

<!-- �림 접근 -->
<!-- (설정에서 수동 활성화, 매니페스트에 service 선언 필요) -->

<!-- 접근성 -->
<!-- (설정에서 수동 활성화) -->

<!-- 배터리 -->
<!-- (권한 불필요) -->

<!-- 오디오 / TTS -->
<!-- (권한 불필요) -->

<!-- 부팅 �료 � 자동 시작 -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

**선언할 �포넌트:**
- MainActivity
- NotificationListenerService (service.NotificationService)
- AccessibilityService (service.AccessibilityBridge)
- BootReceiver

### Task 1.3: HermesApp.kt (Application 클래스)

**파일:** `app/src/main/java/com/nick/hermesbridge/HermesApp.kt`

**역할:**
- � 실행 시 WebSocketManager 초기화
- 싱글톤으로 디바이스 상태 관리
- CoroutineScope 제공

### Task 1.4: WebSocketManager.kt

**파일:** `app/src/main/java/com/nick/hermesbridge/ws/WebSocketManager.kt`

**요구사항:**
- OkHttp WebSocket으로 서버 연결
- 자동 재연결 (지수 백오프: 1s → 2s → 4s → ... → 30s 최대)
- 연결 상태 MutableStateFlow<ConnectionState>
- 송신: `send(message: String)` — 내부 큐에서 순차 처리
- 수신: 들어오는 메시지를 JsonRpcHandler로 디스패치
- 하트비트: 30초마다 ping 프레임 전송
- 연결 �김 감지 → 자동 재연결 시도
- 로그: android.util.Log로 "HermesBridge" 태그 사용

**상태 �신:**
```
Disconnected → Connecting → Authenticating → Connected → Disconnected
```

### Task 1.5: Model Classes

**파일:** `app/src/main/java/com/nick/hermesbridge/model/Models.kt`

**정의할 data class:**
```kotlin
// Connection
enum class ConnectionState { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, ERROR }

// JSON-RPC base
@Serializable
data class JsonRpcRequest(val jsonrpc: String = "2.0", val id: Int?, val method: String, val params: JsonObject? = null)

@Serializable
data class JsonRpcResponse(val jsonrpc: String = "2.0", val id: Int?, val result: JsonObject? = null, val error: JsonRpcError? = null)

@Serializable
data class JsonRpcError(val code: Int, val message: String, val data: JsonObject? = null)

@Serializable
data class JsonRpcEvent(val jsonrpc: String = "2.0", val method: String, val params: JsonObject? = null)

// Device Info
@Serializable
data class DeviceInfo(val deviceId: String, val model: String, val manufacturer: String, val androidVersion: String, val sdkVersion: Int, val capabilities: List<String>)

@Serializable
data class BatteryStatus(val level: Int, val isCharging: Boolean, val temperature: Float, val health: String)

@Serializable
data class NetworkStatus(val wifiConnected: Boolean, val ssid: String?, val ipAddress: String, val signalStrength: Int, val isAvailable: Boolean)

@Serializable
data class StorageStatus(val internalTotal: Long, val internalFree: Long, val externalTotal: Long?, val externalFree: Long?)

@Serializable
data class SystemStatus(val uptimeSeconds: Long, val availableMemory: Long, val totalMemory: Long, val cpuUsage: Float)

@Serializable
data class Notification(val key: String, val packageName: String, val appName: String, val title: String, val text: String, val postedAt: Long, val isClearable: Boolean)

@Serializable
data class AppInfo(val packageName: String, val appName: String, val isSystemApp: Boolean, val versionName: String)

@Serializable
data class LocationData(val latitude: Double, val longitude: Double, val accuracy: Float, val timestamp: Long)
```

### Task 1.6: JsonRpcHandler.kt

**파일:** `app/src/main/java/com/nick/hermesbridge/ws/JsonRpcHandler.kt`

**요구사항:**
- 들어오는 메시지 파싱 → id 있으면 request, 없으면 event 처리
- method 이름을 `.`으로 split → namespace.handler 방식
- `notification.*` → NotificationHandler로 디스패치
- `device.*` → DeviceHandler로 디스패치
- `screen.*` → ScreenHandler로 디스패치
- `app.*` → AppHandler로 디스패치
- `media.*`, `camera.*`, `sensor.*` → MediaHandler로 디스패치
- � 수 없는 method → error response (-32601: Method not found)
- 처리 결과를 JSON-RPC response로 serialize → WebSocketManager로 반환

### Task 1.7: MainActivity.kt (설정 UI)

**파일:** `app/src/main/java/com/nick/hermesbridge/ui/MainActivity.kt`

**UI 요소:**
- 서버 IP 입력 필드 (pref: `ws://192.168.x.x:8765/bridge`)
- 연결 상태 표시 (색상 원: �강/노랑/초록)
- 연결/해제 버튼
- 로그 �스트 영역 (최근 50�)
- "알림 접근 권한" 버튼 → 설정 화면으로 이동
- "접근성 서비스" 버튼 → 설정 화면으로 이동
- "배터리 최적화 해제" 버튼 → 설정으로 이동

**SharedPreferences 키:**
- `server_url` — WebSocket 서버 주소
- `device_id` — 고유 디바이스 ID (UUID, 최초 1회 생성)

### Task 1.8: 레이아웃 XML

**파일:**
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/themes.xml`

---

## 📝 Phase 2: �림 서비스 + 디바이스 상태

### Task 2.1: NotificationService.kt

**파일:** `app/src/main/java/com/nick/hermesbridge/service/NotificationService.kt`

**요구사항:**
- `NotificationListenerService` 상속
- `onNotificationPosted()` — � 알림 발생 시 WebSocket으로 이벤트 전송
- `onNotificationRemoved()` — 알림 �제 시 이벤트 전송
- 활성 �림 목록 캐싱 (최근 100개)
- `getActiveNotifications()` — WebSocket에서 호출될 때 현재 알림 목록 반환
- 패키지 필터링 지원 (config에서 허용 목록 설정 가능)
- Rate limiting: 초당 최대 5개 이벤트 초과 시 버퍼링

### Task 2.2: DeviceHandler.kt

**파일:** `app/src/main/java/com/nick/hermesbridge/handler/DeviceHandler.kt`

**요구사항:**
- `device.battery` → BatteryManager에서 레�/충전상태/온도 조회
- `device.storage` → StatFs로 용량 조회
- `device.network` → ConnectivityManager + WifiManager로 상태 조회
- `device.system` → Debug.MemoryInfo + Runtime으로 메모리 조회
- 각 메서드는 suspend function으로 구현

### Task 2.3: AccessibilityBridge.kt

**파일:** `app/src/main/java/com/nick/hermesbridge/service/AccessibilityBridge.kt`

**요구사항:**
- `AccessibilityService` 상속
- `performTap(x, y)` — GestureDescription으로 � 수행
- `performSwipe(x1, y1, x2, y2, duration)` — 스와이프 수행
- `getTextContent()` — 현재 화면의 AccessibilityNode �스트 트리 반환
- `findNodeByText(text)` — �스트로 노드 �색
- `typeText(text)` — ACTION_SET_TEXT로 입력
- `performGlobalAction(action)` — BACK, HOME, RECENTS 등
- 싱글톤 패턴으로 인스턴스 접근 가능

---

## 📝 Phase 3: App 제어 + 미디어/센서

### Task 3.1: AppHandler.kt

**파일:** `app/src/main/java/com/nick/hermesbridge/handler/AppHandler.kt`

**요구사항:**
- `app.list` — PackageManager로 설치 앱 목록 반환
- `app.launch` — launchIntent로 � 실행
- `app.current` — UsageStatsManager 또는 AccessibilityEvent에서 현재 앱 감지
- `app.close` — AccessibilityService의 ACTION_BACK 또는 kill 불가(일반 � 강제 종료 불가) → 에러 반환

### Task 3.2: MediaHandler.kt + ScreenHandler.kt

**파일:**
- `app/src/main/java/com/nick/hermesbridge/handler/MediaHandler.kt`
- `app/src/main/java/com/nick/hermesbridge/handler/ScreenHandler.kt`

**MediaHandler 요구사항:**
- `media.volume` — AudioManager로 볼� 조회/설정
- `media.tts` — TextToSpeech로 음성 출력
- `camera.snap` — Camera2 API로 사진 �영 → base64 반환
- `sensor.location` — FusedLocationProvider로 GPS 수신
- `sensor.compass` — SensorManager 방향 센서

**ScreenHandler 요구사항:**
- `screen.screenshot` — MediaProjection API (별도 권한 필요, 별도 Activity에서 intent �득)
- `screen.read` — AccessibilityBridge.getTextContent()
- `screen.tap` — AccessibilityBridge.performTap()
- `screen.swipe` — AccessibilityBridge.performSwipe()
- `screen.type` — AccessibilityBridge.typeText()

---

## 📝 Phase 4: Python WebSocket 서버 (Nick �)

### Task 4.1: ws_server.py

**파일:** `server/ws_server.py`

**요구사항:**
- Python 3.10+, asyncio 기반
- websockets 라이브러리 사용
- JSON-RPC 2.0 프로토콜 처리
- 여러 클라이언트(디바이스) 동시 연결 지원
- 기능:
  - 클라이언트 연결 관리 (device_id 기반)
  - 명령 전송 (agent → phone): await server.send_command(device_id, method, params) → Future 반환
  - 이벤트 수신 (phone → agent): 콜백 등록 방식
  - 하트비트 체크 (60초 무응답 시 연결 해제)
  - 재연결 감지 � 상태 관리
- 로그: Python logging 모듈 사용
- 설정: 환경변수 `HERMES_BRIDGE_HOST`, `HERMES_BRIDGE_PORT`

### Task 4.2: test_connection.py

**파일:** `scripts/test_connection.py`

**요구사항:**
- ws_server.py에 연결 테스트
- `device.battery` 호출 테스트
- 기본 스모크 테스트 스크립트

---

## 📝 Phase 5: 문서 + 빌드

### Task 5.1: 프로토� 문서

**파일:** `docs/PROTOCOL.md`

**내용:**
- JSON-RPC 2.0 메시지 형식
- 모든 method/params/result 상세 명세
- 에러 코드 테이블
- 이벤트 목록
- 연결 라이프사이클

### Task 5.2: 설치 가이드

**파일:** `docs/SETUP.md`

**내용:**
1. Nick � ws_server.py 실행 방법
2. APK �드 방법
3. Android 권한 설정 단계별 가이드
4. 연결 테스트 방법
5. 트러�팅

### Task 5.3: README.md

**프로젝트 소개, 구조, �드, 사용법 요약**

### Task 5.4: build_apk.sh

**파일:** `scripts/build_apk.sh`

**요구사항:**
- `./gradlew assembleDebug` 실행
- APK 출력 경로 출력
- 서명 없이 디버그 APK 생성 가능

---

## ✅ �질 게이트 (모든 Phase 통과 전 확인)

- [ ] Kotlin 코드: 컴파일 에러 없음
- [ ] Python 코드: `python -m py_compile` 통과
- [ ] 하드코딩된 IP/포트 없음 (모두 설정)

에서 주소 사용)
- [ ] 민감정보(API key 등) 하드코딩 없음
- [ ] 모든 파일에 적절한 주석 포함
- [ ] TODO/FIXME/stub 없음

---

## � 우선순위 요약

| Order | Task | 난이도 | 의존성 |
|-------|------|--------|--------|
| 1 | Gradle 설정 + Manifest | �음 | 없음 |
| 2 | Models.kt | �음 | 없음 |
| 3 | WebSocketManager.kt | 중간 | 없음 |
| 4 | JsonRpcHandler.kt | 중간 | Models, WSManager |
| 5 | DeviceHandler.kt | 중간 | Models |
| 6 | NotificationService.kt | 중간 | Models |
| 7 | AppHandler.kt | 중간 | Models |
| 8 | AccessibilityBridge.kt | 높음 | 없음 |
| 9 | ScreenHandler.kt | 중간 | AccessibilityBridge |
| 10 | MediaHandler.kt | 높음 | 없음 |
| 11 | MainActivity + UI | 중간 | WSManager |
| 12 | ws_server.py | 중간 | 없음 |
| 13 | 문서 | 낮음 | 전체 �료 후 |
| 14 | 빌드 스크립트 | 낮음 | Gradle 설정 |

---

이 지시서를 바탕으로 OpenCode CLI가 Phase 1부터 순차적으로 코드를 생성하면 된다. 
각 Phase가 완료되면 코드 리뷰 후 다음 Phase로 진행한다.
