# DeskLink 초기 환경 셋업 가이드

새 컴퓨터 또는 새 기기에서 DeskLink를 처음부터 동작시키기 위한 절차를 정리한다.
권한 부여, 인증서 생성, 설치 스크립트 실행까지 순서대로 따라가면 된다.

빌드 명령과 테스트 항목의 상세는 `docs/BUILD_AND_TEST.md`, 와이어 프로토콜은
`docs/protocol-spec.md`를 정본으로 삼는다. 이 문서는 "아무것도 설치되지 않은 상태에서
무엇을 어떤 순서로 해야 하는가"만 다룬다.

---

## 0. 시작 전 알아야 할 구조

| 역할 | 기기 | 하는 일 |
|------|------|---------|
| 서버 | Mac | 가상 디스플레이 생성 → 화면 캡처 → HEVC 인코딩 → 전송, 터치 입력 주입 |
| 클라이언트 | Android 태블릿 | 수신 → 디코딩 → 확장 화면 렌더 → 터치 역전송 |

- 기본 전송은 **USB**다. Mac이 리슨하고 태블릿이 `127.0.0.1`로 접속하므로
  `adb reverse`(device → host)를 쓴다. `adb forward`는 방향이 반대라 연결되지 않는다.
- USB 스택은 항상 켜져 있다(loopback, 평문, PIN 없음). Wi-Fi는 선택 기능이며 켜면
  LAN 스택(TLS + 6자리 PIN 페어링)이 추가로 뜬다.

| 채널 | USB 포트 | LAN 포트 |
|------|----------|----------|
| Control | 7100 | 7110 |
| Video | 7101 | 7111 |
| Input | 7102 | 7112 |

셋업이 필요한 환경은 세 가지다. 필요한 것만 골라 진행한다.

| 환경 | 할 수 있는 일 | 해당 절 |
|------|---------------|---------|
| macOS 머신 | 서버 빌드 · 실행, Android 빌드, 전체 E2E | 2절 |
| Android 태블릿 | 클라이언트 실행 | 3절 |
| Windows / Linux 머신 | Android 클라이언트 빌드 · 단위 테스트, 프로토콜 벡터 검증 (서버는 불가) | 4절 |

---

## 1. 공통 사전 준비

### 1.1 저장소 클론

```bash
git clone https://github.com/garamssi/android-multi-display.git
cd android-multi-display
```

### 1.2 Python 3.10 이상

프로토콜 골든 벡터 검증기(`tools/*.py`)를 돌리는 데 쓴다. 외부 의존성은 없다.
`tools/protocol_vectors.py`가 `str | None` 문법을 사용하므로 3.10 이상이 필요하다.

```bash
python3 --version   # 3.10 이상
```

Windows는 `python --version`으로 확인한다.

### 1.3 첫 검증

플랫폼 툴체인을 설치하기 전에도 바로 돌려볼 수 있다. 여기서 실패하면 클론이
잘못된 것이다.

```bash
python3 tools/protocol_vectors.py   # 마지막 줄 "ALL CHECKS PASS"
python3 tools/pairing_vectors.py    # PIN별 PSK 벡터 3줄 출력
```

Windows에서는 `python3`를 `python`으로 바꿔 실행한다. 이 문서의 나머지 `python3`
명령도 같다.

`pairing_vectors.py`는 합격 문구를 출력하지 않고 벡터만 찍는다. 기대값:

```
000000 -> bc4f4adccff971132b24c0dcdebaec75574683fe9fa84471533d9f88ff492016
123456 -> 97a17f725a8dbce5993a82f3d43ca7cd569acb9756ca2e656607726e110e83b8
987654 -> 9893e3e98b4de2e22c4aced3cfce08827e28461827f405d2dbaafe8559660d79
```

---

## 2. macOS 서버 머신 셋업

### 2.1 툴체인

**요구 OS 버전.** `Package.swift`는 `.macOS(.v14)`, `DeskLink-Info.plist`의
`LSMinimumSystemVersion`은 `14.0`이다. 즉 빌드와 실행의 최소 요구는
**macOS 14 (Sonoma)**이다. 단 아래 두 가지는 버전 의존성이 있다.

- 가상 디스플레이 생성은 비공개 API `CGVirtualDisplay`에 의존한다. macOS 업데이트로
  동작이 바뀔 수 있고, App Store 배포는 불가하다.
