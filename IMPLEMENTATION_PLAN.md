# DeskLink 구현 · 테스트 · 시나리오 마스터 플랜

작성일: 2026-07-06 · 기준: `CODE_REVIEW.md`의 발견사항 · 목표: P1~P3 수정 + 스캐폴딩 종단 완성

---

## 0. 목표(Goal)와 완료 정의(Definition of Done)

**최종 골**: Mac(가상 디스플레이 캡처 → HEVC 인코딩 → 전송)과 Android(수신 → 디코딩 → 확장 디스플레이 렌더 → 터치 역전송)가 스펙(`docs/protocol-spec.md`)을 정확히 준수하며 종단 간(E2E) 동작하는 상태.

**완료 정의(각 항목이 모두 참일 때 "골 도달"):**
1. `CODE_REVIEW.md`의 P1 실제 버그 5종이 모두 수정되고 회귀 테스트가 존재한다.
2. 와이어 프로토콜(프레이밍/터치/비디오 프레임·설정/PING)이 양 플랫폼에서 **동일 골든 벡터**(아래 §2)를 산출한다.
3. 수신 루프·비디오 파이프라인·입력 파이프라인·PING keepalive·재연결이 양쪽에 배선된다.
4. Hilt DI 그래프가 완성되어 Android 앱이 조립 가능한 상태가 된다.
5. 각 모듈에 단위 테스트가, 핵심 흐름에 통합/시나리오 테스트 명세가 존재한다.
6. 폐기 API(`CGDisplayStream`)가 ScreenCaptureKit으로 이관된다.

> **검증 제약(반드시 인지)**: 현재 리뷰 환경(Linux)에는 macOS SDK/Android SDK/Swift/Gradle이 없어 **컴파일·기기 실행 검증 불가**. 대신 (a) 언어 중립 골든 벡터를 Python으로 실제 실행 검증(`outputs/protocol_vectors.py`, 전 항목 PASS), (b) 양 플랫폼 단위 테스트가 이 벡터를 하드코딩해 대조하도록 설계한다. 실제 빌드/기기 검증은 개발자 로컬(Xcode / Android Studio)에서 수행하는 것을 전제로 한다.

---

## 1. 확정된 프로토콜 결정(구현 전 잠금)

리뷰에서 열려 있던 두 가지 모호점을 다음과 같이 확정한다.

**(A) VIDEO_FRAME 페이로드 헤더** — 스펙 §4.3 그대로:
```
[Timestamp int64 us BE (8)][Flags u8 (1)][FrameNumber uint32 BE (4)][NAL...]
Flags: bit0 = IS_KEYFRAME(IDR), bit1 = IS_CONFIG, bit2~7 = 0
```

**(B) VIDEO_CONFIG 페이로드** — 스펙 §4.2 그대로:
```
[CodecID u8 (1)][ConfigLength uint16 BE (2)][ConfigData...]
CodecID: 0x01=HEVC, 0x02=H.264
```

**(C) NAL 포맷 = Annex-B 종단 통일** (핵심 결정):
- Mac: VideoToolbox 출력은 AVCC(길이 접두)이므로 **Annex-B(`00 00 00 01`)로 변환**해서 전송. CSD(VPS+SPS+PPS)도 Annex-B로 연접해 `VIDEO_CONFIG`로 전송.
- Android: `MediaFormat`의 `csd-0`에 Annex-B 연접 CSD를 그대로 주입, 프레임도 Annex-B로 디코더 입력.
- 이유: MediaCodec의 가장 견고한 경로이며 scrcpy 등 검증된 방식과 동일.

**(D) 좌표계**: 터치 X/Y는 0.0~1.0 정규화(가상 디스플레이 대비). Android 송신 시 `x/surfaceWidth`, Mac 수신 시 `x*displayWidth`.

---

## 2. 골든 벡터 (권위 있는 기준값 — 양 플랫폼 테스트가 대조)

Python 검증 완료(`outputs/protocol_vectors.py`, ALL CHECKS PASS). 표본 입력과 기대 바이트(hex):

| 메시지 | 표본 입력 | 기대 hex |
|--------|-----------|----------|
| TOUCH_EVENT (20B) | MOVE(0x02), x=0.5, y=0.25, pressure=32768, pointerId=1, ts=1234567890123456us | `023F0000003E800000800001000462D53C8ABAC0` |
| FRAMED_TOUCH (type 0x20) | 위 터치를 프레이밍 (len=21) | `0000001520` + 위 20B |
| TOUCH_BATCH (count=2) | count=2 + 위 터치 ×2 | `0002` + 위20B + 위20B |
| VIDEO_FRAME 헤더+NAL | ts=1000000us, flags=0x01, frameNo=42, nal=`000000012600` | `00000000000F4240` `01` `0000002A` `000000012600` |
| VIDEO_CONFIG | codec=0x01, cfg=`0000000140` | `01` `0005` `0000000140` |
| PING (i64 ms) | ts=1700000000000 | `0000018BCFE56800` |

