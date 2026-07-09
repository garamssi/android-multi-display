# WiFi 전송 아키텍처 & 구현 플랜

USB(adb reverse)에 더해 같은 네트워크(WiFi/LAN)로도 연결되게 하는 상세 설계이자 정본 문서다.

가드레일(`CLAUDE.md`) 준수: 근본 원인 우선, 클린 아키텍처(전송/보안은 data 계층 추상 뒤),
클린 코드(전송 종류를 상위에 흩뿌리지 않음), TDD(Red→Green→Refactor), 와이어 변경 시
`docs/protocol-spec.md`와 `tools/protocol_vectors.py` 동기화.

최신 OS 검토 시점 기준: macOS 15 Sequoia+(26 Tahoe 유지)의 Local Network privacy,
Android 16의 로컬 네트워크 접근 opt-in(향후 강제). 출처는 문서 하단.

---

## 1. 근본 원인 진단 (왜 지금은 WiFi가 안 되나)

WiFi가 막힌 진짜 원인은 "네트워크 코드가 많아서"가 아니라, **엔드포인트(어디로 붙을지)와
링크 설정(adb reverse)이 전송 코드에 하드코딩**되어 있기 때문이다. 상위 계층(프레이밍,
핸드셰이크, 비디오/입력 파이프라인, keep-alive, 재연결)은 이미 전송 무관하다.

하드코딩 지점(수정 대상은 여기로 국한된다):

- 안드로이드 `data/network/TCPClient.kt` — `InetSocketAddress("127.0.0.1", port)`로 호스트 고정.
- 맥 `Data/Network/TCPServer.kt` — `params.requiredInterfaceType = .loopback`로 loopback 전용 수신.
- 맥 `Data/Network/ADBManager.kt` + `Data/Network/PortForwardingWatcher.kt` — adb reverse(USB 전용).
- 신뢰 모델: USB는 물리 링크 + loopback이 곧 인증/격리다. LAN엔 그 경계가 없다.

즉 근본 해결은 "전송/보안을 추상 뒤로 빼고, 상위는 호스트/스트림만 받는다"이다. 전송 종류를
`if (wifi)`로 곳곳에 분기하는 것은 증상 회피이므로 금지한다.

---

## 2. 불변 영역 (건드리지 않는다)

전송은 TCP 바이트 스트림 위에 얹혀 있어 다음은 **그대로** 재사용한다. 이것이 이 설계의
전제이자 안전판이다.

- 와이어 프레이밍 `[len u32 BE][type u8][payload]`, 코덱(HEVC/H.264), `tools/protocol_vectors.py`.
- 핸드셰이크 흐름, `ControlChannelUseCase`/`HandshakeHandler`(맥), `ConnectionManagerImpl`의
  핸드셰이크/keep-alive/재연결 상태머신(방금 하드닝한 `intendedConnected`/mutex/`lastRequestedConfig`).
- 비디오(`StartStreamingUseCase`/`VideoStreamRepositoryImpl`)·입력(`ReceiveInputUseCase`/
  `InputRepositoryImpl`) 파이프라인.

와이어에 추가되는 건 LAN 인증 단계 하나뿐이다(§6). TLS는 이 프레이밍 "아래"를 감싸므로
프레이밍/디코딩은 투명하게 그대로 동작한다.

---

## 3. 목표 아키텍처

### 3.1 계층 (의존 방향: presentation → domain ← data)

```
presentation      전송 선택(USB/WiFi) · 기기 목록 · 페어링(PIN) UI
      │  (관찰 가능한 상태만)
domain            Transport / PeerDiscovery / PairingStore / SecureChannel  (추상만)
      ▲  (구현이 인터페이스를 만족)
data              UsbTransport · LanTransport · NsdDiscovery · TlsSecureChannel
                  TCPClient(host,port) · (맥) TCPServer(interface) · Bonjour advertiser
```

핵심: 상위(핸드셰이크/비디오/입력 채널)는 "호스트/포트 + 이미 보안 처리된 스트림"만 받는다.
전송이 USB인지 LAN인지, 평문인지 TLS인지 **모른다**.

### 3.2 핵심 추상 (도메인)

