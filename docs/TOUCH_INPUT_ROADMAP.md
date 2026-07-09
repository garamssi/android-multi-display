# 터치 입력 로드맵 (태블릿 -> 맥 입력)

태블릿의 터치를 맥의 입력으로 완전하게 적용하기 위한 계획. 가드레일(`CLAUDE.md`)을
따른다: 근본 원인, 클린 아키텍처, TDD(Red->Green->Refactor), 매직 상수/바이트 태그는
상수/enum, 와이어 변경 시 `tools/protocol_vectors.py` 동기화.

작성 시점 최신 OS 기준: macOS 26 "Tahoe"(15 Sequoia 계열의 프라이버시 정책 유지),
Android 16. 권한/주입 사실은 아래 "최신 OS 검토"에서 출처와 함께 정리.

## 현재 상태

- Android(클라이언트): `data/input/TouchCollector.kt`가 `MotionEvent`를 정규화된
  `TouchEvent`(action DOWN/UP/MOVE/CANCEL, x/y 0..1, pressure, pointerId, ts)로 변환,
  `InputRepositoryImpl`이 TOUCH_EVENT(0x20)/TOUCH_BATCH(0x21)로 전송. best-effort.
- macOS(서버): `data/input/CGEventInjector.swift`가 pointerId별로 down/up/move/cancel을
  `CGEvent` 마우스 이벤트(mouseDown/Up/Move)로 주입. 가상 디스플레이 좌표계 기준.
- 즉 "한 손가락 탭/드래그 -> 마우스 클릭/드래그"는 동작.
- 스크롤(두 손가락 드래그 -> SCROLL 0x22)과 우클릭(한 손가락 롱프레스 ->
  POINTER_BUTTON 0x23)은 구현 완료(P2). 스크롤 속도와 방향(Natural/Reversed)은
  태블릿 설정에서 조절(둘 다 전송 전 델타에 적용, 서버/프로토콜 불변).
- 제스처/관성/정밀 좌표 매핑은 미구현(P3 이후).
- 키보드 입력은 이 로드맵의 범위 밖(제외).

## 핵심 제약 (macOS 입력 주입)

- macOS에는 공개된 "멀티터치 주입" API가 없다. 트랙패드 제스처를 그대로 재생할 수 없다.
  따라서 태블릿 제스처를 마우스/스크롤/수정자-클릭 이벤트로 "매핑"한다.
- 합성 이벤트는 Mojave 이후 Accessibility 권한이 없으면 OS가 무시한다. 현재 프로젝트는
  서명된 `.app` + Accessibility(TCC) 유지가 이미 갖춰져 있어 추가 권한은 없다.
- 사용 API: `CGEventCreateMouseEvent`/`CGEventCreateScrollWheelEvent` + `CGEventPost`.
  좌표는 글로벌 디스플레이 좌표.

## 목표 입력 매핑 (제안)

| 태블릿 제스처 | 맥 동작 | 주입 |
|---|---|---|
| 한 손가락 탭 | 좌클릭 | mouseDown+Up |
| 한 손가락 드래그 | 드래그 | mouseDown -> mouseDragged -> mouseUp |
| 한 손가락 롱프레스 | 우클릭(또는 보조 클릭) | rightMouseDown+Up |
| 두 손가락 드래그 | 스크롤 | scrollWheel(정밀 스크롤) |
| 두 손가락 탭 | (현재 오버레이 컨트롤로 예약됨 — 충돌 주의) | - |
| 핀치 | 확대/축소(제한적: Cmd+scroll 또는 magnify 불가) | scroll + 수정자 |

주의: 두 손가락 탭은 이미 "컨트롤 표시" 제스처로 쓰고 있으므로, 우클릭은 롱프레스로
매핑하는 것을 기본으로 한다(제스처 충돌 방지). 최종 매핑은 사용자 확인 후 확정.

## 권한 표

| 플랫폼 | 필요 권한 | 현재 상태 |
|---|---|---|
| macOS | Accessibility(TCC) — 합성 입력 주입 | 이미 요구/유지(서명 .app) |
| Android | 터치 캡처 자체는 권한 불필요 | - |

## 프로토콜 추가 (와이어)

새 입력 종류는 명시적 `MessageType` 바이트 태그로 추가하고 golden vector를 넣는다.
프레이밍은 기존 `[len uint32 BE][type u8][payload]` 유지.

- 확정 태그(입력 대역 0x20~): SCROLL 0x22, POINTER_BUTTON(좌/우) 0x23 (구현 완료).
  GESTURE 0x24(확장용, 미구현). 값은 `docs/protocol-spec.md`가 정본.
- payload는 고정 크기 리틀/빅엔디안을 기존 TouchEvent 직렬화 규칙과 일치시킨다.
- 변경 시 `tools/protocol_vectors.py`에 각 신규 타입 벡터 추가, "ALL CHECKS PASS" 유지.

## Phase 계획

### P1 — 좌표 매핑/기본 클릭 정확도 (기반)
- 근본 원인 관점: 정규화 좌표(0..1)를 가상 디스플레이 픽셀로 매핑하는 규칙을 단일
  소스로 명확히(현재도 있으나, 멀티 디스플레이/스케일/레티나에서의 정확도 점검).
