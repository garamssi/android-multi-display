# DeskLink 리팩터링 로드맵

자원 수명주기 감사(2026-07-08) 결과를 근거로 한 리팩터링 계획이다. 모든 항목은
프로젝트 가드레일(`CLAUDE.md`)을 따른다: 증상 우회가 아닌 근본 원인 해결, 클린
아키텍처/클린 코드, TDD(Red -> Green -> Refactor), delay 로 버그 가리기 금지.

감사 결론 요약: 무한 증가형 누수는 없었다. 대부분의 stop/deinit 경로는 정상이다
(TCPServer 커넥션 취소, 인코더 재구성 시 이전 세션 invalidate, CGVirtualDisplay
대칭 생성/파괴, 4MB 프레임 상한, VsyncRenderer removeFrameCallback, pendingFrames
30 상한, 포그라운드 서비스 start/stop 대칭). 아래는 그 예외들이다.

## 작업 원칙 (모든 항목 공통)

- 근본 원인을 한 문장으로 먼저 적는다. 못 적으면 원인을 아직 못 찾은 것이다.
- TDD: 버그를 재현하는 실패 테스트를 먼저 쓰고(Red), 가장 단순한 올바른 변경으로
  통과시키고(Green), 테스트를 안전망 삼아 정리한다(Refactor).
- 공개 API(use case, 프로토콜 코덱, view-model 상태)를 통해 행위를 검증한다.
  내부 구현이 아니라.
- 와이어 프로토콜을 건드리면 `tools/protocol_vectors.py`가 "ALL CHECKS PASS"를
  유지하고 새 케이스 벡터를 추가한다.
- 테스트 하네스: macOS는 XCTest + fake, Android는 JUnit5 + MockK + Turbine +
  `kotlinx-coroutines-test`(가상 시간). 이미 갖춰져 있으니 그대로 쓴다.

## 우선순위 개요

| 순위 | 항목 | 플랫폼 | 분류 | 근거(감사 ID) |
|---|---|---|---|---|
| P1 | 코덱 configure/start 실패 시 release | Android | 누수 | A-M1 |
| P1 | codec.release() 무조건 호출 | Android | 누수 | A-L1 |
| P1 | 종료 시 컨트롤 채널 disconnect 완료 보장 | Android | 연결/코루틴 누수 | A-M3 |
| P1 | start() 실패 시 정리(stop) | macOS | 누수 | M-H1 |
| P1 | 재연결 시 맥 상태 동기화(Waiting 고착) 검증·수정 | macOS | 상태 정확성 | 장애 G1 |
| P2 | 백그라운드 진입 시 디코더/소켓 반납 | Android | 자원 낭비 | A-M2 |
| P2 | 취소 경로마다 소켓 close | Android | 스레드 파킹 | A-M4 |
| P2 | stop() 시 인코더 세션 invalidate | macOS | 자원 낭비 | M-M1 |
| P2 | 재구성 전 스트리밍 Task await(300ms sleep 제거) | macOS | 동시성 경쟁 | M-M5 |
| P2 | encode 블로킹을 전용 큐로(또는 주석 정정) | macOS | 스레드풀 블로킹 | M-M4 |
| P2 | 세션 중 reverse 유실 재수립 타이밍 정합 | 공통 | 장애 복구 | 장애 G2 |
| P3 | adb 폴링 -> track-devices 스트림 | macOS | 낭비 | M-M2 |
| P3 | VTCompressionSession 픽셀버퍼 풀 재사용 | macOS | 낭비 | M-M3 |
| P3 | 재시작 코루틴 추적/중복 방지 | Android | 경쟁 | A-L3 |
| P3 | MediaCodec 비동기 콜백 모드 검토 | Android | 구조 개선 | 신규 |
| P3 | 맥 graceful 종료(terminate 전 stop) | macOS | 장애 정리 | 장애 G3 |
| P3 | 비디오 단독 채널 유실을 연결 상태로 전파 | Android | 장애 감지 | 장애 G4 |
| P3 | 맥/디스플레이 슬립 시 캡처·세션 처리 | macOS | 장애 복구 | 장애 G5 |
| P4 | 연결 수명주기 단일화(세션 상태머신) | 공통 | 클린 아키텍처 | 신규 |

## 장애 시나리오 커버리지 매트릭스

