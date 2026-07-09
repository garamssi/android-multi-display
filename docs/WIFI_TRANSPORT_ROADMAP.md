# WiFi 전송 로드맵 (USB에 더해 LAN/WiFi 연결)

> 상세 아키텍처·컴포넌트·보안·단계별 플랜의 정본은 `docs/WIFI_TRANSPORT_DESIGN.md`.
> 이 문서는 상위 개요다.

USB(adb reverse)뿐 아니라 같은 네트워크(WiFi/LAN)에서도 연결되게 하는 계획. 가드레일
(`CLAUDE.md`)을 따른다: 근본 원인, 클린 아키텍처(전송은 data 계층 추상화 뒤로), TDD,
와이어 변경 시 `tools/protocol_vectors.py` 동기화.

작성 시점 최신 OS 기준: macOS 26 "Tahoe"(15 Sequoia의 Local Network privacy 유지),
Android 16(local network 접근이 opt-in으로 도입, 향후 권한 필수화 예정). 권한 사실은
아래 "최신 OS 검토"에 출처와 함께 정리.

## 현재 상태

- 전송은 USB 전용: 클라이언트가 `127.0.0.1:<port>`로 다이얼하고 `adb reverse`가 USB로
  맥 서버에 터널링한다(맥이 서버). `ADBManager`/`PortForwardingWatcher`가 reverse 관리.
- 즉 IP/디스커버리/보안(TLS)/페어링 개념이 아직 없다. WiFi는 이 셋이 새로 필요하다.

## 목표 아키텍처

전송을 추상화해 USB와 LAN을 같은 상위 로직 뒤에 둔다.

- 도메인/데이터 경계에 `Transport`(연결 수립/주소 제공) 추상을 두고, 구현을
  `UsbTransport`(기존 adb reverse, 127.0.0.1)와 `LanTransport`(맥 LAN IP:포트)로 분리.
- 상위(핸드셰이크/비디오/입력 채널)는 "호스트/포트"만 받고 전송 종류를 모른다.
- 선택: 사용자 지정 또는 자동(USB 우선, 없으면 LAN).

## 디스커버리 (맥 IP 찾기)

- macOS(서버): Bonjour로 서비스 광고. `Network.framework`의 `NWListener`에
  `NWListener.Service(type: "_desklink._tcp", ...)`를 붙여 advertise.
- Android(클라이언트): `NsdManager`로 `_desklink._tcp` 검색 -> resolve로 IP/포트 취득.
  Android 16 대응: `NsdManager` 사용은 local network 접근에 해당하므로
  (a) `NEARBY_WIFI_DEVICES` 선언 + "Nearby devices" 권한 허용, 또는
  (b) `DiscoveryRequest#FLAG_SHOW_PICKER`(시스템 기기 선택 다이얼로그, 권한 없이 단일
  기기 선택)를 사용한다. 둘 중 UX/정책에 맞게 선택.
- 대안(디스커버리 없이): 사용자가 맥 IP를 직접 입력(수동 페어링). 초기 Phase에 유용.

## 보안 / 페어링

- USB는 물리 링크라 신뢰선이 있지만, LAN은 아니다. 반드시 추가:
  - 전송 암호화: `Network.framework`의 TLS(`NWProtocolTLS`) 또는 상호 인증. Android는
    `SSLSocket`/Conscrypt 또는 Ktor 등. self-signed + 핀 기반 신뢰가 현실적.
  - 페어링: 최초 연결 시 PIN/QR로 기기 승인, 이후 저장된 키로 재연결. 무단 접속 차단.
- 핸드셰이크에 인증 단계 추가(프로토콜 확장) — golden vector 갱신.

## 권한 표

| 플랫폼 | 필요 권한/설정 | 근거 |
|---|---|---|
| macOS | Local Network privacy 허용(Bonjour 광고/로컬 연결) | macOS 15+/26: IP 입력·Bonjour·유니/멀티캐스트 앱은 Local Network 권한 필요 |
| macOS | (기존) Screen Recording, Accessibility | 미러링/입력 |
| Android | `NEARBY_WIFI_DEVICES`(Nearby devices) 또는 `FLAG_SHOW_PICKER` | Android 16: mDNS/NsdManager·로컬 소켓은 local network 접근에 해당 |
| Android | `INTERNET`, `ACCESS_NETWORK_STATE` | 이미 선언됨 |
| Android | 포그라운드 서비스 타입 재검토 | USB는 `connectedDevice`. 순수 WiFi 세션엔 `dataSync`가 더 맞을 수 있음(단 일일 시간 제한 유의). 전송별로 타입 선택 검토 |

주의(macOS 26 Tahoe): Catalyst 등 일부 앱에서 Local Network 권한 프롬프트가 제대로
뜨지 않는 회귀 사례 보고가 있으니, 권한 미부여 시 명확한 안내/재시도 UX를 넣는다.

## Phase 계획