- Wi-Fi(LAN) 모드의 Bonjour 광고는 macOS 15 이상에서 `NSBonjourServices` 선언을
  요구한다. Info.plist에 이미 들어 있으므로 추가 작업은 없다.

**Xcode.** `swift-tools-version: 6.0`이므로 Swift 6 툴체인이 포함된 **Xcode 16 이상**이
필요하다. App Store에서 설치한 뒤:

```bash
xcode-select --install                                            # 커맨드라인 도구
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer    # 활성 툴체인 지정
xcode-select -p                                                   # 위 경로가 나와야 한다
swift --version                                                   # Swift 6.x 확인
```

`swift --version`이 6 미만이면 Xcode가 아니라 Command Line Tools가 활성 툴체인일
가능성이 높다. `xcode-select -p`로 확인하고 위 `-s` 명령을 다시 실행한다. Xcode 자체가
16 미만이면 App Store에서 업데이트한다.

**Homebrew.** 아직 없으면 설치한다.

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

### 2.2 adb 설치와 경로 (중요)

```bash
brew install --cask android-platform-tools
which adb
```

**서버 코드가 adb 경로를 하드코딩하고 있다.** `ADBManager`는 아래 두 경로만 순서대로
찾고, 둘 다 없으면 `ConnectionError.refused`를 던진다.

```
/opt/homebrew/bin/adb    # Apple Silicon Homebrew
/usr/local/bin/adb       # Intel Homebrew
```

Android Studio에 딸린 SDK의 adb만 있으면 앱이 찾지 못한다. 그 경우 심볼릭 링크를 만든다.

```bash
sudo mkdir -p /usr/local/bin   # Apple Silicon에는 없을 수 있다
sudo ln -s "$HOME/Library/Android/sdk/platform-tools/adb" /usr/local/bin/adb
/usr/local/bin/adb version     # 실행되는지 확인
```

`adb`가 여러 개 설치되어 있으면 adb 서버 버전이 충돌해 기기 인식이 불안정해진다.
하나로 통일한다.

### 2.3 스크립트 실행 권한

`macos/DeskLink/scripts/`의 스크립트 4개는 클론 직후 실행 비트가 없을 수 있다.
한 번만 부여한다.

```bash
cd macos/DeskLink
chmod +x scripts/*.sh scripts/*.command
```

| 스크립트 | 언제 실행 | 횟수 |
|----------|-----------|------|
| `scripts/create_cert.sh` | 코드 서명 인증서 생성 (TCC 권한 유지용) | 최초 1회 |
| `scripts/create_tls_cert.sh` | LAN용 TLS 서버 인증서 생성 | Wi-Fi 쓸 때 1회 |
| `scripts/build_app.sh` | 빌드 + 서명 | 코드 변경마다 |
| `scripts/build_and_run.command` | 기존 프로세스 종료 + 빌드 + 서명 + 실행 | 평소 사용 |

`build_and_run.command`는 Finder에서 더블클릭해도 된다. 실행 비트가 없으면 텍스트
편집기로 열리므로 위 `chmod +x`를 먼저 해야 한다.

### 2.4 인증 — 인증서 2개 (성격이 다르다)

두 인증서는 목적이 완전히 다르다. 하나로 대체할 수 없다.

#### (a) 코드 서명 인증서 — "DeskLink Dev"

macOS의 권한 부여(TCC)는 앱의 **코드 서명 정체성**에 묶인다. 서명이 없거나 매번
바뀌면 재빌드마다 화면 기록·손쉬운 사용 권한이 초기화된다. 안정적인 자체 서명
정체성을 한 번 만들어 두면 권한이 유지된다.

```bash
cd macos/DeskLink
./scripts/create_cert.sh
```

스크립트가 하는 일: RSA 2048 키 + `codeSigning` 확장을 가진 자체 서명 인증서를 만들고,
인증서와 개인키를 PKCS#12로 묶어 login 키체인에 임포트한다(`-T /usr/bin/codesign`으로
codesign이 키를 쓸 수 있게 허용). 실행 중 Mac 로그인 암호를 물을 수 있다.

확인:

```bash
security find-identity -p codesigning | grep "DeskLink Dev"
```

여기서 안 보여도 `codesign`은 성공할 수 있다(자체 서명은 "신뢰됨"으로 집계되지 않음).
실제 판정은 다음 절의 빌드다. `build_app.sh`가 codesign 실패로 멈추면 키체인 접근에서
`DeskLink Dev`를 더블클릭 → 신뢰 펼치기 → "코드 서명: 항상 신뢰"로 바꾼 뒤 다시 빌드한다.