주요 상수 확인: `0.5f=3F000000`, `0.25f=3E800000`, `32768=8000`, `1234567890123456=000462D53C8ABAC0`, MAX_PACKET=`4194304=0x00400000`.

---

## 3. 수정 플랜 (단계별)

### Phase 1 — P1 실제 버그 (최우선, 회귀 테스트 필수)

| ID | 파일 | 수정 내용 | 테스트 |
|----|------|-----------|--------|
| S-C1 | `TouchDeserializer.swift`, `PacketFramer.swift` | `load(fromByteOffset:as:)` → `loadUnaligned(...)` 전면 교체 | 홀수 오프셋 파싱 정확성 + 골든 벡터 역직렬화 |
| S-C2 | `EncodedFrame.swift`(신규 `serialize()`), `StartStreamingUseCase.swift` | 13바이트 헤더(ts/flags/frameNo) + NAL 직렬화 후 전송 | `VIDEO_FRAME` 골든 벡터 일치 |
| S-C3 | `HEVCEncoder.swift`, `StartStreamingUseCase.swift`, 신규 `AnnexBConverter.swift` | 첫 프레임 전 `VIDEO_CONFIG` 전송 + AVCC→Annex-B 변환 | `VIDEO_CONFIG` 골든 벡터 + 변환 유닛 테스트 |
| A-C1 | `TCPClient.kt` | 매 read 전체 복사 제거 → 파스 오프셋 유지, 프레임 완성 시에만 compact | 청크 분할 수신 시 O(n) 확인 + 다중 프레임 재조립 |
| A-C2 | `PacketFramer.kt` | 크기 계산 `Long`화(`4L + packetLength`), `.toInt()` 전 상한 재확인 | 경계값(4MB, 4MB+1, 0, 음수 래핑) 테스트 |
| A-C3 | `HEVCDecoder.kt`, `VsyncRenderer.kt` | 입력 프레임 큐잉/재시도(유실 금지), vsync당 준비 출력 전량 배출, `INFO_OUTPUT_BUFFERS_CHANGED` 처리, KDoc 정정 | 상태 머신 유닛 테스트(모의 codec) |
| A-M1 | `HEVCDecoder.kt` | 디코더 입력 `BUFFER_FLAG_KEY_FRAME` 제거(일반 프레임 flags=0), CSD는 `BUFFER_FLAG_CODEC_CONFIG` | — |

### Phase 2 — 폐기 API · 견고성

| ID | 파일 | 수정 내용 |
|----|------|-----------|
| S-H2 | 신규 `SCKScreenCapturer.swift`, `ScreenCapturing.swift` | ScreenCaptureKit(`SCStream`+`SCContentFilter`)로 캡처 이관, 화면 녹화 권한 확인/요청, 기존 `CGDisplayStream` 경로는 폴백 또는 제거 |
| S-H1 | `TCPServer.swift`, `ADBManager.swift` | `AsyncStream`+continuation을 `init`에서 1회 생성·저장, `onTermination` 설정 |
| S-H3 | `ADBManager.swift` | 파이프를 EOF까지 먼저 배출 후 `waitUntilExit`, 블로킹은 continuation+전용 큐로 오프로드 |
| S-H4 | `HEVCEncoder.swift` | `actor`화 또는 세마포어 대기를 락 밖으로 |
| A-H1 | `ConnectionManagerImpl.kt` | `withTimeout(HANDSHAKE_TIMEOUT)`로 핸드셰이크 감쌈 |
| A-H2 | 신규 `KeepAliveController` (양쪽) | PING 1s 송신, 수신 PING→PONG 응답, 마지막 PONG 시각 추적, `now-last>PING_TIMEOUT`이면 끊김 처리→재연결 |
| A-H3 | `TCPClient.kt` | 프레이밍 오류를 타입드 이벤트/정상 종료로, `RuntimeException` 제거, 소켓 정리 |
| S-M/A-L | `HandshakeHandler.swift`, `HandshakeClient.kt` | 코덱 지원 검증, 0 해상도 거부, `protocolVersion` 검증, 에러 코드 스펙 매핑 |

