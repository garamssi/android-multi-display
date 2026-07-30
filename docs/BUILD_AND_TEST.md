# DeskLink 빌드 · 설치 · 테스트 가이드

이 문서는 DeskLink(맥 서버 ↔ 안드로이드 클라이언트, 확장 디스플레이)를 빌드하고, 각 기기에 설치하고, 테스트하는 방법을 정리한다.

> 아키텍처 요약: **Mac = 서버**(가상 디스플레이 캡처 → HEVC 인코딩 → 전송, 포트 7100/7101/7102 리슨). **Android = 클라이언트**(수신 → 디코딩 → 확장 화면 렌더 → 터치 역전송). 우선 연결 모드는 **USB(ADB reverse 터널)**.

> **처음 셋업하는 경우 [setup.md](setup.md)를 먼저 본다.** 권한 부여(TCC, 안드로이드 런타임 권한), 인증서 생성(코드 서명 · TLS), 설치 스크립트 실행 순서를 환경별로 다룬다. 이 문서는 그 이후의 빌드 · 테스트 절차를 다룬다.

---

## 1. 사전 요구사항

> 이 절의 요구사항을 환경별 설치 단계로 풀어 쓴 것이 [setup.md](setup.md) 1~4절이다.

### 공통
- Git, 그리고 이 저장소 클론.
- Android 기기(태블릿 권장), USB 케이블.
- Mac과 Android 기기.

### macOS 서버 빌드용 (Mac에서)
- **macOS 14 (Sonoma)** 이상 — `Package.swift`의 `.macOS(.v14)`, `DeskLink-Info.plist`의 `LSMinimumSystemVersion`(14.0) 기준. Wi-Fi(LAN) 모드의 Bonjour 광고는 macOS 15 이상에서 `NSBonjourServices` 선언을 요구하며, 이미 Info.plist에 들어 있다.
- **Xcode 16 이상** — `swift-tools-version: 6.0`이므로 Swift 6 툴체인이 필요하다. 커맨드라인 도구: `xcode-select --install`.
- **adb** — Mac에 설치되어 있어야 하며, 서버 코드는 `/opt/homebrew/bin/adb` 또는 `/usr/local/bin/adb` 경로만 찾는다(하드코딩).
  ```bash
  brew install --cask android-platform-tools   # adb 설치 (Homebrew cask)
  which adb                                     # 위 두 경로 중 하나에 있어야 함
  ```
  Android Studio SDK의 adb만 있으면 앱이 찾지 못한다. 심볼릭 링크 방법은 [setup.md](setup.md) 2.2 참고.

### Android 클라이언트 빌드용
- **Gradle 실행용 JDK: 17 권장(또는 21).** 
  ```bash
  java -version   # 17.x 또는 21.x 권장
  ```
  - 주의: build.gradle.kts의 `jvmTarget = 17`은 "출력 바이트코드 목표"이지, 설치할 JDK 버전이 아니다. 진짜 중요한 건 **Gradle을 실행하는 JDK**다.
  - 이 프로젝트는 **Gradle 8.14.5**를 쓰며, Gradle 8.14 계열은 **JDK 25를 지원하지 않는다**(공식 호환성 문서 기준 데몬 실행 상한이 JDK 23~24). 따라서 시스템 JDK가 25여도 아래 중 하나로 17/21을 지정해야 한다:
    - Android Studio: Settings → Build, Execution, Deployment → Build Tools → Gradle → **Gradle JDK**를 17 또는 21로. (스튜디오 내장 JDK 사용 시 시스템 JDK가 25여도 대개 문제없음)
    - 터미널: `brew install --cask temurin@17` 후 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- **Android SDK (API 35)** + build-tools. **Android Studio**(최신) 사용을 권장.
- Gradle은 별도 설치 불필요 — 저장소의 Gradle Wrapper(`gradlew`)가 Gradle 8.14.5를 자동 내려받는다.
- `android/gradle.properties`의 `org.gradle.java.home`은 macOS 절대경로다. Windows/Linux에서는 `./gradlew`가 즉시 실패하므로 [setup.md](setup.md) 4.3의 우회가 필요하다.