> 서명 아이덴티티 이름은 `build_app.sh`의 `SIGN_ID="DeskLink Dev"`와 정확히 일치해야
> 한다. 인증서 이름을 바꾸면 스크립트도 같이 바꿔야 한다.

#### (b) TLS 서버 인증서 — "DeskLink TLS Server"

Wi-Fi(LAN) 채널을 TLS로 서비스하기 위한 것이다. USB(loopback)는 평문이라 쓰지 않는다.
**Wi-Fi를 쓸 계획이 없으면 건너뛴다.**

```bash
./scripts/create_tls_cert.sh
```

스크립트가 하는 일: EC P-256 키 + `serverAuth` 확장 자체 서명 인증서를 만들어 PKCS#12로
login 키체인에 임포트한다. `security import -A`를 쓰는 이유는 TLS 핸드셰이크 중
키체인 ACL 프롬프트가 떠서 연결이 막히는 것을 방지하기 위함이다.

이 인증서는 **암호화만** 담당한다. "정말 내 Mac인가"의 인증은 그 위에 얹힌 6자리 PIN
페어링이 한다. 태블릿은 첫 접속 시 인증서 지문을 저장하고(TOFU) 이후 변경을 거부한다.

이 인증서가 없으면 서버는 LAN을 **평문으로** 서비스하며 로그에 다음을 남긴다.

```
LAN TLS identity not found — run scripts/create_tls_cert.sh. Serving PLAINTEXT.
```

Wi-Fi를 쓰면서 이 로그가 보이면 즉시 중단하고 스크립트를 실행한다.

인증서를 회전하려면 스크립트를 다시 실행한다. 단 태블릿이 저장해 둔 인증서 지문과
새 인증서가 어긋나 연결이 거부되므로, 태블릿 앱 데이터를 지워 저장된 지문을 초기화해야
한다. 여기서 말하는 지문은 6자리 페어링 PIN과 무관한 별개의 값이다.

### 2.5 빌드와 실행

```bash
cd macos/DeskLink
./scripts/build_and_run.command
```

내부 동작: 실행 중인 `DeskLink` 프로세스 종료(포트 7100~7102 해제) → `swift build -c release`
→ `build/DeskLink.app` 조립(Info.plist, `AppIcon.icns`, 폰트 리소스 번들 복사) →
`DeskLink Dev`로 서명 → 서명 검증 → `open`.

성공하면 메뉴 막대에 DeskLink 아이콘이 생긴다. Dock 아이콘은 없다(`LSUIElement`).

**서명된 `.app`으로만 실행한다.** `swift run`으로 띄운 미서명 실행 파일은 재빌드마다
TCC 권한이 초기화된다. 로직 검증 목적의 `swift build` / `swift test`는 무관하다.

빌드 로그에 다음 경고가 보이면 무시하지 않는다.

| 경고 | 의미 |
|------|------|
| `WARNING: AppIcon.iconset not found` | 아이콘 없이 빌드됨. 클론이 불완전할 수 있다 |
| `WARNING: DeskLink_DeskLink.bundle not found` | IBM Plex 폰트 미포함, 시스템 폰트로 대체됨 |

### 2.6 시스템 권한 (TCC) 부여

서버는 두 권한이 **반드시** 필요하다.

| 권한 | 왜 필요한가 | 없을 때 증상 |
|------|-------------|--------------|
| 화면 기록 (Screen Recording) | ScreenCaptureKit으로 가상 디스플레이 캡처 | 화면이 검게 나오거나 캡처 실패 |
| 손쉬운 사용 (Accessibility) | `CGEvent`로 터치를 마우스 입력으로 주입 | 터치 무반응, 에러 1301 `INPUT_PERMISSION_DENIED` |

부여 절차:

1. 메뉴 막대 아이콘 클릭 → `Settings…`
2. `Permissions` 섹션에서 두 항목의 상태를 본다. `NOT GRANTED`면 `Request`(시스템
   프롬프트 표시) 또는 `Open`(시스템 설정 해당 페이지로 이동) 버튼을 누른다.
3. 시스템 설정 → 개인정보 보호 및 보안 → **화면 기록** / **손쉬운 사용**에서 DeskLink를
   켠다. 목록에 없으면 `+`로 `macos/DeskLink/build/DeskLink.app`을 직접 추가한다.
4. **DeskLink를 완전히 종료하고 다시 실행한다.** TCC 판정은 프로세스 시작 시점에
   캐시되므로, 권한을 켠 뒤 재시작하지 않으면 계속 미부여로 보인다.
