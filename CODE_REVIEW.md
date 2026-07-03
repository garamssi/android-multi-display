# DeskLink 코드 리뷰 보고서

작성일: 2026-07-06 · 대상 브랜치: `dev` · 검토 방식: 정적 리뷰 + 크로스플랫폼 대조 + 웹 기반 API 최신성 검증

---

## 1. 검토 범위와 방법

- **대상**: macOS 서버(Swift, SwiftPM, `macos/DeskLink/`) + Android 클라이언트(Kotlin, Compose, `android/`), 프로토콜 스펙(`docs/protocol-spec.md`).
- **방법**: 두 플랫폼 소스 전량 정독 → 와이어 프로토콜 핵심 파일을 양쪽 나란히 대조 → 핵심 결함은 실제 파일에서 재확인 → 배포/의존성 최신성은 웹 검색으로 교차 검증.
- **빌드 검증은 수행하지 못함**: macOS 타깃은 Apple 프레임워크(ScreenCaptureKit, VideoToolbox, CoreGraphics)가, Android는 Android SDK가 필요하나 이 리뷰 샌드박스(Linux)에 없음. 따라서 컴파일/테스트 실행이 아닌 정적 분석 결과임을 밝혀 둡니다.

> **중요한 전제**: 이 저장소는 "초안(scaffold)" 상태입니다. 도메인 모델·직렬화·네트워크 프레이밍 등 하부 계층은 구현+테스트되어 있으나, **수신 루프·비디오 파이프라인·입력 파이프라인·DI 바인딩 등 상위 연결부는 미완성**입니다. 아래에서 "실제 버그(작성된 코드의 결함)"와 "미구현 스캐폴딩(아직 안 만든 부분)"을 구분해 표기했습니다.

---

## 2. 종합 판정

| 영역 | 판정 |
|------|------|
| 프로토콜 상수·메시지 타입·프레이밍 | 양 플랫폼 **완전 일치** (양호) |
| 터치 직렬화(20바이트, Big-Endian) | 양 플랫폼 **완전 일치** + 테스트 라운드트립 통과 (양호) |
| 비디오 프레임/설정 와이어 포맷 | **스펙 위반** — 송신부가 헤더 없이 raw NAL만 전송, `VIDEO_CONFIG` 미전송 (심각) |
| macOS 캡처 API | 사용 중인 `CGDisplayStream`이 **폐기 예정 API** (높음) |
| Swift 바이트 파싱 | **정렬 위반 `load` = 미정의 동작** (심각) |
| Android 의존성 버전 | 동작엔 문제없으나 **~1.5년 구버전** (낮음, 정보) |
| targetSdk 35 / Play 정책 | **현행 기준 충족** (양호) |
| 수신·비디오·입력 파이프라인 종단 연결 | **미구현** (스캐폴딩) |
| PING/PONG keepalive, 재연결 트리거 | **미구현** (스캐폴딩) |

핵심 요약: **와이어 프로토콜의 하위 계층은 잘 맞물려 있으나, (a) 비디오 포맷 직렬화, (b) Swift 정렬 위반 파싱, (c) 폐기 예정 캡처 API 세 가지가 실제 결함으로 반드시 고쳐야 하며**, 나머지 상당수는 아직 배선하지 않은 미완성 부분입니다.

---

## 3. 크로스플랫폼 프로토콜 일치성 (직접 대조 결과)

양쪽 파일을 바이트 단위로 대조한 결과:

- **`PacketFramer`** (Swift/Kotlin): `[길이 uint32 BE][타입 uint8][페이로드]` 구조, 4MB 상한, 길이≥1 검증까지 동일. (일치)
- **`MessageType`** (0x01–0x0C, 0x10–0x12, 0x20–0x21): 값 전부 동일. (일치)
- **`ProtocolConstants`** (포트 7100–7102, 타이밍 상수, 버퍼 크기): 전부 동일. (일치)
- **터치 이벤트 직렬화**: `Action(1)+X(f32)+Y(f32)+Pressure(u16)+PointerID(1)+Timestamp(i64)` = 20바이트, Big-Endian. Kotlin `putFloat`/Swift `Float(bitPattern:)` 조합까지 정확히 대응. (일치, 배치 포맷 `count(u16)+events`도 일치)
- **비디오 포맷**: (불일치) 스펙 §4.3은 `VIDEO_FRAME` 페이로드에 `Timestamp(i64)+Flags(1)+FrameNumber(u32)` 13바이트 헤더를 요구하지만, 송신부(`StartStreamingUseCase.swift:34`)는 `encoded.data`(raw NAL)만 그대로 보냄. `VIDEO_CONFIG(0x11)`은 enum에만 존재하고 실제 전송/수신 코드가 없음. Android 측에도 이를 파싱하는 구현이 없어 양쪽 모두 스펙 미준수.