- `Transport` — 현재 연결 대상을 해석한다.
  - `suspend fun target(): ConnectionTarget` → `ConnectionTarget(host, security)`.
  - USB: `host = "127.0.0.1"`, `security = PLAINTEXT` (adb reverse 터널, loopback).
  - LAN: `host = <발견/입력된 맥 IP>`, `security = SECURE` (TLS + 페어링).
- `PeerDiscovery` — LAN에서 맥 서버 후보를 찾는다. `fun servers(): Flow<List<DiscoveredServer>>`.
- `PairingStore` — 서버별 페어링 키를 저장/조회(재연결 시 PIN 재입력 불필요).
- `SecureChannel` — 연결된 소켓을 받아 읽기/쓰기 스트림을 돌려준다.
  - `PlaintextChannel`(USB): 무가공 통과.
  - `TlsChannel`(LAN): TLS 핸드셰이크 + 상호 인증 후 암호화 스트림.

### 3.3 보안 배치 결정 (근본 원인 관점)

- **USB는 지금 그대로**: loopback 바인딩 + 평문 + 페어링 없음. 물리 링크가 신뢰 경계다.
  여기에 페어링 의식을 더하는 건 근거 없는 마찰이므로 넣지 않는다.
- **LAN은 TLS + 페어링 필수**: 서브넷의 모든 기기가 포트에 닿고, 이 앱은 화면 스트림 +
  `CGEvent` 입력 주입(원격 제어)을 한다. 인증 없는 LAN 개방은 "아무나 내 맥을 보고 조종"이
  가능한 구멍이다(§7).
- 보안은 `if (wifi) encrypt()` 분기가 아니라 `SecureChannel` 전략으로 **일급 계층화**한다.
  전송이 자신의 `SecureChannel`을 고르고, 상위는 결과 스트림만 쓴다.

---

## 4. 컴포넌트 설계

### 4.1 안드로이드 (클라이언트)

- `data/network/TCPClient.kt`: `connect(host: String, port: Int)`로 호스트 파라미터화(리프 변경).
  소켓을 만든 뒤 주입된 `SecureChannel`로 스트림을 감싼다. `send`/`receivePackets`는 그
  스트림 위에서 그대로 동작(프레이밍 불변).
- `domain/transport/`(신규): `Transport`, `ConnectionTarget`, `SecurityMode`, `PeerDiscovery`,
  `DiscoveredServer`, `PairingStore`, `SecureChannel` 인터페이스.
- `data/transport/`(신규):
  - `UsbTransport`(127.0.0.1 + PLAINTEXT), `LanTransport`(발견/입력 IP + SECURE).
  - `NsdPeerDiscovery`(NsdManager로 `_desklink._tcp` 검색/resolve, 멀티캐스트 락 관리).
  - `TlsSecureChannel`(TLS + 페어링 키 기반 상호 인증), `PlaintextSecureChannel`.
  - `PrefsPairingStore`(EncryptedSharedPreferences 등에 페어링 키 저장).
- `ConnectionManagerImpl`/`VideoStreamRepositoryImpl`/`InputRepositoryImpl`: `connect()` 시
  `Transport.target()`으로 host/security를 받아 `TCPClient.connect(host, port)` 호출. 세 채널이
  같은 `Transport`를 공유해 동일 대상에 붙는다.
- presentation: `ConnectionViewModel`에 전송 종류/발견된 서버/페어링 상태 노출, `ConnectionScreen`에
  전송 토글·기기 목록·PIN 입력 UI. Hilt 바인딩(`di/AppModule.kt`)에 신규 구현 추가.
- 매니페스트: `NEARBY_WIFI_DEVICES`(및 필요 시 멀티캐스트 락 권한) 선언(§6).

### 4.2 맥 (서버)

- `Data/Network/TCPServer.kt`: 바인딩 인터페이스 파라미터화 — USB 리스너는 `.loopback`,
  LAN 리스너는 전체 인터페이스. LAN 리스너는 `NWProtocolTLS`를 스택에 추가(`SecureListener`).
- `App/ServerCoordinator.kt`: 전송 모드에 따라 리스너 구성. USB 모드는 기존 그대로(loopback +
  `PortForwardingWatcher`/adb). LAN 모드는 네트워크 바인딩 + Bonjour 광고 + 페어링 활성.
  두 모드 동시 허용도 가능(USB loopback 상시 + LAN opt-in).