5. Settings의 두 항목이 `GRANTED`로 바뀌었는지 확인한다.

`build_and_run.command`는 재빌드 전에 기존 프로세스를 종료하므로, 이 스크립트로
다시 실행하면 4번이 자동으로 처리된다.

Wi-Fi를 켠 경우 추가로 두 가지가 뜰 수 있다.

- **로컬 네트워크 접근 허용** 프롬프트(`NSLocalNetworkUsageDescription` 문구가 보인다).
  허용해야 태블릿이 Bonjour로 Mac을 찾을 수 있다.
- **방화벽 수신 연결 허용** 프롬프트. 방화벽이 켜져 있으면 허용한다.
  USB는 loopback이라 방화벽과 무관하다.

### 2.7 Wi-Fi 모드 켜기 (선택)

1. 2.4(b)의 TLS 인증서를 먼저 만든다.
2. Settings → `Connection` → `Allow Wi-Fi (LAN) connections` 토글을 켠다.
   **다음 Start부터 적용된다**(설명 문구 그대로). 서버를 Stop → Start 한다.
3. 같은 섹션에 Mac의 로컬 IP와 6자리 페어링 PIN이 표시된다. PIN은 60초마다 회전하고
   `Copy` 버튼으로 복사할 수 있다.
4. 메뉴 막대 상태가 `USB · Wi-Fi`로 바뀐다.

Wi-Fi는 실험 기능이다. USB 대비 지연이 크고(목표 60ms 대 30ms), 신뢰할 수 있는
네트워크에서만 쓰라고 앱이 경고한다.

### 2.8 macOS 셋업 검증

```bash
cd macos/DeskLink
swift build            # 컴파일 통과
swift test             # 단위 테스트 통과
cd ../..
python3 tools/protocol_vectors.py   # ALL CHECKS PASS
```

여기까지 통과하고 메뉴 막대 → Settings에서 두 권한이 `GRANTED`면 서버 머신 셋업은
끝이다.

---

## 3. Android 태블릿 셋업

### 3.1 기기 요구사항

- **Android 9 (API 28) 이상.** `minSdk = 28`.
- 하드웨어 HEVC 디코더. 최근 기기는 대부분 지원한다.
- 해상도 프리셋은 기기 네이티브 해상도에서 런타임에 계산된다. 별도 설정은 없다.

### 3.2 개발자 옵션과 USB 디버깅 인증

1. 설정 → 태블릿 정보 → 빌드번호를 7회 탭 → 개발자 옵션 활성화.
2. 개발자 옵션 → **USB 디버깅** 켜기.
3. USB 케이블로 Mac(또는 개발 머신)에 연결.
4. 태블릿에 "USB 디버깅을 허용하시겠습니까?" RSA 지문 대화상자가 뜬다.
   **"이 컴퓨터에서 항상 허용"을 체크하고 허용**한다. 체크하지 않으면 재연결마다
   다시 승인해야 하고, 승인 전에는 `adb`가 기기를 `unauthorized`로 본다.

```bash
adb devices
# <serial>   device        <- 정상
# <serial>   unauthorized  <- 4번 승인이 안 된 상태
# 목록이 빔                 <- 케이블/포트 또는 USB 디버깅 미활성
```

`unauthorized`가 계속되면 개발자 옵션 → "USB 디버깅 승인 취소"로 초기화한 뒤 다시
연결해 승인한다.

케이블은 데이터 전송이 되는 것을 쓴다. 충전 전용 케이블은 `adb devices`에 아무것도
나오지 않는다.

### 3.3 앱 설치

빌드 머신에서 실행한다(Mac / Windows / Linux 모두 가능, 4절 참고).

```bash
cd android                        # 저장소 루트에서 시작
./gradlew installDebug            # 빌드 + 연결된 기기에 설치
```

또는 이미 만든 APK를 설치한다(경로는 `android/` 안에서 실행한 기준).

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

실행:

```bash
adb shell am start -n com.desklink.android/.presentation.MainActivity
```

앱 이름은 **DeskLink**, 패키지는 `com.desklink.android`다.

### 3.4 앱 권한

USB 모드는 **런타임 권한이 필요 없다.** 아래는 Wi-Fi와 백그라운드 유지에 관한 것이다.