각 장애의 "기대 종료 상태"와 현재 처리, 담당 로드맵 항목이다. 리팩터링은 이 표의
모든 행을 회귀 테스트로 보존해야 한다. (상태: OK=현재 정상, 부분=일부 경로/타이밍
미확인, 갭=미처리)

| # | 장애 시나리오 | 기대 종료 상태 | 현재 처리 | 상태 | 담당 |
|---|---|---|---|---|---|
| S1 | 일시적 컨트롤 손실(포그라운드) | 오버레이 후 자동 재연결, 영상 복구 | reconnectLoop 5회, Gap B 비디오 재시작, 맥 forceKeyframe | OK | P1-3, P3-3 |
| S2 | 앱 최소화 후 복귀 | 연결 유지, 복귀 시 영상 즉시 복구 | FGS 유지 + surface 재생성 시 재연결 | OK | P2-1 |
| S3 | USB 케이블 분리 | 재시도 소진 후 Connect 화면 | 소켓 단절 -> 재연결 실패 -> Error -> Connect | OK | P2-2 |
| S4 | 맥 Stop Server | Connect 화면 복귀 | 소켓 close -> onConnectionLost -> 소진 -> Connect | OK | - |
| S5 | 맥 Quit(프로세스 종료) | Connect 화면 복귀 + 자원 정리 | terminate 즉시 종료(stop 미호출), 태블릿은 감지 | 부분 | G3(P3) |
| S6 | 재시도 소진 | Connect 화면 | reconnectLoop -> Error(LOST) -> 네비게이트 | OK | - |
| S7 | 연결 시 서버 미준비(waiting for device) | 준비되면 자동 연결 | PortForwardingWatcher reverse 재적용 | OK | P3-1 |
| S8 | 재연결 시 맥 팝오버 상태 | 다시 Connected 표시 | onClientConnected 로 갱신, 단 공유 accumulator/스테일 이벤트 우려 | 부분 | G1(P1) |
| S9 | 세션 중 reverse 유실(adb 재시작/USB 재열거, 기기는 유지) | 자동 재연결 | 맥 워처 1s 재적용 vs 태블릿 5s 소진 창 경쟁 | 부분 | G2(P2) |
| S10 | 태블릿 화면 잠금/꺼짐 | 잠금 해제 시 영상 복구 | 최소화와 동일 경로(surface 파기/재생성) | 부분 | P2-1 |
| S11 | 맥/디스플레이 슬립 | 깨어나면 스트림 복구 | 미검증(SCStream/가상 디스플레이 슬립 거동) | 갭 | G5(P3) |
| S12 | OS 가 백그라운드 프로세스 강제 종료(FGS에도) | 콜드 스타트 -> Connect | 재실행 시 Connection 화면, 맥은 keepAlive 타임아웃까지 Connected 표시 | 부분 | G6(문서/테스트) |
| S13 | 해상도/비트레이트 변경 후 재연결 | 새 설정으로 스트림 | bootStreaming 이 해상도 변경 시 디스플레이 재생성 | OK | P4(중앙화) |
| S14 | 빠른 연결/해제 반복(churn) | 마지막 상태로 수렴 | macOS 직렬화 완료, Android 재시작 경쟁 잔존 | 부분 | P3-3, P4 |
| S15 | 멀티터치/댕글링 터치 | UI 제스처만 처리, 맥 릴리스 | TwoFingerTapDetector + cancelTouch | OK | - |
| S16 | 링크 끊기는 순간 터치 전송(broken pipe) | 터치 유실만, 크래시 없음 | 입력 전송을 best-effort 로(IOException/닫힘 catch) | OK(수정됨) | InputRepositoryImpl |

---

## Phase 0 — 테스트 가능성 확보 (선행)

리팩터링 전에 실패 테스트를 쓸 수 있게 seam 을 마련한다. 대부분 이미 있다
(HEVCDecoder 의 `MediaCodecFactory`, ConnectionMonitor 의 `MonotonicClock`,
ADBManaging/StreamServing 프로토콜). 부족한 곳만 보강한다.

- macOS: 인코더를 프로토콜(`VideoEncoding`) 뒤에서 fake 로 교체할 수 있는지 확인.
  `StartStreamingUseCase`의 재연결/키프레임 로직을 fake `StreamServing` +
  `VideoEncoding`으로 검증할 수 있게 한다(현재 테스트 없음 -> 추가 대상).