**결론**: 이미 구현된 컨트롤/입력 계층의 프로토콜 정합성은 우수. 비디오 계층만 스펙과 어긋나며, 이는 "미구현"이자 동시에 "이미 작성된 송신 코드의 결함"입니다.

---

## 4. macOS / Swift 발견사항

### 심각(Critical)

**S-C1. 정렬되지 않은 `load(fromByteOffset:as:)` = 미정의 동작(UB)**
`Sources/Data/Input/TouchDeserializer.swift:19,22,25,31`, `Sources/Data/Network/PacketFramer.swift:52`
```swift
let x = Float(bitPattern: UInt32(bigEndian: raw.load(fromByteOffset: offset, as: UInt32.self))) // offset=1
```
`UnsafeRawBufferPointer.load`는 대상 타입의 정렬을 만족하는 주소에서만 안전합니다. 터치 페이로드의 `UInt32` X/Y는 오프셋 1·5(홀수), `Int64` 타임스탬프는 오프셋 12(8정렬 아님)에 있어 정렬 위반입니다. Apple Silicon 릴리스 빌드에서 "우연히" 동작할 수 있으나 보장되지 않고, 새니타이저/특정 조건에서 트랩합니다. 테스트가 통과하는 것은 우연히 정렬된 힙 주소 덕분이라 더 위험합니다.
→ **수정**: 전부 `loadUnaligned(fromByteOffset:as:)`(macOS 12+, 본 프로젝트 타깃 macOS 14에서 사용 가능)로 교체.

**S-C2. `VIDEO_FRAME`을 13바이트 헤더 없이 전송 → 스펙 위반, 클라이언트 파싱 불가**
`Sources/Domain/UseCases/StartStreamingUseCase.swift:34`
`EncodedFrame`이 `timestampUs`/`isKeyframe`/`frameNumber`를 계산하지만 직렬화하지 않고 raw NAL만 보냄. 스펙 §4.3의 헤더가 통째로 빠져 키프레임 플래그·PTS·프레임번호를 수신측이 알 수 없음.
→ **수정**: `EncodedFrame.serialize()`(13바이트 헤더 + NAL) 추가 후 그 결과를 페이로드로 전송. Flags bit0 = `isKeyframe`.

**S-C3. `VIDEO_CONFIG`(SPS/PPS/VPS) 미전송 + Annex-B vs AVCC 포맷 불일치**
`Sources/Data/Encoding/HEVCEncoder.swift`(설정 추출은 함) + `StartStreamingUseCase.swift`(전송 안 함)
디코더 초기화에 필요한 CSD를 스트림 시작 시 보내지 않음(스펙 §4.2 위반). 또한 설정 데이터는 Annex-B(`00 00 00 01`)로 만들면서 VideoToolbox 프레임 출력은 길이 접두(AVCC) 형식이라 둘이 섞이면 디코딩 불가.
→ **수정**: 첫 프레임 전에 `VIDEO_CONFIG` 전송, NAL 포맷을 한쪽으로 통일(변환).

### 높음(High)

**S-H1. `AsyncStream` continuation을 매 접근마다 새로 생성/덮어씀**
`Sources/Data/Network/TCPServer.swift:11-17`, `Sources/Data/Network/ADBManager.swift:15-21`
computed property라 접근할 때마다 새 스트림을 만들고 이전 continuation을 폐기 → 소비자가 이벤트를 놓치거나 영원히 대기. `onTermination` 미설정으로 누수 가능.
→ **수정**: `init`에서 스트림+continuation을 1회 생성해 저장, getter는 저장된 것 반환, `onTermination` 설정.

**S-H2. 폐기 예정 `CGDisplayStream` 사용 + 화면 녹화 권한 미처리** *(웹 검증됨, §6 참조)*
`Sources/Data/Capture/ScreenCapturer.swift:8,42-73`
`CGDisplayStream`은 ScreenCaptureKit으로 대체 예정인 API이며 코드 스스로 `@available(macOS, deprecated: 14.0)`로 표기(그런데 그 어노테이션은 자기 래퍼 메서드에 붙어 경고만 유발, 실질 대응 아님). 타깃이 macOS 14라 "폐기된 그 OS에서 그대로 사용"하는 셈. TCC(화면 녹화) 권한 확인/요청도 없음.
→ **수정**: ScreenCaptureKit(`SCStream`+`SCContentFilter`)로 이관, 권한 요청 추가.