- `Data/Network/`(신규): `BonjourAdvertiser`(NWListener `_desklink._tcp` 광고),
  `LocalNetworkPermission`(macOS Local Network 프롬프트 유도/안내), 서버측 페어링(PIN 생성·표시·검증,
  paired 키 저장).
- `HandshakeHandler`: LAN일 때 인증 단계 처리(§6). USB 경로는 인증 없이 기존대로.
- 설정 창(이미 있음, `Presentation/SettingsView.swift`)에 "WiFi 허용", "페어링 PIN 표시",
  Local Network 권한 상태를 추가(권한 패널과 동일 패턴).

### 4.3 데이터 흐름 요약

```
USB:  Transport.target() = (127.0.0.1, PLAINTEXT)
      → TCPClient.connect → PlaintextChannel → 프레이밍/핸드셰이크(기존)
      → 맥: loopback 리스너 + adb reverse (변화 없음)

LAN:  PeerDiscovery(또는 수동 IP) → 사용자 선택 → Transport.target() = (192.168.x.y, SECURE)
      → TCPClient.connect → TlsChannel(TLS+페어링 상호 인증) → 프레이밍/핸드셰이크
      → 맥: 네트워크 리스너(TLS) + Bonjour 광고 + 페어링
```

---

## 5. 재연결 (하드닝된 상태머신 위에)

WiFi는 IP 변동/로밍 특성이 있다. 방금 하드닝한 `ConnectionManagerImpl`(의도치 않은 손실만
재연결, mutex 직렬화, `lastRequestedConfig`)을 그대로 재사용하되, LAN 전용으로 한 가지 추가:
손실 후 재연결 시 저장된 IP가 죽었으면 `PeerDiscovery`로 재발견한 주소를 `Transport`가
제공하도록 한다. 즉 재연결 루프는 그대로, "대상 해석"만 전송에 위임한다(근본 원인 일관).

---

## 6. 프로토콜 변경 (LAN 인증 단계)

- 프레이밍/기존 메시지는 불변. LAN에서만 핸드셰이크 앞단에 상호 인증을 추가한다.
- 방식: 페어링에서 유도한 공유 키로 챌린지-응답(HMAC) 또는 TLS-PSK(§7)로 채널 자체 인증.
  전자를 쓸 경우 새 제어 메시지(예: `AUTH_CHALLENGE`/`AUTH_RESPONSE`, 입력 대역 밖 제어 태그)를
  추가하고 `docs/protocol-spec.md` + `tools/protocol_vectors.py`에 golden vector를 넣어 3자
  (Python/Swift/Kotlin) 일치를 유지한다("ALL CHECKS PASS").
- USB는 인증 메시지 없이 기존 흐름 그대로(회귀 없음).

---

## 7. 보안 설계 (지연 없는 방식)

- **암호화는 지연을 만들지 않는다**: AES-GCM 등 AEAD는 하드웨어 가속(맥 AES-NI, 태블릿 ARMv8
  크립토)으로 GB/s급이라 40Mbps 스트림 암호화는 프레임당 마이크로초. 스트리밍 지연은
  인코딩/디코딩/네트워크가 지배한다. TLS의 비용은 연결 시 1회 핸드셰이크(LAN에서 수 ms)뿐,
  전송 중 반복 없음. TLS 1.3 1-RTT + 세션 재개로 재연결도 거의 즉시. → 지연을 이유로 암호화를
  빼지 않는다.
- **인증(페어링)**: 최초 1회 맥이 PIN(또는 QR) 표시 → 태블릿 입력 → 양쪽이 공유 키 유도·저장 →
  이후 저장 키로 조용히 재연결. 공유 키로 양쪽이 서로를 증명하므로 상호 인증(무단 클라이언트 +
  가짜 서버 MITM 차단).
- **권장 조합**:
  1. TLS-PSK(페어링 키를 PSK로) — 인증+암호화 한 메커니즘, 인증서 관리 없음, 가벼운 핸드셰이크.
     맥 Network.framework는 PSK 지원. **안드로이드(Conscrypt) PSK 지원은 구현 시 검증 필요**(미확정).
  2. 대안(양쪽 확실): TLS(자체서명) + TOFU/핀닝 + 핸드셰이크 PIN 상호 인증.