| 권한 | 대상 | 부여 방법 |
|------|------|-----------|
| `NEARBY_WIFI_DEVICES` | Android 13 (API 33) 이상, Wi-Fi 검색 | 앱의 Wi-Fi 탭에서 `Find Macs` 버튼을 누르면 시스템 프롬프트가 뜬다 |
| 알림 (`POST_NOTIFICATIONS`) | Android 13 이상, 연결 유지 알림 | **수동 부여 필요** (아래 참고) |
| `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE*`, `INTERNET` 등 | 전부 | 설치 시 자동, 사용자 조작 없음 |

**알림 권한은 앱이 런타임에 요청하지 않는다.** 매니페스트에는 선언되어 있지만 요청
코드가 없어서 Android 13 이상에서는 기본 거부 상태로 남는다. 연결 유지용 포그라운드
서비스(`MirrorConnectionService`) 자체는 동작하지만 상태 표시줄 알림이 보이지 않는다.
알림을 보려면 수동으로 켠다.

```
설정 → 앱 → DeskLink → 알림 → 허용
```

또는 adb로:

```bash
adb shell pm grant com.desklink.android android.permission.POST_NOTIFICATIONS
```

알림이 없어도 미러링은 동작한다. 백그라운드 전환 시 연결 유지가 실패하면
`adb logcat -s DeskLink`에서 `startForeground failed` 로그를 확인한다.

### 3.5 USB 연결 (기본 경로)

reverse 터널은 Mac 서버 앱이 자동으로 설정하고 유지한다. `PortForwardingWatcher`가
1초 간격으로 기기 존재를 확인해 재연결(재플러그, adbd 재시작)마다 매핑을 다시 건다.

수동 확인이나 서버 없이 테스트할 때:

```bash
adb reverse tcp:7100 tcp:7100   # Control
adb reverse tcp:7101 tcp:7101   # Video
adb reverse tcp:7102 tcp:7102   # Input

adb reverse --list              # 3줄 확인
adb reverse --remove-all        # 전체 해제
```

태블릿 앱에서 USB를 선택하고 연결한다. 호스트는 `127.0.0.1`로 고정되어 있어 입력할
필요가 없다.

### 3.6 Wi-Fi 연결과 PIN 페어링 (선택)

1. Mac에서 2.7의 Wi-Fi 모드를 켜 둔다.
2. 태블릿 앱에서 Wi-Fi 탭 선택 → `Find Macs`로 권한을 허용하면 Bonjour 검색이 시작된다.
3. 목록에서 Mac을 고르거나, 검색이 안 되면 IP를 직접 입력한다(Mac Settings의
   `Connection` 섹션에 표시된 주소).
4. Mac Settings의 6자리 PIN을 태블릿에 입력한다. PIN은 60초마다 바뀌므로 만료되면
   Mac 화면의 새 값을 쓴다.
5. 첫 연결에서 태블릿이 서버 TLS 인증서 지문을 저장한다. 이후 지문이 바뀌면 연결을
   거부한다(Mac에서 TLS 인증서를 재발급했다면 태블릿 앱 데이터를 지운다).

PIN 자체는 네트워크로 전송되지 않는다. 양쪽이 PIN에서 같은 키를 유도해
HMAC 증명을 교환한다(`docs/protocol-spec.md` §3.1a).

### 3.7 태블릿 셋업 검증

```bash
adb devices                     # device 상태
adb reverse --list              # Mac 서버 실행 중이면 3줄
adb shell dumpsys package com.desklink.android | grep -A5 "runtime permissions"
adb logcat -s DeskLink          # 연결 로그
```

---

## 4. Windows / Linux 개발 머신 셋업

### 4.1 할 수 있는 것과 없는 것

| 가능 | 불가능 |
|------|--------|
| Android 클라이언트 빌드 · 설치 · 단위 테스트 | macOS 서버 빌드 (ScreenCaptureKit, VideoToolbox, CGVirtualDisplay) |
| `tools/*.py` 골든 벡터 검증 | 서버 실행, E2E 스트리밍 검증 |
| 문서 · 프로토콜 스펙 작업 | TCC 권한, 코드 서명, TLS 인증서 스크립트 |

E2E 확인에는 Mac이 반드시 필요하다.

### 4.2 JDK

**JDK 17 또는 21을 쓴다.** Gradle 래퍼는 8.14.5이고, Gradle 8.14 계열은 JDK 25를
지원하지 않는다(공식 호환성 문서 기준 데몬 실행 상한이 JDK 23~24). 시스템 기본이
25 이상이면 아래 4.3의 방법으로 반드시 재지정해야 한다.