- 변경: 좌표 매핑을 순수 함수로 분리(Android 정규화 <-> Mac 픽셀), 경계/반올림 정의.
- TDD: 매핑 함수 단위 테스트(모서리/중앙/경계값). 프로토콜 직렬화 왕복 테스트.

### P2 — 스크롤 + 우클릭 (완료)
- Android: 두 손가락 드래그 -> SCROLL(dx,dy) 정규화 델타. 한 손가락 롱프레스 ->
  POINTER_BUTTON(RIGHT, DOWN/UP). 순수 인식기 `TwoFingerTapDetector`(스크롤),
  `LongPressDetector`(롱프레스)로 분리(타이밍은 화면의 코루틴이 담당).
- macOS: SCROLL은 `CGEvent(scrollWheelEvent2Source:units:.pixel)` + 서브픽셀 잔차
  누적, POINTER_BUTTON은 `rightMouseDown/Up`(및 left) 매핑.
- 크로스체크: 스크롤은 정규화 델타로 전송, 감도 배율은 태블릿에서 적용(Mac 1:1).
  방향은 서버가 natural 부호로 적용. 좌표는 TOUCH_EVENT와 동일 정규화.
- TDD: 인식기 단위 테스트(스크롤 델타, 롱프레스 발화/실격), 프로토콜 golden vector
  (SCROLL, POINTER_BUTTON) Swift/Kotlin/Python 3자 일치.
- 관성 스크롤은 사용자 요청으로 P3(나중 개발)로 이연.

### P3 — 제스처/관성/정밀도
- 관성 스크롤(완료): 태블릿이 두 손가락 스크롤의 속도를 EMA로 추정(`VelocityTracker2D`),
  손을 뗄 때 지수 감쇠 모델(`FlingDecay`)로 프레임마다 감쇠하는 SCROLL 델타를 계속
  전송한다. 와이어 변경 없음(SCROLL 0x22 재사용) — 맥은 그대로 1:1 주입하고 서브픽셀
  잔차로 매끄럽게 이어진다. 새 터치가 들어오면 글라이드를 멈춘다(탭으로 잡기). 뗌 직전
  일정 시간(FLING_MAX_RELEASE_GAP_MS) 이상 정지했으면 플링하지 않는다.
  - 대안 검토: macOS 네이티브 모멘텀 페이즈(CGEvent momentum) 주입은 앱별로 동작이
    달라 취약하므로, 태블릿에서 감쇠 델타를 생성하는 방식을 채택(모든 앱에서 동작).
- 남은 항목(미구현): 드래그 임계값, 탭-이동 구분, 커서 가시성, 좌표 스무딩.
- macOS 한계 명시: 진짜 핀치/회전 제스처 주입 불가 -> 대체 매핑(Cmd+scroll 등) 또는
  범위 밖으로 문서화. 성급히 구현하지 않는다(스파이크 후 결정).

### P4 — 다중 포인터 정책 정리
- macOS는 멀티터치 주입 불가 -> 현재 pointerId별 마우스 매핑의 의미를 재정의(주 포인터
  1개만 커서로, 나머지는 제스처 인식에만 사용). 클린 코드로 단일 정책화.

## 크로스체크 원칙 (Android 캡처 <-> Mac 주입)

- 모든 신규 입력은 "Android가 무엇을 어떤 단위로 보내는가"와 "Mac이 무엇으로 주입하는가"를
  한 쌍으로 정의하고 protocol-spec에 기록. 한쪽만 바꾸지 않는다.
- 좌표/델타 단위, 방향(자연 스크롤), 수정자 상태는 프로토콜에 명시.
- 각 Phase 종료 시 독립 리뷰(서브에이전트)로 캡처<->주입 대칭·프로토콜 벡터 검증.

## 검증
- 단위: 좌표 매핑/제스처 인식/프로토콜 직렬화.
- 프로토콜: `python3 tools/protocol_vectors.py` "ALL CHECKS PASS" + 신규 벡터.
- 기기: 탭/드래그/스크롤/우클릭 실제 동작 + Accessibility 권한 유지 확인.

## 최신 OS 검토 (출처)

- macOS: 합성 마우스/스크롤 주입은 여전히 `CGEvent`(CGEventCreateMouseEvent/
  ScrollWheelEvent + CGEventPost)로 하며, Mojave 이후 Accessibility 권한이 없으면 OS가
  합성 이벤트를 무시한다. 공개 멀티터치 주입 API는 없다. 따라서 본 로드맵은 제스처를
  마우스/스크롤로 매핑한다.
- Android: 앱 내 터치 캡처(MotionEvent)는 권한이 필요 없다.

Sources:
- [CGEvent — Apple Developer](https://developer.apple.com/documentation/coregraphics/cgevent)
- [macOS Input Monitoring, Screen Capture & Accessibility — HackTricks](https://hacktricks.wiki/en/macos-hardening/macos-security-and-privilege-escalation/macos-security-protections/macos-input-monitoring-screen-capture-accessibility.html)
- [Requesting macOS Privacy and Security Permissions — Gannon Lawlor](https://gannonlawlor.com/posts/macos_privacy_permissions/)