### P0 — 전송 추상화 (기반, 동작 변화 없음)
- 근본: 현재 USB 가정이 곳곳에 박혀 있으니(127.0.0.1 하드코딩 등), `Transport` 추상 뒤로
  숨긴다. 기존 USB 동작을 그대로 통과시키는 리팩터링.
- TDD: 상위 로직이 fake Transport로 호스트/포트만 받아 동작함을 검증(회귀 없음).

### P1 — 수동 IP LAN 연결 (디스커버리 없이)
- Android: 설정에 "맥 IP 직접 입력" 추가, `LanTransport`로 해당 IP:포트 다이얼.
- macOS: LAN 인터페이스에서도 `NWListener`가 수신(현재 loopback 전용 파라미터 완화).
  Local Network 권한 프롬프트 처리.
- 크로스체크: 맥의 리스너 바인딩 범위(loopback vs 전체)와 Android 다이얼 대상이 일치.
- TDD: Transport 선택/주소 파싱 단위 테스트.

### P2 — 자동 디스커버리 (Bonjour/NSD)
- macOS: `_desklink._tcp` 광고. Android: `NsdManager` 검색/resolve(권한 또는 PICKER).
- TDD: 디스커버리 결과 파싱/후보 선택 로직 단위 테스트(네트워크는 통합 테스트).

### P3 — 보안(TLS) + 페어링
- TLS 채널 + PIN/QR 페어링 + 신뢰 저장. 핸드셰이크 인증 단계 추가.
- 크로스체크: 맥 TLS 신원과 Android 신뢰 저장이 대칭. 프로토콜 인증 메시지 벡터 추가.
- TDD: 페어링 상태머신/인증 프레임 직렬화 단위 테스트.

### P4 — 전송 자동 선택 / 폴백
- USB 우선, 없으면 LAN. 세션 중 전송 전환 정책(가능하면). 상태머신(리팩터링 P4)과 연계.
- 크로스체크: 재연결/상태 표시가 전송 종류와 무관하게 일관.

## 크로스체크 원칙 (맥 <-> Android)

- 디스커버리(광고 타입/이름), 포트, TLS 신원, 페어링 프로토콜은 한 문서
  (`docs/protocol-spec.md`)에서 양쪽 합의. 한쪽만 바꾸지 않는다.
- 권한은 양쪽 모두 필요: 맥 Local Network, Android Nearby devices(또는 PICKER).
  둘 중 하나라도 없으면 LAN 연결이 조용히 실패하므로, 각 OS의 권한 안내 UX를 짝으로 구현.
- 각 Phase 종료 시 독립 리뷰로 대칭성/권한/프로토콜 벡터 검증.

## 검증
- 단위: Transport 선택/주소 파싱/디스커버리 파싱/페어링 상태머신/인증 프레임.
- 프로토콜: `python3 tools/protocol_vectors.py` "ALL CHECKS PASS" + 신규 벡터.
- 기기: USB 연결 회귀(변화 없음) + 동일 WiFi에서 디스커버리->연결->미러링, 권한 거부 시
  안내, 페어링/재연결, USB<->WiFi 폴백.

## 최신 OS 검토 (출처)

- macOS 15 Sequoia부터(26 Tahoe 유지) Local Network privacy가 도입되어, IP 입력·Bonjour·
  유니/멀티캐스트를 쓰는 앱은 Local Network 권한이 필요하다. `.local` 해석과 대부분의
  Bonjour 동작이 권한 대상(AirPlay/프린팅 등 고수준 서비스는 예외). Tahoe에서 일부 앱의
  권한 프롬프트 회귀 보고가 있어 미부여 대비 UX 필요.
- Android 16: local network 접근이 opt-in으로 도입되었고, mDNS(NsdManager)/로컬 소켓이
  이에 해당한다. 복구하려면 `NEARBY_WIFI_DEVICES` 선언 + Nearby devices 권한 허용, 또는
  `DiscoveryRequest#FLAG_SHOW_PICKER`(시스템 선택기)로 권한 없이 단일 기기 선택. 향후
  릴리스에서 Nearby devices 그룹의 신규 권한으로 강제될 예정.

Sources:
- [Local Network access on macOS 15 Sequoia — Rogue Research](https://www.rogue-research.com/2025/05/local-network-access-on-macos-15-sequoia/)
- [How local network privacy could affect you — The Eclectic Light Company](https://eclecticlight.co/2026/01/14/how-local-network-privacy-could-affect-you/)
- [macOS Tahoe local network permission issue — home-assistant/iOS #4192](https://github.com/home-assistant/iOS/issues/4192)
- [Local network permission — Android Developers](https://developer.android.com/privacy-and-security/local-network-permission)
- [Behavior changes: Apps targeting Android 16 — Android Developers](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Use network service discovery (NSD) — Android Developers](https://developer.android.com/develop/connectivity/wifi/use-nsd)
- [Request permission to access nearby Wi-Fi devices — Android Developers](https://developer.android.com/guide/topics/connectivity/wifi-permissions)