**S-H3. `async` 함수 내 블로킹 `Process.waitUntilExit()` + 파이프 데드락 위험**
`Sources/Data/Network/ADBManager.swift:66-87`
서스펜션 지점 없이 스레드를 블로킹해 협력 스레드풀을 굶길 수 있음. stdout/stderr를 한 파이프에 합친 뒤 배출 전에 `waitUntilExit()`를 호출 → `adb` 출력이 파이프 버퍼(~64KB)를 넘으면 상호 대기로 영구 정지 가능.
→ **수정**: 파이프를 먼저(또는 동시에) EOF까지 읽고, 블로킹 작업은 전용 큐/`continuation`으로 오프로드.

**S-H4. `NSLock`을 `DispatchSemaphore.wait()` 구간 내내 잡음**
`Sources/Data/Encoding/HEVCEncoder.swift:91-202`
인코딩 콜백을 세마포어로 대기하는 동안 락을 계속 보유 → 하드웨어 인코딩 지연 내내 협력 스레드 점유, 우선순위 역전/경합 위험.
→ **수정**: `HEVCEncoder`를 `actor`로 전환하거나, 세션을 락 안에서 스냅샷만 하고 인코딩/대기는 락 밖에서 수행.

### 중간(Medium) — 요약

- **S-M1**: `ScreenCapturer` 재시작 시 기존 `displayStream`을 stop/clear 않고 덮어써 이중 stop/누수 가능(`ScreenCapturer.swift:91-93`).
- **S-M2**: `IOSurfaceLock` 반환값 무시 + `bytesPerRow`(패딩된 stride)를 그대로 복사하면서 다운스트림은 `width*4` 가정 → 특정 해상도에서 프레임 왜곡(`ScreenCapturer.swift:58-64`).
- **S-M3**: `frame()`에서 `UInt32(1 + payload.count)`가 초대형 페이로드에서 트랩, 송신 경로엔 4MB 상한 미적용(`PacketFramer.swift:16-18`).
- **S-M4**: 설정 협상이 `supportedCodecs` 검증·0 해상도 거부·`keyframeInterval` 반영을 안 함(`HandshakeHandler.swift:48-76`).
- **S-M6**: CGEvent 주입에 Accessibility 권한 확인이 없어 권한 없으면 조용히 무동작(`CGEventInjector.swift:69-96`).
- **S-M7**: `isMouseDown`이 포인터별이 아니라 단일 Bool + 항상 왼쪽 버튼 → 멀티터치 상태 붕괴(`CGEventInjector.swift:11,55-64`).

### 낮음(Low) — 요약

- `TouchEvent.init`의 `precondition`은 릴리스에서도 트랩 → 신뢰 불가 입력으로 앱 크래시 소지(`TouchEvent.swift:12-13`).
- 잘못 적용된 `@available(deprecated:)`(경고만 양산), `@preconcurrency import`가 실제 Sendable 경고를 가림(`ScreenCapturer.swift:1-21`).
- `NWListener` 실패를 삼켜 상위로 전파 안 함(`TCPServer.swift:41-50`).
- ObjC 브리지: `performSelector:`로 init 계열 셀렉터 호출 → ARC 소유권 추론 불가(over/under-retain 위험), dealloc 경로 스레드 안전성 미흡(`CGVirtualDisplayBridge.m:96,24-26`).

---

## 5. Android / Kotlin 발견사항

### 심각(Critical)

**A-C1. 매 `read()`마다 누적 버퍼 전체 복사 → 대형 프레임에서 O(n²)** *(직접 재확인)*
`data/network/TCPClient.kt:62-82`
주석은 "O(n²)를 피한다"고 하지만, 실제로는 `accumulator.toByteArray()`로 전체를 복사하고 `offset=0`부터 재파싱함. 4MB 비디오 프레임이 ~8KB 청크로 도착하면 수백 번 재복사/재파싱 → 지연·ANR 위험(스펙의 ≤30ms 목표와 정면 충돌).
→ **수정**: 파싱 오프셋을 유지하고 프레임이 완성될 때만 compact. 매 read마다 전체 `toByteArray()` 금지.

**A-C2. `unframe` 크기 계산의 정수 오버플로 취약성**
`data/network/PacketFramer.kt:48-56`
언사인드 디코드(`and 0xFFFFFFFFL`)와 4MB 상한 검사는 정상이나, `4 + packetLength.toInt()`는 상한 검사 순서 덕에만 안전. 상수/순서가 바뀌면 `toInt()`가 음수로 래핑돼 `ByteArray(음수)` 유발 가능.
→ **수정**: 크기 계산을 전부 `Long`으로(`val totalSize = 4L + packetLength`), `.toInt()` 전에 상한 재확인.