- Android: `HEVCDecoder`의 코덱 팩토리로 configure/start 예외를 주입할 수 있는지
  확인(가능). `ConnectionRepository` teardown 을 검증할 fake 준비.

완료 기준: 아래 P1/P2 각 항목에 대해 "먼저 실패하는 테스트"를 작성할 수 있다.

---

## Phase 1 — 정확성 / 누수 (필수)

### P1-1. Android: 코덱 configure/start 실패 시 MediaCodec 반납
- 파일: `data/codec/HEVCDecoder.kt` (`configure`)
- 근본 원인: `newCodec.configure()/start()`가 던지면 `codec`에 대입도 release 도
  안 되어 하드웨어 코덱이 샌다. 재연결마다 재시도되어 코덱 고갈 가능.
- 변경: create -> configure -> start 를 try 로 감싸고, 실패 시 `newCodec.release()`
  후 재던짐.
- 최신 기법: MockK 로 팩토리가 `configure`에서 예외를 던지도록 주입.
- TDD: (Red) "configure 가 던지면 생성된 코덱이 release 된다" 테스트 추가 ->
  (Green) try/release 구현 -> (Refactor) 정리.
- 완료 기준: 실패 주입 테스트 통과, 정상 경로 회귀 없음.

### P1-2. Android: `release()` 무조건 호출
- 파일: `data/codec/HEVCDecoder.kt` (`release`)
- 근본 원인: `try { stop(); release() }` 에서 `stop()`이 던지면 `release()`를
  건너뛰어 코덱 핸들이 샌다(그리고 `codec=null`로 핸들 유실).
- 변경: `release()`를 별도 try 로 분리해 `stop()` 성공 여부와 무관하게 호출.
- TDD: (Red) "stop 이 던져도 release 가 호출된다" -> (Green) 분리 -> 통과.

### P1-3. Android: 종료 시 컨트롤 채널 disconnect 완료 보장
- 파일: `presentation/display/DisplayViewModel.kt`(`teardown`), `DisplayScreen.kt`
  (`leaveToConnect`), `data/network/ConnectionManagerImpl.kt`
- 근본 원인: `teardown()`이 `viewModelScope`에 disconnect 를 launch 만 하는데,
  곧바로 `onDisconnected()`가 화면을 pop -> `onCleared()`가 `viewModelScope`를
  취소한다. 마지막 `connectionRepository.disconnect()` 전에 취소되면, 절대
  취소되지 않는 싱글턴 `managerScope`의 재연결 루프/keepAlive 가 UI 없이 영원히
  핑을 돌 수 있다(좀비 연결).
- 변경(근본): 연결 종료를 ViewModel 수명에 묶지 않는다. 최소한
  `connectionRepository.disconnect()`를 `NonCancellable` 컨텍스트에서 완료 보장
  하거나, teardown 을 리포지토리의 자체 스코프에서 구동한다. 이중 `teardown()`
  호출에 의존하지 않는다.
- 최신 기법: `withContext(NonCancellable) { ... }` 로 취소 불가 종료 구간을 명시.
  구조적 동시성 원칙에 맞게, 연결 수명은 도메인 레벨 스코프가 소유.
- TDD: (Red) fake ConnectionRepository 로 "leaveToConnect 후 disconnect()가
  반드시 호출된다"를 `runTest` + `StandardTestDispatcher`로 검증(스코프 취소를
  시뮬레이션) -> (Green) NonCancellable 적용 -> 통과.
- 완료 기준: 화면 이탈 후 managerScope 에 살아있는 reconnect/keepAlive job 이 없음.

### P1-4. macOS: start() 실패 시 정리
- 파일: `App/ServerViewModel.swift`(`start` catch), `App/ServerCoordinator.swift`
  (`start`)
- 근본 원인: `start()`가 워처/리스너를 먼저 켠 뒤 바인드 실패로 던지면, catch 가
  상태만 `.disconnected`로 바꾸고 정리를 안 해 워처 1초 루프(매초 adb)와 바인드된
  소켓이 남는다.
- 변경(근본): `start()`를 실패 원자적으로 만든다. 어느 단계에서 던지든 이미 켠
  것(워처 + 서버)을 되돌리거나, `ServerViewModel.start()`의 catch 에서
  `await coordinator.stop()` 호출.
- 최신 기법: Swift 의 `do/catch` + `defer` 또는 명시적 롤백. 구조적 동시성에서
  부분 실패 시 정리 책임을 시작한 곳이 진다.