`app/build.gradle.kts`의 `jvmTarget = "17"`은 **출력 바이트코드 목표**이고, 설치할
JDK 버전이 아니다. 중요한 것은 Gradle을 실행하는 JDK다.

Android Studio를 설치했다면 함께 오는 JBR(JetBrains Runtime) 21을 쓰는 것이 가장 간단하다.

| OS | JBR 경로 |
|----|----------|
| Windows | `C:\Program Files\Android\Android Studio\jbr` |
| Linux | `/opt/android-studio/jbr` 또는 `~/android-studio/jbr` |
| macOS | `/Applications/Android Studio.app/Contents/jbr/Contents/Home` |

### 4.3 `gradle.properties`의 macOS 절대경로 (필수 우회)

저장소의 `android/gradle.properties`에는 macOS 전용 절대경로가 커밋되어 있다.

```properties
org.gradle.java.home=/Applications/Android Studio.app/Contents/jbr/Contents/Home
```

**Windows / Linux에서는 이 줄 때문에 `./gradlew`가 즉시 실패한다.** 추적 파일을
수정하지 않고 우회하려면 사용자 홈의 Gradle 설정에 같은 키를 덮어쓴다.
`GRADLE_USER_HOME`(`~/.gradle`)의 `gradle.properties`가 프로젝트 파일보다 우선한다.

Windows — `%USERPROFILE%\.gradle\gradle.properties`:

```properties
org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
```

경로 구분자는 `/`를 쓴다. properties 파일에서 `\`는 이스케이프 문자이므로
`C:\Program Files\...`는 잘못 해석된다. 백슬래시를 쓰려면 `C:\\Program Files\\...`처럼
두 번 쓴다.

Linux — `~/.gradle/gradle.properties`:

```properties
org.gradle.java.home=/opt/android-studio/jbr
```

일회성으로는 커맨드라인 옵션도 된다.

```bash
./gradlew test -Dorg.gradle.java.home="/opt/android-studio/jbr"
```

Android Studio에서 열어 쓸 때도 이 줄이 우선하므로, Studio의 Gradle JDK 설정만
바꿔서는 해결되지 않는다.

> 근본적으로는 이 줄을 지우고 Gradle Java 툴체인으로 선언하는 것이 맞다. 빌드 설정
> 변경이 필요한 사안이라 이 문서는 우회 방법만 제시한다. 7절 참고.

### 4.4 Android SDK

Android Studio를 설치하면 SDK Manager로 받는다. 필요한 것은 **API 35**(`compileSdk = 35`)
플랫폼과 build-tools, platform-tools다.

Studio 없이 CLI만 쓸 경우 SDK 경로를 직접 지정한다. `local.properties`는 `.gitignore`에
있으므로 커밋되지 않는다.

`android/local.properties`:

```properties
sdk.dir=C:/Users/<사용자>/AppData/Local/Android/Sdk
```

또는 환경변수로 지정한다. Linux / macOS:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
```

Windows (명령 프롬프트, 새 셸부터 적용):

```
setx ANDROID_HOME "%LOCALAPPDATA%\Android\Sdk"
```

Gradle은 별도 설치하지 않는다. 저장소의 래퍼가 Gradle 8.14.5를 자동으로 내려받는다.

### 4.5 adb

`platform-tools`의 adb를 PATH에 넣는다.

```bash
adb version
adb devices
```

Windows에서 기기가 안 잡히면 제조사 USB 드라이버 설치가 추가로 필요할 수 있다
(Google USB Driver 또는 제조사 제공 드라이버). macOS / Linux는 드라이버가 필요 없다.
Linux는 udev 규칙이 없으면 기기가 `no permissions`로 나온다. `android-sdk-platform-tools-common`
패키지를 설치하거나 udev 규칙을 추가한 뒤 `sudo udevadm control --reload-rules`를 실행한다.

### 4.6 Windows / Linux 셋업 검증

```bash
cd android
./gradlew test                  # Windows: gradlew.bat test
cd ..
python tools/protocol_vectors.py
python tools/pairing_vectors.py
```

테스트 리포트: `android/app/build/reports/tests/testDebugUnitTest/index.html`.

Windows PowerShell에서는 `./gradlew`가 아니라 `.\gradlew.bat`을 쓴다.

---

## 5. 최종 검증 체크리스트

### 5.1 환경별