- **위협 모델**: 신뢰된 개인 홈 네트워크·본인 기기만이면 실질 위험은 낮다. 그래도 최소선은
  PIN 인증(무단 제어 차단)이며, 광고(Bonjour)+평문+무인증 조합은 개인망에서도 피한다.
  배포/공유 시엔 TLS까지 필수.
- **키 관리**: 안드로이드는 EncryptedSharedPreferences/Keystore, 맥은 Keychain에 페어링 키 저장.

---

## 8. 권한 (검증된 최신 OS)

| 플랫폼 | 필요 | 근거/주의 |
|---|---|---|
| macOS | Local Network privacy 허용 | macOS 15+/26: IP 입력·Bonjour·유니/멀티캐스트 앱은 Local Network 권한 필요. `NWListener`/`NWBrowser`가 애플 권장 API. Info.plist에 Bonjour 서비스 + 사유 문자열 선언. 권한 상태를 미리 확실히 조회하는 공개 API가 없어 미부여 대비 안내/재시도 UX 필요. |
| macOS | (기존) Screen Recording, Accessibility | 미러링/입력 |
| Android | `NEARBY_WIFI_DEVICES` (+ mDNS 멀티캐스트 락) | Android 16: 로컬 네트워크 접근(mDNS/NsdManager/로컬 소켓)이 현재 opt-in(강제 아님), 향후 릴리스에서 Nearby devices 권한으로 강제 예정. `NsdManager`가 구글 권장 디스커버리. 대안으로 `DiscoveryRequest#FLAG_SHOW_PICKER`(권한 없이 시스템 선택기). |
| Android | (기존) `INTERNET`, `ACCESS_NETWORK_STATE` | 이미 선언 |
| Android | 포그라운드 서비스 타입 재검토 | USB는 `connectedDevice`. 순수 WiFi 세션엔 타입 적합성 재검토(전송별 선택). |

---

## 9. 단계별 플랜 (근본 원인 순서)

각 단계는 독립적으로 빌드/테스트되며 USB 회귀가 없어야 한다.

### P0 — 전송 추상화 리팩터링 (동작 변화 없음, 기반)
- 목표: §1의 하드코딩을 `Transport`/`SecureChannel` 뒤로 이동. USB 경로를 그대로 통과시키는
  순수 리팩터링. 이것이 이후 모든 단계를 여는 근본 수정.
- 변경: `TCPClient.connect(host, port)`; 세 채널이 `Transport.target()` 사용; `UsbTransport` +
  `PlaintextSecureChannel`가 기존 동작 재현; Hilt 바인딩.
- TDD: 상위 로직이 fake `Transport`(host/security만 제공)로 동작 — 회귀 없음. 기존 테스트 유지.
- DoD: USB 연결/스트림/입력/재연결이 이전과 동일. 새 경고 없음.

### P1 — 수동 IP LAN (디스커버리 없이, 평문, 개발 전용 게이트)
- 목표: 파이프라인이 LAN 위에서 도는지 검증. **배포 기본값 금지** — 명시 opt-in + 경고.
- 변경: 맥 `TCPServer` 네트워크 바인딩(LAN 리스너) + Local Network 권한 처리; 안드로이드 설정에
  "맥 IP 직접 입력" + `LanTransport`.
- TDD: 주소 파싱/`Transport` 선택 단위 테스트.
- DoD: 같은 WiFi에서 수동 IP로 미러링. USB 회귀 없음. 평문 경고 노출.

### P2 — 자동 디스커버리 (Bonjour / NsdManager)
- 목표: 맥을 자동 발견.
- 변경: 맥 `BonjourAdvertiser`(`_desklink._tcp`); 안드로이드 `NsdPeerDiscovery`(권한 또는 PICKER);
  UI 기기 목록.
- TDD: 디스커버리 결과 파싱/후보 선택 로직(네트워크는 통합 테스트).
- DoD: 목록에서 맥 선택 → 연결. 권한 거부 시 안내.

### P3 — 보안 (TLS + 페어링) — 실사용 전 필수
- 목표: LAN 채널 암호화 + 상호 인증.
- 변경: `TlsSecureChannel`(맥/안드로이드 대칭) + PIN/QR 페어링 + 키 저장 + 핸드셰이크 인증(§6).
  TLS-PSK 우선(안드로이드 PSK 검증), 아니면 TLS+TOFU+PIN.