- TDD: (Red) fake 서버가 두 번째 `start`에서 던지도록 하고 "coordinator.stop 이
  호출되어 워처/리스너가 정리된다" 검증 -> (Green) 롤백 구현.
- 완료 기준: 시작 실패 후 남은 Task/리스너 0.

### P1-5. macOS: 재연결 시 팝오버 상태 동기화 (장애 G1, S8)
- 파일: `Domain/UseCases/ControlChannelUseCase.swift`(`runReceiveLoop`,
  `runKeepAlive`), `Data/Network/TCPServer.swift`, `App/ServerViewModel.swift`
- 근본 원인(가설, 로그로 확정 필요): 컨트롤 채널이 하나의 `receivedBytes` 스트림 +
  하나의 `FrameAccumulator` + `clientInfo`를 커넥션들 간에 공유한다. 실제 USB 분리
  후 재연결 시 (a) 이전 커넥션의 잔여 바이트가 새 핸드셰이크 프레이밍을 오염시키거나
  (b) `runKeepAlive`가 스테일 `clientConnections` 이벤트를 처리하며
  `onClientDisconnected`를 `onClientConnected` 뒤에 발생시켜 상태가
  `.connecting`("Waiting for device")에 고착될 수 있다. (최소화 경로는 FGS 로 끊기지
  않아 이미 해소; 이 항목은 "진짜 재연결"에 한정.)
- 검증 선행: S8 을 유발(USB 분리->재연결)하고 맥 `DeskLink>>` + 태블릿 로그로 실제
  콜백 순서를 확정. 그 다음 근본 수정.
- 변경(근본 후보): 새 커넥션 수락 시 프레이밍 상태(accumulator + clientInfo)를
  커넥션 경계에서 초기화하고, 스테일 커넥션 이벤트가 상태를 뒤집지 못하게
  `onClientConnected`/`onClientDisconnected`를 현재 활성 커넥션 기준으로 게이팅.
- 최신 기법: 커넥션 경계를 명시적 이벤트로 모델링(스트림에 경계 신호), 또는
  P4 세션 상태머신에서 상태를 단일 소스로 파생.
- TDD: (Red) fake `StreamServing`가 연결 -> 끊김 -> 재연결 이벤트를 내도록 하고
  "최종 상태가 .connected 로 수렴, 스테일 이벤트로 .connecting 로 되돌아가지 않음"
  검증 -> (Green).
- 완료 기준: S8 에서 팝오버가 재연결 후 반드시 Connected 로 복귀.

---

## Phase 2 — 자원 효율 / 동시성 견고화

### P2-1. Android: 백그라운드 진입 시 디코더/소켓 반납
- 파일: `presentation/display/DisplayViewModel.kt`(`onSurfaceDestroyed`),
  `data/video/VideoStreamRepositoryImpl.kt`(`connect`의 `awaitClose`)
- 근본 원인: 최소화 시 `videoStream.disconnect()`를 안 해, 파기된 Surface 에 묶인
  HW 디코더와 video/input 소켓이 백그라운드 내내 열려 있다(복귀 시 어차피 새로
  만듦). `awaitClose{}`가 비어 있어 flow 취소만으로는 정리도 안 된다.
- 변경(근본): 정리 책임을 flow 로 이동 -> `awaitClose { decoder.release(); videoClient.disconnect() }`
  로 만들어 "수집 취소 = 자원 반납"이 성립하게 한다. `onSurfaceDestroyed`는
  video/input 을 disconnect. 복귀 시 재연결은 이미 구현됨.
- 최신 기법: `callbackFlow` + `awaitClose` 의 정석 사용(콜드 플로우가 자기 자원을
  소유하고 종료 시 반납).
- TDD: (Red) "flow 수집이 취소되면 decoder.release + videoClient.disconnect 가
  호출된다"(MockK) -> (Green) awaitClose 로 이동.
- 완료 기준: 백그라운드 동안 열린 코덱/소켓 0(로그로 확인), 복귀 정상.

### P2-2. Android: 취소 경로마다 소켓 close
- 파일: `data/network/ConnectionManagerImpl.kt`(`onConnectionLost`), `TCPClient.kt`
- 근본 원인: 블로킹 `input.read()`는 코루틴 취소만으론 안 풀리고 소켓 close 가
  있어야 풀린다. 취소만 하고 disconnect 안 하는 경로는 IO 스레드가 다음 재연결까지
  파킹된다.