### 기기 설정 (Android)
- 설정 → 휴대전화 정보 → 빌드번호 7회 탭 → 개발자 옵션 활성화.
- 개발자 옵션 → **USB 디버깅 켜기**.
- USB 연결 후 기기에서 "이 컴퓨터에서 USB 디버깅 허용" 승인.
  ```bash
  adb devices   # "<serial>   device" 로 보여야 함
  ```

---

## 2. 저장소 구조

```
android-multi-display/
├── macos/DeskLink/          # Swift 서버 (SwiftPM 패키지, 실행 타깃 "DeskLink")
│   ├── Package.swift
│   ├── DeskLink-Info.plist  # 번들 ID, LSUIElement, Bonjour/로컬 네트워크 선언
│   ├── scripts/             # create_cert.sh, create_tls_cert.sh, build_app.sh,
│   │                        #   build_and_run.command
│   ├── Sources/             # App / Domain / Data / Presentation
│   └── Tests/               # XCTest
├── android/                 # Android 클라이언트 (Gradle, Compose, Hilt)
│   ├── gradlew
│   └── app/                 # applicationId: com.desklink.android
├── docs/
│   ├── setup.md             # 환경별 초기 셋업 (권한 · 인증서 · 스크립트)
│   ├── protocol-spec.md     # 와이어 프로토콜 스펙 (정본)
│   └── BUILD_AND_TEST.md    # (이 문서)
└── tools/
    ├── protocol_vectors.py  # 언어 중립 골든 벡터 검증기
    └── pairing_vectors.py   # PIN → PSK 유도 골든 벡터
```

---

## 3. Part A — macOS 서버 빌드

### 3.1 커맨드라인 빌드 (로직 빌드/검증용)
```bash
cd macos/DeskLink
swift build            # 디버그 빌드
swift build -c release # 릴리스 빌드
```
산출물: `macos/DeskLink/.build/debug/DeskLink` (또는 `.build/release/DeskLink`).

### 3.2 서명된 앱으로 빌드·실행 (실제 기기 실행 경로)
```bash
cd macos/DeskLink
./scripts/create_cert.sh          # 최초 1회: "DeskLink Dev" 코드 서명 인증서 생성
./scripts/build_and_run.command   # 빌드 + 서명 + 실행
```
`build_and_run.command`는 기존 인스턴스를 종료해 포트(7100~7102)를 비우고, `swift build -c release` → `build/DeskLink.app` 조립 → `DeskLink Dev`로 서명 → 실행까지 한다. Finder에서 더블클릭해도 되며, 이때는 실행 비트가 필요하다(`chmod +x scripts/*.sh scripts/*.command`).

**서명된 `.app`으로만 기기 실행한다.** TCC(권한) 부여는 코드 서명 정체성에 묶이므로, `swift run`으로 띄운 미서명 실행 파일은 재빌드마다 화면 기록·손쉬운 사용 권한이 초기화된다. `swift build` / `swift test`는 로직 검증용이라 무관하다.

Xcode로 소스를 편집하려면 `open -a Xcode Package.swift`로 열 수 있다. 단 실행은 위 스크립트를 쓴다.

인증서 생성과 권한 부여의 단계별 절차는 [setup.md](setup.md) 2.3~2.6에 있다.

### 3.3 앱 실행과 권한에 대한 중요한 주의
서버는 SwiftUI `MenuBarExtra`(메뉴 막대 앱)이며, 다음 두 가지 시스템 권한이 반드시 필요하다:

1. **화면 녹화(Screen Recording)** — 가상 디스플레이 캡처(ScreenCaptureKit)에 필요. 최초 캡처 시 시스템 프롬프트가 뜨거나, 시스템 설정 → 개인정보 보호 및 보안 → **화면 기록**에서 DeskLink를 추가/허용해야 한다.
2. **손쉬운 사용(Accessibility)** — 터치를 마우스 이벤트로 주입(`CGEvent`)하는 데 필요. 시스템 설정 → 개인정보 보호 및 보안 → **손쉬운 사용**에서 DeskLink를 추가/허용. (코드가 `AXIsProcessTrusted()`로 확인하며 미허용 시 `INPUT_PERMISSION_DENIED(1301)` 에러를 낸다.)