### Phase 3 — 스캐폴딩 종단 완성

| ID | 파일 | 수정 내용 |
|----|------|-----------|
| A-C4 | `AppModule.kt` | `@Binds`로 `ConnectionManagerImpl→ConnectionRepository`, `InputRepositoryImpl→InputRepository`, `VideoStreamRepositoryImpl→VideoStreamRepository` 바인딩 |
| A-H5 | 신규 `VideoStreamRepositoryImpl.kt` | 비디오 소켓 연결, `VIDEO_CONFIG` 파싱→`HEVCDecoder.configure`, `VIDEO_FRAME` 파싱→`submitFrame` |
| A-H4 | 신규 `InputRepositoryImpl.kt`, `DisplayScreen.kt` | 입력 소켓 연결, SurfaceView 터치 리스너→`TouchCollector`→`SendTouchUseCase`, 서피스 수명↔디코더 연동, `BackHandler`로 정상 종료 |
| S-L10 | `StartStreamingUseCase.swift`, 신규 `ReceiveInputUseCase.swift` | 컨트롤/입력 포트 서버 기동, 수신 루프→`TouchDeserializer`→`CGEventInjector`, PING/PONG 처리 |
| S-M6/M7 | `CGEventInjector.swift` | `AXIsProcessTrusted()` 확인→`inputPermissionDenied`, 포인터별 상태 추적 |
| A-L4 | `SettingsViewModel.kt`, `DeskLinkNavHost.kt` | 설정→`connect(config)` 전달, 기본 해상도 일치 |

### Phase 4 — 정리
의존성 버전 상향(AGP/Kotlin/Compose), 레거시 XML 테마→Material3(A-M6), Low 항목 정리, 문서화.

---

## 4. 테스트 코드 전략

### 4.1 원칙
- **테스트 피라미드**: 순수 로직 단위 테스트(다수) → 컴포넌트/모의(mock) 테스트(중간) → E2E 시나리오(소수, 수동/계측).
- **골든 벡터 고정**: 직렬화 계열 테스트는 §2의 hex를 상수로 박아 **양 플랫폼이 동일 바이트**를 산출하는지 검증(크로스플랫폼 회귀 방지).
- **경계값·오류 경로 우선**: 정상 경로보다 부분 수신/오버플로/권한 없음/타임아웃 같은 실패 경로를 반드시 덮는다.

### 4.2 macOS (XCTest — 기존 `Tests/` 확장)
- `PacketFramerTests`: 프레이밍 골든 벡터, 부분 버퍼→`needMoreData`, 4MB 초과→error, `loadUnaligned` 정확성.
- `TouchDeserializerTests`: 20B 골든 벡터 역직렬화, 홀수 오프셋(정렬 위반 회귀), 범위 밖 좌표 거부, 배치.
- `VideoFrameSerializerTests`(신규): `EncodedFrame.serialize()`가 `VIDEO_FRAME` 골든 벡터와 일치, keyframe 플래그.
- `AnnexBConverterTests`(신규): AVCC(4바이트 길이 접두) → Annex-B(`00000001`) 변환, 다중 NALU.
- `HEVCEncoderTests`: 설정 반영, 비트레이트 갱신(모의). 실제 VT는 통합 대상.
- `HandshakeHandlerTests`: 코덱 미지원 거부, 0 해상도 거부, 버전 검증, 에러 코드 매핑.
- `KeepAliveTests`: PONG 타임아웃 판정 로직(가상 시계 주입).

### 4.3 Android (JUnit5 + MockK + Turbine — 기존 `test/` 확장)
- `PacketFramerTest`: 프레이밍 골든 벡터, 부분/다중 프레임, `Long` 크기 계산 경계값(4MB/4MB+1/음수 래핑 방지).
- `TouchSerializerTest`: 20B 골든 벡터 직렬화/역직렬화 라운드트립, 배치 count 상한(100).
- `TCPReframingTest`(신규): 청크 경계 분할 입력을 mock InputStream으로 흘려 다중 프레임이 정확히 재조립되고 **전체 복사 없이**(호출 카운트로 간접 검증) 처리되는지.
- `VideoFrameParserTest`(신규): `VIDEO_FRAME`/`VIDEO_CONFIG` 골든 벡터 파싱, keyframe 플래그, CSD 추출.
- `HevcDecoderStateTest`(신규): 입력 버퍼 없음 시 프레임 **유실 금지**(재시도 큐), 출력 다중 배출을 모의 codec으로 검증.
- `ConnectionManagerImplTest`: 핸드셰이크 타임아웃(가상 디스패처), 상태 전이(Turbine), 재연결 백오프 지수 증가.
- `KeepAliveControllerTest`: PING 주기 송신, PONG 미수신 시 끊김 전이(가상 시계).
- `SettingsViewModelTest`: `toDisplayConfig()`가 `connect`에 전달되는 경로.