- 변경: 모든 취소 경로가 대응 소켓 `disconnect()`도 호출하도록 통일.
- 최신 기법: 코루틴 취소 협력성(cancellation cooperation) 원칙 — 블로킹 I/O 는
  리소스 close 로 취소를 전파.
- TDD: (Red) "onConnectionLost 후 controlClient.disconnect 가 호출된다" -> (Green).

### P2-3. macOS: stop() 시 인코더 세션 invalidate
- 파일: `Domain/Protocols/VideoEncoding.swift`, `Data/Encoding/HEVCEncoder.swift`,
  `App/ServerCoordinator.swift`(`stop`)
- 근본 원인: `stop()`이 인코더를 안 건드려, 중지 상태에서도 HW HEVC 세션이 유휴
  점유(누적은 아니나 스카스한 자원).
- 변경: `VideoEncoding`에 `func teardown() async` 추가 ->
  `VTCompressionSessionInvalidate` + `session=nil`, `ServerCoordinator.stop()`에서 호출.
- TDD: (Red) fake encoder 로 "stop() 이 teardown 을 호출한다" -> (Green).

### P2-4. macOS: 재구성 전 스트리밍 Task await (300ms sleep 제거)
- 파일: `App/ServerCoordinator.swift`(`bootStreaming`), `Data/Encoding/HEVCEncoder.swift`
- 근본 원인: 파이프라인 Task 를 await 없이 취소하고 300ms sleep 뒤 `configure`가
  세션을 invalidate 한다. 진행 중 인코드가 있으면 use-after-invalidate 위험 —
  가드레일의 "delay 로 버그 가리기" 패턴.
- 변경(근본): 취소한 스트리밍 Task 를 `await` 한 뒤 재구성/invalidate. sleep 제거
  (또는 포트 해제만을 위한 최소치로 근거를 주석에 명시).
- 최신 기법: 구조적 동시성 — 취소 후 완료 지점(`await task.value`)으로 동기화.
- TDD: (Red) 순서 보장 검증(취소된 Task 완료 전에는 configure 호출 안 됨) -> (Green).

### P2-5. macOS: encode 블로킹을 전용 큐로 (또는 주석 정정)
- 파일: `Data/Encoding/HEVCEncoder.swift`
- 근본 원인: "전용 encodeQueue 에서 블로킹" 주석과 달리 `encodeQueue`가 없어
  `semaphore.wait()`가 협력 스레드풀 스레드를 블로킹한다(주석-코드 불일치, 가드레일
  위반).
- 변경: `performEncode`를 전용 serial `DispatchQueue`에서 실행하고
  `withCheckedThrowingContinuation`으로 브리지(ADBManager 패턴과 동일). 불가하면
  주석을 실제 설계에 맞게 정정.
- TDD: 동작 회귀 테스트(인코드 출력 동일) + 블로킹이 전용 큐에서 일어남을
  구조로 보장.

### P2-6. 공통: 세션 중 reverse 유실 재수립 타이밍 정합 (장애 G2, S9)
- 파일: `data/network/ConnectionManagerImpl.kt`(reconnect 정책),
  `macos .../PortForwardingWatcher.swift`(reverse 재적용 주기)
- 근본 원인: adb 재시작/USB 재열거로 reverse 매핑이 세션 중 유실되면, 맥 워처가
  재적용하는 데 최대 ~1s + 태블릿의 재연결 창은 5회x1s=5s. 재적용이 늦거나 태블릿
  소진이 빠르면 불필요하게 Connect 화면으로 떨어진다(기기는 붙어 있는데).
- 변경: 태블릿 재연결 정책과 맥 reverse 재적용 주기를 정합(예: 태블릿 재시도 창을
  reverse 재적용 지연보다 넉넉히, 또는 P3-1 track-devices 로 재적용을 즉시화).
  "reverse 부재로 인한 실패"와 "서버 부재로 인한 실패"를 구분하면 더 정확.
- TDD: fake ADB 로 reverse 유실->복구 시퀀스를 만들어 "기기 유지 시 세션이 Connect
  로 떨어지지 않고 복구"를 검증.
- 완료 기준: S9 에서 기기 유지 중 reverse 재적용으로 자동 복구.

---