> **권한 부여 후 재시작**: TCC 판정은 프로세스 시작 시점에 캐시된다. 권한을 켠 뒤에는 앱을 완전히 종료하고 다시 실행해야 `GRANTED`로 반영된다. `build_and_run.command`로 재실행하면 자동으로 처리된다.

> **배포 제약**: 가상 디스플레이 생성은 비공개 API(`CGVirtualDisplay`)에 의존한다. App Store 배포는 불가하며, macOS 버전에 따라 동작이 바뀔 수 있다.

---

## 4. Part B — Android 클라이언트 빌드

### 4.1 커맨드라인 빌드
```bash
cd android
./gradlew assembleDebug     # 디버그 APK 빌드
```
산출물: `android/app/build/outputs/apk/debug/app-debug.apk`.

릴리스 빌드(코드 축소/ProGuard 적용):
```bash
./gradlew assembleRelease   # 서명 설정 필요
```

### 4.2 Android Studio로 열기 (권장)
Android Studio → Open → `android/` 폴더 선택 → Gradle sync 완료 후 상단 Run(실행) 버튼 클릭.

---

## 5. 설치

### 5.1 macOS 서버
- 3.2의 `./scripts/build_and_run.command`를 실행하면 메뉴 막대에 DeskLink 아이콘이 나타난다(Dock 아이콘은 없다 — `LSUIElement`).
- 최초 실행 시 위 3.3의 **화면 기록 / 손쉬운 사용** 권한을 부여하고 앱을 재시작한다.
- 별도의 설치 위치는 없다. 산출물은 `macos/DeskLink/build/DeskLink.app`이며 그 자리에서 실행된다.

### 5.2 Android 클라이언트
USB 연결·디버깅 승인 후:
```bash
cd android
./gradlew installDebug      # 연결된 기기에 설치
# 또는
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
앱 실행:
```bash
adb shell am start -n com.desklink.android/.presentation.MainActivity
```
또는 Android Studio에서 Run(실행) 버튼 클릭. 앱 이름은 **DeskLink**.

---

## 6. 연결 (USB / ADB reverse — 우선 모드)

Mac이 서버이고 Android가 `127.0.0.1`로 접속하는 클라이언트이므로 **`adb reverse`**(device→host)를 사용한다. (`adb forward`는 반대 방향이라 이 구조에서는 연결되지 않는다.)

macOS 서버 앱은 시작 시 아래 reverse 터널을 **자동 설정**한다(`ADBManager`). 수동으로 설정/확인하려면:
```bash
adb reverse tcp:7100 tcp:7100   # Control
adb reverse tcp:7101 tcp:7101   # Video
adb reverse tcp:7102 tcp:7102   # Input