- TDD: 페어링 상태머신 + 인증 프레임 직렬화(golden vector) 단위 테스트. `protocol_vectors.py` 갱신.
- DoD: 미페어링 기기 접속 거부. 도청 불가(암호화). 지연 체감 없음.

### P4 — 전송 자동 선택 / 폴백
- 목표: USB 우선, 없으면 LAN. 재연결/상태 표시가 전송 무관하게 일관.
- 변경: `Transport` 선택 정책 + §5 재발견 연동.
- TDD: 선택/폴백 정책 단위 테스트.
- DoD: USB↔WiFi 전환 시 UI/재연결 일관.

---

## 10. 크로스체크 매트릭스 (맥 ↔ 안드로이드)

한쪽만 바꾸지 않는다. 아래 계약은 `docs/protocol-spec.md`에서 합의·기록한다.

| 항목 | 맥(서버) | 안드로이드(클라이언트) | 합의 지점 |
|---|---|---|---|
| 디스커버리 | `_desklink._tcp` 광고(`NWListener`) | `NsdManager` 검색/resolve | 서비스 타입·이름·TXT |
| 주소/포트 | LAN 인터페이스 바인딩, 7100~7102 | 발견/입력 host + 동일 포트 | 포트 상수 |
| 보안 | TLS(PSK 또는 자체서명) 신원 | 동일 신뢰(PSK/핀 저장) | TLS 방식·신원 |
| 페어링 | PIN 생성·표시·검증 | PIN 입력·키 저장 | 인증 프레임 golden vector |
| 권한 | Local Network | NEARBY_WIFI_DEVICES(또는 PICKER) | 둘 다 필요 — 미부여 UX 짝 구현 |
| 재연결 | 리스너 유지 | 손실→재발견→재연결 | 전송 무관 상태 표시 |

각 단계 종료 시 독립 리뷰(서브에이전트)로 대칭성·권한·프로토콜 벡터 검증.

---

## 11. 테스트 전략 (TDD)
- 단위(순수): `Transport` 선택/주소 해석, 디스커버리 결과 파싱, 페어링 상태머신, 인증 프레임
  직렬화(golden vector), `SecureChannel` 선택.
- 프로토콜: `python3 tools/protocol_vectors.py` "ALL CHECKS PASS" + 신규 인증 벡터(Swift/Kotlin 일치).
- 기기: USB 회귀(변화 없음), 동일 WiFi 디스커버리→연결→미러링, 권한 거부 안내, 페어링/재연결,
  USB↔WiFi 폴백.

---

## 12. 리스크 / 미해결 질문
- 안드로이드 TLS-PSK 지원(Conscrypt) 확정 — 미지원 시 TLS+TOFU 경로로.
- macOS Tahoe Local Network 권한 프롬프트 회귀 보고 — 미부여 대비 안내/재시도 UX 필수.
- 포그라운드 서비스 타입(WiFi 세션) 적합성.
- USB/LAN 동시 리스너 vs 모드 전환 — 초기엔 모드 전환(단순), 이후 동시 허용 검토.

---

## 정의된 완료(DoD, 전체)
1. 근본 원인(엔드포인트/링크/보안 하드코딩)이 추상 뒤로 이동, `if (wifi)` 산재 없음.
2. USB 경로 회귀 없음(빌드·동작·테스트 동일).
3. LAN은 TLS + 페어링 없이는 연결 불가(무단 제어/도청 차단).
4. 와이어 변경분은 `protocol-spec.md` + `protocol_vectors.py` 동기화, 3자 벡터 일치.
5. 계층/네이밍 준수, 신규 경고 없음, 각 단계 크로스체크 통과.

---

## 출처 (권한/보안 사실)
- [Local network permission — Android Developers](https://developer.android.com/privacy-and-security/local-network-permission)
- [Behavior changes: Apps targeting Android 16 — Android Developers](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Use network service discovery (NSD) — Android Developers](https://developer.android.com/develop/connectivity/wifi/use-nsd)
- [Local Network access on macOS 15 Sequoia — Rogue Research](https://www.rogue-research.com/2025/05/local-network-access-on-macos-15-sequoia/)
- [How to use multicast networking in your app — Apple Developer](https://developer.apple.com/news/?id=0oi77447)