**A-C3. MediaCodec 펌핑 모델 결함(입력 프레임 유실·vsync당 1버퍼)**
`data/codec/HEVCDecoder.kt:61-100`, `data/codec/VsyncRenderer.kt:27-35`
KDoc은 "async 콜백"이라지만 실제는 동기 API. `submitFrame`이 입력 버퍼 없으면 프레임을 **조용히 버림**(`if(inputIndex>=0)`에 else 없음). 출력은 vsync당 1개만 방출 → 버스트/재정렬 시 지연 누적. 키프레임 뒤 프레임 유실 시 다음 IDR까지 화면 깨짐. `INFO_OUTPUT_BUFFERS_CHANGED`(minSdk 28에서 전달됨) 미처리.
→ **수정**: async 콜백 채택 또는 동기 모드에서 vsync당 준비된 출력 모두 배출 + 입력 프레임 버퍼링/재시도. KDoc 정정. API 28 케이스 처리.

**A-C4. Hilt 리포지토리 바인딩 누락** *(직접 재확인 — 단, 스캐폴딩 성격)*
`di/AppModule.kt:9-12`(비어 있음), 소비자 `ConnectToServerUseCase`/`SendTouchUseCase`
`ConnectionManagerImpl`은 `ConnectionRepository`를 구현하지만 `@Binds`가 없어 인터페이스 타입으로 주입 불가. `InputRepository`/`VideoStreamRepository`는 구현 자체가 없음. 이 유스케이스들이 실제 주입 그래프(ViewModel/Activity)에 연결되면 Hilt 컴파일 검증(`MissingBinding`)에서 실패.
→ **수정**: `@Binds`로 `ConnectionManagerImpl`→`ConnectionRepository` 바인딩, 나머지 두 리포지토리 구현+바인딩.

### 높음(High)

- **A-H1**: `connect()`에 타임아웃 부재 + `soTimeout=0`(블로킹) → 서버가 침묵하면 `input.read()`가 영원히 대기, 이름과 달리 `TIMEOUT` 핸들러가 발동 못 함(`ConnectionManagerImpl.kt:45-94`, `TCPClient.kt:31`). → `withTimeout(HANDSHAKE_TIMEOUT)` 적용.
- **A-H2**: **PING/PONG keepalive 전무** — 상수/타입만 있고 송신·응답·타임아웃 추적 없음. Wi-Fi 단절 감지 불가, `reconnect()`는 죽은 코드(스캐폴딩).
- **A-H3**: 프레이밍 오류를 `RuntimeException`으로 던져 플로우 붕괴 → 상위에서 오해 소지 있는 `TIMEOUT`으로 매핑되고 소켓은 열린 채 방치(`TCPClient.kt:72-74`).
- **A-H4 / A-H5**: **입력·비디오 파이프라인 종단 미구현**(스캐폴딩) — `DisplayScreen`의 `SurfaceHolder.Callback`이 전부 빈 스텁, 터치 리스너·비디오 소켓·디코더 배선 없음.

### 중간·낮음 — 요약

- **A-M1**: 디코더 **입력**에 `BUFFER_FLAG_KEY_FRAME`(출력용 플래그) 지정 — 무의미/오용(`HEVCDecoder.kt:70-73`). → 일반 프레임은 `flags=0`.
- **A-M3/M4**: `releaseOutputBuffer(index, true)`는 즉시 표시라 vsync 페이싱 무의미 + Choreographer 루프가 메인 스레드에서 블로킹 `dequeueOutputBuffer` 수행 → 고프레임레이트에서 jank/ANR. → 타임스탬프 방출 형식 사용 + 전용 렌더 스레드.
- **A-M2**: HEVC CSD를 `csd-0` 하나로만 전달 — 서버가 hvcC(길이접두)로 보내면 `configure` 실패 가능(`HEVCDecoder.kt:34-38`).
- **A-M6**: 베이스 테마가 레거시 `android:Theme.Material.Light.NoActionBar`(구 프레임워크 Material) — Compose+Material3+edge-to-edge와 불일치(`res/values/themes.xml:3`). → `Theme.Material3.*` 계열로 교체.
- **A-L1**: 라이브러리 `currentCoroutineContext()`를 가리는 사설 함수 정의(`TCPClient.kt:96`). → stdlib 사용.
- **A-L4**: `SettingsViewModel.toDisplayConfig()` 결과가 어디서도 소비되지 않아 사용자 설정이 `connect()`에 전달 안 됨 + 기본 해상도 불일치(설정 2560×1600 vs `DisplayConfig` 1920×1200).
- **A-L7**: 핸드셰이크에서 `protocolVersion` 미검증(스펙 1001 PROTOCOL_MISMATCH 미사용), JSON 파싱 예외가 `TIMEOUT`으로 오매핑.