adb reverse --list              # 터널 확인
adb reverse --remove-all        # 전체 해제
```
이후 Android 앱의 연결 화면에서 호스트를 `127.0.0.1`(localhost)로 두고 연결한다.

### Wi-Fi 모드 (대안)
같은 네트워크에서 Android 앱의 호스트에 **Mac의 IP 주소**를 입력해 연결한다(LAN 포트 7110~7112, TLS+PIN). adb 터널은 불필요. USB 스택(7100~7102)은 그대로 켜져 있어 Wi‑Fi를 켜도 USB는 PIN 없이 연결된다. USB 대비 지연이 크다(스펙 목표 ≤60ms).

사전 준비 두 가지가 필요하다:
1. `./scripts/create_tls_cert.sh`로 TLS 서버 인증서("DeskLink TLS Server")를 만든다. 없으면 서버가 LAN을 **평문으로** 서비스하며 로그에 `Serving PLAINTEXT`를 남긴다.
2. Settings → Connection → `Allow Wi-Fi (LAN) connections`를 켜고 서버를 Stop → Start 한다(다음 Start부터 적용).

같은 화면에 표시되는 6자리 PIN을 태블릿에 입력해 페어링한다. PIN은 60초마다 회전한다. 자세한 절차는 [setup.md](setup.md) 2.7과 3.6 참고.

---

## 7. 종단(E2E) 실행 순서

1. Mac에서 DeskLink 서버 실행 + 권한 부여 확인.
2. USB로 Android 기기 연결, `adb devices`로 인식 확인.
3. (자동이 안 되면) 위 `adb reverse` 3줄 실행.
4. Android DeskLink 앱 실행 → 연결(호스트 `127.0.0.1`).
5. 핸드셰이크 → 설정 협상 → 스트림 시작 → 태블릿에 확장 화면 표시.
6. 태블릿을 터치하면 Mac 가상 디스플레이에 입력이 주입된다.
7. 종료: 태블릿 뒤로가기(정상 disconnect) 또는 Mac 메뉴에서 중지.

---

## 8. 테스트

### 8.1 프로토콜 골든 벡터 (언어 중립, 즉시 실행 가능)
와이어 포맷이 스펙과 일치하는지 검증하는 기준값 오라클. Python만 있으면 실행된다.
```bash
python3 tools/protocol_vectors.py   # "ALL CHECKS PASS" 확인
python3 tools/pairing_vectors.py    # PIN → PSK 유도 벡터 (LAN 페어링)
```
양 플랫폼의 단위 테스트가 이 파일과 동일한 hex(예: TOUCH_EVENT `023F0000003E800000800001000462D53C8ABAC0`)를 단언하므로, 크로스플랫폼 회귀 방지에 사용한다.

`pairing_vectors.py`는 합격 문구 없이 벡터만 출력한다. Swift(CryptoKit)와 Kotlin(HMAC-SHA256)이 같은 PSK를 유도하는지 확인하는 기준값이다.

### 8.2 macOS 단위 테스트 (XCTest)
```bash
cd macos/DeskLink
swift test                              # 전체
swift test --filter EncodedFrameTests   # 특정 테스트만
```
커버리지:
- 프로토콜/네트워크 — `PacketFramerTests`, `FrameAccumulatorTests`, `MessageTypeTests`, `ControlMessageTests`, `ConnectionMonitorTests`, `HandshakeHandlerTests`, `ControlChannelUseCaseTests`, `PortForwardingWatcherTests`
- 입력 — `TouchDeserializerTests`, `TouchEventTests`, `PointerButtonCoderTests`, `ScrollCoderTests`, `CGEventInjectorMappingTests`, `ReceiveInputUseCaseTests`
- 비디오/디스플레이 — `EncodedFrameTests`, `VideoConfigMessageTests`, `AnnexBConverterTests`, `CaptureVerifierTests`, `VirtualDisplayManagerTests`, `DisplayConfigTests`
- 보안/페어링 — `PairingCryptoTests`, `PairingAuthTests`, `PairingPinTests`, `AuthGateTests`
- 설정/UI 상태 — `TransportSettingsTests`, `SettingsViewModelTests`, `DiagnosticLogParserTests`

### 8.3 Android 단위 테스트 (JUnit5 + MockK + Turbine, JVM)
기기 없이 JVM에서 실행된다.
```bash
cd android
./gradlew test                  # 전체 단위 테스트
./gradlew testDebugUnitTest     # 디버그 변형만
```
리포트: `android/app/build/reports/tests/testDebugUnitTest/index.html`.
커버리지:
- 프로토콜/네트워크 — `PacketFramerTest`, `TCPClientReframingTest`, `ConnectionManagerImplTest`, `KeepAliveControllerTest`, `ConnectionErrorTest`, `ConnectionStateTest`
- 전송/전송 선택 — `UsbTransportTest`, `RoutingTransportTest`, `DiscoveredServerRegistryTest`
- 보안/페어링 — `PairingCryptoTest`, `PairingAuthTest`, `CertPinStoreTest`, `RoutingSecureChannelTest`
- 입력 — `TouchSerializerTest`, `TouchEventTest`, `PointerButtonSerializerTest`, `ScrollSerializerTest`, `InputRepositoryImplTest`, `SendTouchUseCaseTest`
- 비디오 — `HEVCDecoderTest`, `VideoProtocolTest`, `VideoStreamRepositoryImplTest`
- 표시/제스처 — `DisplayConfigTest`, `DisplayRotationTest`, `ViewZoomTest`, `FlingDecayTest`, `VelocityTracker2DTest`, `LongPressDetectorTest`, `DisplayViewModelTouchGateTest`
- 설정/배선 — `SettingsViewModelTest`, `ConnectionWiringTest`

### 8.4 정적 분석 (선택)
```bash
cd android && ./gradlew lint    # Android Lint 리포트
```

### 8.5 수동 시나리오 테스트 (기기 필요)
기기 연결 후 아래 **SC-1 ~ SC-9**를 순서대로 수행한다. 첫 연결 자체를 검증하는 절차는 [setup.md](setup.md) 5.2에 있다.
- SC-1 USB 정상 스트리밍(지연 ≤30ms, 60fps)
- SC-2 Wi-Fi 적응형 비트레이트
- SC-3 터치 정확성(멀티터치 포인터별 상태)
- SC-4 핸드셰이크 타임아웃(5초)
- SC-5 연결 끊김 감지·지수 백오프 재연결
- SC-6 스트림 중 해상도 변경
- SC-7 대형 프레임/부분 수신 견고성
- SC-8 권한 없음 처리(명시적 에러)
- SC-9 정상 종료(자원 해제, 누수 없음)

---

## 9. 문제 해결 (Troubleshooting)

| 증상 | 원인/확인 | 조치 |
|------|-----------|------|
| `adb devices`에 기기 없음 | USB 디버깅 미승인 | 케이블/포트 교체, 기기에서 디버깅 허용, `adb kill-server && adb start-server` |
| 서버가 adb를 못 찾음 | adb가 `/opt/homebrew/bin` 또는 `/usr/local/bin`에 없음 | `brew install --cask android-platform-tools`, 또는 [setup.md](setup.md) 2.2의 심볼릭 링크 |
| 연결이 안 됨(USB) | `adb forward`를 잘못 사용 | 반드시 `adb reverse` 사용(§6), `adb reverse --list`로 3포트 확인 |
| 화면이 안 나옴/캡처 실패 | 화면 기록 권한 미부여 | 시스템 설정 → 개인정보 보호 및 보안 → 화면 기록에서 DeskLink 허용 후 재시작 |
| 터치가 반응 없음 | 손쉬운 사용 권한 미부여 | 시스템 설정 → 손쉬운 사용에서 DeskLink 허용(에러 1301) |
| 권한이 재빌드 후 초기화 | 미서명 실행 파일(`swift run`) | `./scripts/build_and_run.command`로 서명된 `.app` 실행(§3.2) |
| `codesign failed with identity 'DeskLink Dev'` | 코드 서명 인증서 없음 | `./scripts/create_cert.sh` 실행 |
| LAN이 평문으로 서비스됨(`Serving PLAINTEXT`) | TLS 서버 인증서 없음 | `./scripts/create_tls_cert.sh` 후 서버 재시작 |
| Gradle sync 실패 | JDK 17/21 아님 | JDK 17 또는 21 설치·지정 |
| Windows/Linux에서 `./gradlew` 실패 | `gradle.properties`의 macOS 절대경로 | [setup.md](setup.md) 4.3 우회 |
| 포트 충돌(7100~7102) | 다른 프로세스 점유 | `lsof -i :7100` 확인 후 종료. `build_and_run.command`는 자동 처리 |

---

## 10. 알려진 제약 / 미검증

- `CGVirtualDisplay`는 비공개 API이며 macOS 버전 의존성이 있다. 요청 해상도와 실제 모드가 어긋나면 로그에 `MISMATCH: private-API mode fallback`이 남는다.
- VideoToolbox(Mac) ↔ MediaCodec(Android) 실제 디코딩 상호운용은 기기에서만 최종 확인 가능하다.
- Wi-Fi(LAN)는 실험 기능이다. TLS로 암호화되지만 자체 서명 인증서 + TOFU 핀 방식이며, 신뢰할 수 있는 네트워크에서만 사용한다.
- Android 앱은 `POST_NOTIFICATIONS`를 런타임에 요청하지 않는다. Android 13 이상에서 연결 유지 알림이 표시되지 않으므로 수동 부여가 필요하다([setup.md](setup.md) 3.4).