## Phase 3 — 낭비 최적화 / 구조 개선 (선택)

### P3-1. macOS: adb 폴링 -> `adb track-devices`
- 파일: `Data/Network/PortForwardingWatcher.swift`, `ADBManager.swift`
- 근본 원인: 워처가 매초 `adb devices` 자식 프로세스를 스폰(정상 상태에서도).
- 변경: 단일 장수명 `adb track-devices` 스트림을 파싱해 이벤트 기반으로 전환,
  또는 연결 후 폴링 주기를 크게 늘림.
- 최신 기법: adb 의 track-devices 스트리밍 프로토콜.
- TDD: fake ADB 이벤트 스트림으로 "device 등장/사라짐에 따라 reverse 재적용"을 검증.

### P3-2. macOS: VTCompressionSession 픽셀버퍼 풀 재사용
- 파일: `Data/Encoding/HEVCEncoder.swift`(`performEncode`)
- 근본 원인: 프레임마다 `CVPixelBufferCreate`(60/s) — 세션 풀을 두고도 재사용 안 함.
- 변경: `VTCompressionSessionGetPixelBufferPool` -> `CVPixelBufferPoolCreatePixelBuffer`
  로 IOSurface 버퍼 재활용.
- TDD: 성능은 단위 테스트로 어려우니, 인코드 결과 동일성 회귀 + 코드 리뷰로.

### P3-3. Android: 재시작 코루틴 추적/중복 방지
- 파일: `presentation/display/DisplayViewModel.kt`(`restartVideoPipeline`)
- 근본 원인: 추적 안 되는 `launch`라 재연결 두 번 겹치면 disconnect/connect 가
  인터리브될 수 있음.
- 변경: 재시작 job 을 필드로 추적, 이전 것을 `cancelAndJoin` 후 새로 시작(직렬화).
- TDD: (Red) 연속 재연결 2회에 소켓 연산이 겹치지 않음 -> (Green).

### P3-4. Android: MediaCodec 비동기 콜백 모드 검토
- 파일: `data/codec/HEVCDecoder.kt`, `data/codec/VsyncRenderer.kt`
- 배경: 현재는 vsync 마다 동기 drain(폴링) 모델이다. `MediaCodec.setCallback`
  (API 21+) 비동기 콜백 모델이 표준 최신 기법으로, 입출력 버퍼를 이벤트로 처리해
  vsync 폴링/스핀을 줄이고 지연을 낮춘다.
- 주의: 렌더 타이밍(Choreographer 동기)과의 결합을 재설계해야 하므로 범위가 크다.
  별도 스파이크 후 결정. 성급히 바꾸지 않는다.
- TDD: 콜백 기반 디코더의 상태 전이(입력 큐잉/출력 렌더)를 mock MediaCodec 으로 검증.

### P3-5. 공통: 연결 수명주기 단일화(세션 상태머신)
- 배경: 현재 재연결/재시작 트리거가 여러 곳(Android: onSurface*, observeReconnects,
  reconnectLoop / macOS: bootStreaming, StartStreamingUseCase 재시작)에 흩어져 있다.
  동작은 하지만 상호작용을 추론하기 어렵다(이번 디버깅이 그 방증).
- 변경(클린 아키텍처): "미러 세션"을 명시적 상태머신으로 도메인에 모델링
  (Idle -> Connecting -> Streaming -> Reconnecting -> Terminated). UI/서비스/파이프
  라인은 이 단일 소스에서 파생. 트리거를 한 곳으로 모은다.
- 최신 기법: 단방향 상태 흐름(뷰모델이 상태 노출, 뷰는 렌더), 도메인 상태머신.
- TDD: 상태머신을 순수 로직으로 분리해 전이 표를 테스트(플랫폼 무관 단위 테스트).
- 주의: 큰 리팩터링. P1/P2 로 안정화된 뒤 착수. S8/S9/S14 의 근본 해법이기도 함.
- 포함 잔여: P1-3 이 명시적 UI 종료 경로의 disconnect 완료는 보장하나, 사전 teardown
  없이 VM 이 clear 되는 경로(OS 가 백그라운드에서 Activity 파괴 등)는 커버 못 함.
  근본 해법은 연결 수명주기를 ViewModel 이 아니라 세션 상태머신(도메인/앱 수명 스코프)
  이 소유하는 것. 여기서 함께 해결한다.