### 긍정적 확인 사항 (Android)

엔디안 처리(전부 `BIG_ENDIAN`), 20바이트 터치 직렬화(테스트 라운드트립 통과), 언사인드 길이 디코드, `enableEdgeToEdge()`·`WindowInsetsControllerCompat`·`Icons.AutoMirrored`(전부 현행 비폐기 API) 사용은 올바름. **실제로 폐기된 Kotlin/Compose "문법"은 발견되지 않음** — 폐기 관련 항목은 위 M6(레거시 XML 테마)가 사실상 유일.

---

## 6. 의존성 · API 최신성 (2026-07 기준, 웹 검증)

**macOS**
- `CGDisplayStream`: WWDC 2022에서 ScreenCaptureKit 도입과 함께 폐기 예정으로 공지됨. ScreenCaptureKit(macOS 12.3+)이 공식 대체. 신규 개발은 이관 권장. → S-H2 근거.

**Android (핀 버전 vs 2026-07 최신)**
| 항목 | 프로젝트 | 2026-07 최신 | 비고 |
|------|----------|--------------|------|
| AGP | 8.7.3 | 9.2.0 | 동작엔 무해, ~1.5년 구버전 |
| Kotlin | 2.1.0 | 2.3.x | 구버전(호환은 됨) |
| Compose BOM | 2024.12.01 | 2026.06.00 | 구버전 |
| targetSdk/compileSdk | 35 | 35 (Play 요구 충족) | **현행 기준 충족(양호)** |
| minSdk | 28 | — | 정책상 문제 없음 |

버전들은 서로 정합적이고 **폐기가 아니라 단지 구버전**입니다. Google Play는 2025-08-31부터 신규 앱/업데이트에 targetSdk 35를 요구하는데 이 프로젝트는 이미 충족합니다. 참고로 AGP 9.0부터는 Kotlin 지원이 내장되어 별도 `org.jetbrains.kotlin.android` 플러그인 적용이 불필요해지는 등, 업그레이드 시 빌드 스크립트 변경이 필요합니다.

---

## 7. 우선순위 수정 로드맵

**1순위 — 반드시 고쳐야 할 실제 결함**
1. `TouchDeserializer`/`PacketFramer`의 `load` → `loadUnaligned` (S-C1, UB/크래시, 수정 쉬움)
2. `VIDEO_FRAME` 13바이트 헤더 직렬화 추가 + `VIDEO_CONFIG` 전송 + NAL 포맷 통일 (S-C2/S-C3)
3. Android `TCPClient` 수신 루프의 전체 복사 제거 (A-C1)
4. `PacketFramer` 크기 계산 Long화 (A-C2)
5. MediaCodec 펌핑 정상화 + 디코더 입력 플래그 정정 (A-C3, A-M1)

**2순위 — 폐기 API / 견고성**
6. `CGDisplayStream` → ScreenCaptureKit 이관 + 화면 녹화 권한 (S-H2)
7. `AsyncStream` continuation 1회 생성 (S-H1), ADB 블로킹/파이프 데드락 (S-H3), 인코더 락/세마포어 (S-H4)
8. 핸드셰이크 타임아웃 (A-H1), PING/PONG keepalive + 재연결 트리거 (A-H2), 프레이밍 오류 처리 (A-H3)

**3순위 — 스캐폴딩 완성(미구현)**
9. Hilt 바인딩 + `Input/VideoStreamRepository` 구현 (A-C4)
10. 입력·비디오 파이프라인 종단 배선(SurfaceView 터치·디코더·소켓) (A-H4/H5)
11. macOS 수신부(입력/컨트롤 포트) 배선, 설정 협상 검증, 권한 처리 (S-M4/M6, A-L4/L7)

**4순위 — 정리**
12. 의존성 버전 업그레이드, 레거시 XML 테마 교체(A-M6), Low 항목 정리.

---

## 참고 출처
- ScreenCaptureKit / CGDisplayStream 폐기: [Apple WWDC22 "Meet ScreenCaptureKit"](https://developer.apple.com/videos/play/wwdc2022/10156/)
- AGP/Kotlin/Compose 최신 버전: [About Android Gradle plugin](https://developer.android.com/build/releases/about-agp), [AGP 9.2.0 릴리스 노트](https://developer.android.com/build/releases/gradle-plugin)
- Play targetSdk 35 요구: [Meet Google Play's target API level requirement](https://developer.android.com/google/play/requirements/target-sdk)