### 4.4 테스트 하네스 산출물
- `outputs/protocol_vectors.py`: 언어 중립 기준값 생성기(실행 검증 완료). CI에서 실행해 벡터가 표류하지 않는지 감시하는 용도로 재사용 가능.

---

## 5. 시나리오 (통합 · E2E · 수동 검증)

각 시나리오는 전제(Given)-행동(When)-기대(Then)로 기술. 기기 검증 단계에서 사용.

**SC-1 USB(ADB) 정상 연결·스트리밍**
- Given: Mac 서버 실행, USB 연결, `adb forward` 3포트 설정.
- When: Android 앱에서 `localhost:7100` 연결 → 핸드셰이크 → CONFIG 협상 → START_STREAM.
- Then: 5초 내 확장 화면 표시, E2E 지연 ≤30ms, 60fps 유지, Mac CPU ≤10%.

**SC-2 Wi-Fi 연결·적응형 비트레이트**
- Given: 동일 네트워크, 서버 IP 지정 연결.
- When: 대역폭 저하 유발 → 서버가 `BITRATE_UPDATE` 송신.
- Then: 스트림 중단 없이 비트레이트 하향, 화면 유지.

**SC-3 터치 역전송 정확성**
- Given: 스트리밍 중.
- When: 태블릿 특정 좌표 탭/드래그/멀티터치.
- Then: Mac 가상 디스플레이의 정규화 대응 좌표에 클릭/드래그 주입(정규화 오차 ≤1px), 멀티터치 포인터별 상태 독립.

**SC-4 핸드셰이크 타임아웃**
- Given: 서버가 TCP accept 후 응답 없음(모의).
- When: 클라이언트 connect.
- Then: 5초 후 `TIMEOUT` 에러 상태, 소켓 정리, 무한 대기 없음.

**SC-5 연결 끊김 감지·재연결**
- Given: 스트리밍 중 Wi-Fi 단절.
- When: PONG 3초간 미수신.
- Then: 끊김 판정→지수 백오프(1→2→4…최대 30s, 최대 10회) 재연결, 복구 시 스트림 재개.

**SC-6 해상도 변경(스트림 중)**
- Given: 스트리밍 중.
- When: 설정에서 해상도 변경.
- Then: STOP_STREAM→CONFIG→START_STREAM 시퀀스로 재시작, 새 해상도 적용.

**SC-7 대형 프레임/부분 수신 견고성**
- Given: 4MB 근접 키프레임이 작은 청크로 분할 도착.
- When: 수신 루프 재조립.
- Then: 프레임 무손실 재조립, O(n) 성능, 4MB 초과는 안전 거부.

**SC-8 권한 없음 처리**
- Given: macOS 접근성/화면 녹화 권한 미부여.
- When: 캡처/입력 주입 시도.
- Then: 조용한 무동작이 아니라 명시적 에러(1301/1201)와 사용자 안내.

**SC-9 정상 종료**
- Given: 스트리밍 중.
- When: 태블릿 뒤로가기 또는 Mac 메뉴 종료.
- Then: DISCONNECT 교환, 가상 디스플레이 파괴, 소켓·디코더·인코더 자원 해제(누수 없음).

---

## 6. 실행 순서 · 브랜치 전략
1. 작업 브랜치 `dev`에서 진행(현재 브랜치). Phase별 커밋 분리.
2. Phase 1(버그+회귀 테스트) → Phase 2(폐기/견고성) → Phase 3(스캐폴딩) → Phase 4(정리) 순.
3. 각 Phase 종료 시 해당 단위 테스트가 로컬(Xcode/Android Studio)에서 green인지 개발자 확인.
4. 기기 시나리오(§5)는 P1~P3 완료 후 실기 검증.

## 7. 위험 · 미검증 항목
- 본 세션 산출 코드는 **정적 검토 수준**(컴파일 미검증). VideoToolbox/ScreenCaptureKit/MediaCodec의 실제 동작은 기기 검증 필요.
- `CGVirtualDisplay`는 비공개 API로 macOS 버전에 따라 동작이 바뀔 수 있음(대체재 부재가 근본 리스크).
- ScreenCaptureKit 이관은 캡처 파이프라인 전면 교체라 회귀 위험이 가장 큼 → 별도 커밋·집중 검증 권장.