### P3-6. macOS: graceful 종료 (장애 G3, S5)
- 파일: `Presentation/StatusMenuView.swift`(Quit -> `NSApplication.shared.terminate`),
  `App/DeskLinkApp.swift`
- 근본 원인: Quit 이 `terminate(nil)`로 즉시 끝나 `coordinator.stop()`(reverse 제거,
  디스플레이 파괴, 서버 정리)가 실행되지 않는다. 프로세스 종료로 대부분 회수되나,
  기기측 adb reverse 매핑이 남고 정리 순서가 보장되지 않는다.
- 변경: 종료 전 `coordinator.stop()`을 await 한 뒤 terminate(예: `NSApplicationDelegate.applicationWillTerminate`
  또는 Quit 액션에서 stop 완료 후 terminate).
- TDD: 종료 훅이 stop 을 호출함을 단위 검증(기기측은 수동 확인: 재시작 시 reverse
  중복/잔여 없음).

### P3-7. Android: 비디오 단독 채널 유실을 연결 상태로 전파 (장애 G4, S3 보강)
- 파일: `data/video/VideoStreamRepositoryImpl.kt`, `ConnectionManagerImpl.kt`
- 근본 원인: 비디오 소켓만 끊기는 경우(현재는 USB 단일 링크라 대개 컨트롤과 동반
  단절)를 연결 상태로 전파하지 않아, 컨트롤은 살아있는데 영상만 죽는 상황을 감지
  못할 수 있다.
- 변경: 비디오 스트림의 `StreamStopped`/`Error` 를 도메인 상태로 승격(또는 P4
  상태머신 입력)해 재시작/에러 전이를 트리거.
- TDD: fake 비디오 스트림이 Error 를 낼 때 상태 전이/재시작이 일어남을 검증.

### P3-8. macOS: 슬립/디스플레이 슬립 처리 (장애 G5, S11)
- 파일: `Data/Capture/SCKScreenCapturer.swift`, `App/ServerCoordinator.swift`
- 근본 원인: 맥 절전/디스플레이 슬립 시 SCStream·가상 디스플레이 거동이 미검증.
  깨어난 뒤 캡처가 멈춘 채일 수 있다.
- 변경: 슬립/웨이크 노티피케이션(`NSWorkspace.willSleepNotification` 등)에 캡처
  일시정지/재개를 연결하거나, 웨이크 시 파이프라인 재부팅. 먼저 스파이크로 거동 확인.
- TDD: 캡처 재개 로직을 프로토콜 뒤에서 fake 로 검증(노티는 수동/통합).

### P3-9. Android: OS 강제 종료 후 콜드 스타트 (장애 G6, S12) — 문서/테스트
- 근본 원인: FGS 에도 극한 상황에서 OS 가 프로세스를 종료하면 재실행은 콜드
  스타트(Connection 화면). 맥은 keepAlive 타임아웃 전까지 Connected 로 표시.
- 변경: 코드 변경보다 "정상 동작"임을 문서화 + 테스트 시나리오에 포함. 필요 시
  맥의 타임아웃 후 Waiting 전이가 매끄러운지 확인(P1-5 와 연계).

1. 근본 원인을 커밋 메시지 첫 줄에 한 문장으로.
2. 실패 -> 통과 순서로 테스트 커밋(Red 커밋을 남기거나 PR 설명에 근거).
3. macOS `swift test`, Android `./gradlew testDebugUnitTest` 통과.
4. 와이어 변경 시 `python3 tools/protocol_vectors.py` "ALL CHECKS PASS" + 새 벡터.
5. 기기 회귀: 장애 매트릭스 S1~S15 를 회귀 목록으로 사용한다. 최소한 S1(재연결),
   S2(짧은·긴 최소화 복귀), S3(USB 분리), S4/S5(맥 Stop/Quit), S8(재연결 후 맥 상태),
   S9(세션 중 reverse 유실)를 매 릴리스에서 확인하고, 각 시나리오의 "기대 종료 상태"
   에 실제로 수렴하는지 본다.
6. 신규 경고 없음, 아키텍처 계층/명명 규칙 준수.

## 권장 착수 순서

Phase 0 -> P1(누수/정확성) -> P2(효율/동시성) -> P3(선택). P1 은 사용자 영향이
큰 실제 버그이므로 먼저. P3-4/P3-5 는 범위가 크니 스파이크 후 별도 결정.