| 환경 | 명령 / 확인 지점 | 기대 결과 |
|------|------------------|-----------|
| 공통 | `python3 tools/protocol_vectors.py` | `ALL CHECKS PASS` |
| macOS | `swift --version` | Swift 6.x |
| macOS | `which adb` | `/opt/homebrew/bin/adb` 또는 `/usr/local/bin/adb` |
| macOS | `security find-identity -p codesigning \| grep "DeskLink Dev"` | 항목 표시 (미표시라도 빌드 성공이면 무관) |
| macOS | `./scripts/build_and_run.command` (`macos/DeskLink`에서) | 서명 검증 통과 + 메뉴 막대 아이콘 |
| macOS | Settings → Permissions | 두 항목 모두 `GRANTED` |
| macOS | `swift test` | 전체 통과 |
| macOS (Wi-Fi) | `security find-certificate -c "DeskLink TLS Server"` | 인증서 존재 |
| macOS (Wi-Fi) | 서버 로그 | `LAN listener: TLS enabled` (PLAINTEXT 경고 없음) |
| Android | `adb devices` | `<serial>  device` |
| Android | `./gradlew installDebug` | 설치 성공, 앱 실행됨 |
| Win/Linux | `./gradlew test` | 전체 통과 |

### 5.2 E2E 첫 연결 (Mac + 태블릿)

1. Mac에서 `./scripts/build_and_run.command` 실행 → 메뉴 막대 아이콘 확인.
2. Settings에서 두 권한이 `GRANTED`인지 확인. 아니면 부여 후 앱 재시작.
3. 메뉴에서 `Start Server` → 상태가 `Waiting for device`, 메타 칩이 `USB`(또는
   `USB · Wi-Fi`)로 바뀐다.
4. 태블릿을 USB로 연결. `adb devices`에 `device`로 보이는지 확인.
5. `adb reverse --list`에 3줄이 자동으로 생겼는지 확인. 없으면 3.5의 명령을 직접 실행.
6. 태블릿에서 DeskLink 앱 실행 → USB 연결.
7. Mac 메뉴 상태가 `Connected`로 바뀌고 `LIVE` 배지, Device/Link/Output/Frame 값이 채워진다.
8. 태블릿에 Mac 확장 화면이 표시된다.
9. 태블릿을 터치하면 Mac 가상 디스플레이에 마우스 입력이 들어간다.
10. 종료: 태블릿 뒤로가기 또는 Mac 메뉴에서 `Stop Server`.

막히는 단계가 있으면 6절의 증상 표를 본다.

---

## 6. 트러블슈팅

### macOS

| 증상 | 원인 | 조치 |
|------|------|------|
| `codesign failed with identity 'DeskLink Dev'` | 인증서 없음 또는 신뢰 미설정 | `./scripts/create_cert.sh` 실행. 그래도 실패하면 키체인 접근에서 "코드 서명: 항상 신뢰" |
| `build_and_run.command`가 텍스트 편집기로 열림 | 실행 비트 없음 | `chmod +x scripts/*.command` |
| 서버가 adb를 못 찾음 (`ConnectionError.refused`) | adb가 하드코딩 경로 두 곳에 없음 | 2.2의 심볼릭 링크 |
| 권한을 켰는데 계속 `NOT GRANTED` | TCC 판정이 프로세스 시작 시 캐시됨 | 앱 완전 종료 후 재실행 |
| 재빌드마다 권한이 초기화됨 | 미서명 실행 파일(`swift run`)로 실행 | 서명된 `.app`으로 실행 |
| 화면이 검게 나옴 | 화면 기록 미부여 | 시스템 설정 → 화면 기록에서 DeskLink 허용 후 재시작 |
| 터치 무반응, 에러 1301 | 손쉬운 사용 미부여 | 시스템 설정 → 손쉬운 사용에서 허용 후 재시작 |
| 포트 충돌로 리슨 실패 | 이전 인스턴스가 남아 있음 | `lsof -i :7100` 확인 후 종료. `build_and_run.command`는 자동 처리 |
| 로그에 `Serving PLAINTEXT` | TLS 인증서 없음 | `./scripts/create_tls_cert.sh` 후 서버 재시작 |
| 태블릿이 Wi-Fi로 Mac을 못 찾음 | 로컬 네트워크 권한 또는 방화벽 | 로컬 네트워크 허용, 방화벽에서 DeskLink 수신 허용. IP 직접 입력으로 우회 |
| 가상 디스플레이 해상도가 요청과 다름 | 비공개 API의 모드 폴백 | 로그의 `MISMATCH: private-API mode fallback` 확인. 다른 해상도 프리셋 시도 |

### Android

| 증상 | 원인 | 조치 |
|------|------|------|
| `adb devices`가 빔 | 충전 전용 케이블, USB 디버깅 미활성 | 데이터 케이블로 교체, 개발자 옵션 확인, `adb kill-server && adb start-server` |
| `unauthorized` | RSA 지문 승인 안 됨 | 태블릿 대화상자에서 "항상 허용" 승인. 안 뜨면 "USB 디버깅 승인 취소" 후 재연결 |
| Linux에서 `no permissions` | udev 규칙 없음 | 4.5 참고 |
| 연결 시도가 계속 실패 (USB) | reverse 터널 없음 | `adb reverse --list` 확인, 없으면 3.5 명령 실행. `adb forward`는 방향이 반대라 안 됨 |
| 백그라운드 전환 시 연결 끊김 | 포그라운드 서비스 시작 실패 | `adb logcat -s DeskLink`에서 `startForeground failed` 확인, 배터리 최적화 예외 설정 |
| 상태 표시줄 알림이 없음 | 알림 권한 미부여 (앱이 요청하지 않음) | 3.4의 수동 부여 |
| Wi-Fi 검색 결과 없음 | `NEARBY_WIFI_DEVICES` 미부여 | `Find Macs` 버튼으로 재요청, 또는 앱 정보 → 권한에서 허용 |
| PIN 입력이 계속 거부됨 | PIN 만료(60초) 또는 실패 5회 잠금 | Mac의 새 PIN 사용. 5회 실패 시 서버 Stop → Start |
| Wi-Fi 연결이 인증서 오류로 거부 | Mac에서 TLS 인증서를 재발급함 | 태블릿 앱 데이터 삭제(저장된 지문 초기화) |

### Gradle

| 증상 | 원인 | 조치 |
|------|------|------|
| Windows/Linux에서 `./gradlew`가 JDK 경로 오류로 실패 | `gradle.properties`의 macOS 절대경로 | 4.3의 `~/.gradle/gradle.properties` 우회 |
| `Unsupported class file major version` 또는 Kotlin DSL 파싱 실패 | JDK 25 이상으로 Gradle 실행 | JDK 17 또는 21로 재지정 (4.2, 4.3) |
| SDK를 찾지 못함 | `sdk.dir` / `ANDROID_HOME` 미설정 | 4.4 참고 |
| Windows에서 properties의 경로가 깨짐 | `\`가 이스케이프로 해석됨 | `/` 또는 `\\` 사용 |

---

## 7. 셋업을 막는 코드 · 설정 제약

아래 셋은 코드에 남아 있는 제약이다. 이 문서는 우회 방법을 제시하지만, 근본 해결은
코드 변경이다.

| 위치 | 내용 | 이 문서의 대응 | 근본 해결 |
|------|------|----------------|-----------|
| `android/gradle.properties` | `org.gradle.java.home`에 macOS 절대경로가 하드코딩되어 Windows/Linux에서 `./gradlew`가 즉시 실패한다 | 4.3의 `~/.gradle/gradle.properties` 우회 | 이 줄을 지우고 Gradle Java 툴체인으로 선언 |
| `macos/.../ADBManager.swift` | adb 탐색 경로가 `/opt/homebrew/bin`, `/usr/local/bin` 두 곳으로 하드코딩되어 있다 | 2.2의 심볼릭 링크 | PATH 탐색 또는 설정 가능한 경로 |
| `android/.../AndroidManifest.xml` | `POST_NOTIFICATIONS`를 선언하지만 런타임 요청 코드가 없어 Android 13 이상에서 연결 유지 알림이 표시되지 않는다 | 3.4의 수동 부여 | 앱에서 권한을 요청 |

---

## 8. 참고 문서

| 문서 | 내용 |
|------|------|
| `docs/BUILD_AND_TEST.md` | 빌드 명령, 테스트 커버리지, 수동 시나리오 |
| `docs/protocol-spec.md` | 와이어 프로토콜 정본 (프레이밍, 메시지, 에러 코드, 타이밍) |
| `docs/design_handoff_desklink_os_ui/` | UI 디자인 핸드오프 |
| `tools/protocol_vectors.py` | 언어 중립 골든 벡터 검증기 |
| `tools/pairing_vectors.py` | PIN → PSK 유도 골든 벡터 |
| `CLAUDE.md` | 엔지니어링 가드레일 |
