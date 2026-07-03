# DeskLink — Engineering Guardrails

Project-wide rules for any agent working in this repo. These are not suggestions.
Loaded automatically each session; apply to every change in `macos/`, `android/`,
`docs/`, and `tools/`.

## Non-negotiable principles

### 1. Root cause, never a workaround
- Fix the underlying cause. Do NOT suppress, silence, or route around an error to
  make it "go away."
- Banned as a *fix by itself*: broad `try/catch` that swallows, `@ts-ignore`-style
  suppressions, disabling a lint/compiler diagnostic, `!` force-unwraps to dodge a
  nil, retry loops that hide a logic bug, hardcoding a value to skip a real
  computation. These are allowed only when they ARE the correct design, with a
  comment saying why.
- Before claiming a fix, state the actual cause in one sentence. If you can't name
  the cause, you haven't found it yet.
- Example from this repo (do this): the `ButtonStyle` "does not conform" error was
  caused by a nested `struct Body` colliding with `ButtonStyle`'s `Body` associated
  type. The fix was renaming the struct to `Content` — not changing access levels to
  quiet the compiler. Changing `private`→`fileprivate` was a symptom patch and was
  reverted.
- Another (do this): black screen on reconnect was fixed by recreating the
  video/input servers and capturer with fresh `AsyncStream`s in `bootStreaming` —
  not by adding a delay or a reconnect retry that masked the stale stream.

### 2. Clean Architecture
- Keep the layering: `domain` (entities, use cases, protocols) has NO dependency on
  `data` or `presentation`. Dependencies point inward only.
- UI talks to the domain through use cases / repositories, never straight to sockets,
  codecs, or platform APIs.
- Platform specifics (ScreenCaptureKit, VideoToolbox, CGVirtualDisplay, MediaCodec,
  WindowManager, `Network.framework`) live behind an interface in `data`. Domain and
  presentation depend on the abstraction, not the framework.
- State flows one way: ViewModel exposes observable state; the View renders it.
  No business logic in Views/Composables.

### 3. Clean Code
- Names say intent; no abbreviations that need a mental lookup.
- Small, single-responsibility functions and types. If a function needs a "and"
  to describe it, split it.
- No dead code, no commented-out blocks, no leftover debug prints in committed code.
- Comments explain WHY (a non-obvious constraint, a platform quirk), not WHAT the
  code already says.
- No magic numbers/strings: colors, radii, sizes, timings, protocol byte tags come
  from named tokens/constants (e.g. `DesignTokens`, `DeskLinkTokens`, protocol enums).
- No emojis anywhere — code, comments, docs, scripts, or commit messages.

### 4. TDD
- Red → Green → Refactor. Write the failing test first, make it pass with the
  simplest correct change, then refactor with the test as a safety net.
- Test behavior through public APIs (use cases, protocol codecs, view-model state),
  not private internals.
- The wire protocol is verified against the golden vectors in
  `tools/protocol_vectors.py` — any framing/encoding change must keep
  "ALL CHECKS PASS" and add a vector for the new case.
- A bug fix starts with a test that reproduces the bug (fails), then the fix.

## Project-specific rules

- Resolution presets are ALWAYS derived from the tablet's native resolution at
  runtime. Never hardcode a resolution list.
- Transport is USB via `adb reverse` (device -> host). The Mac is the server; never
  use `adb forward`.
- The macOS app must run as a signed `.app` (see `macos/DeskLink/scripts/`) so the
  Screen Recording / Accessibility (TCC) grants persist across rebuilds. Don't ship
  or test an unsigned binary for device runs.
- Wire protocol framing is `[len: uint32 BE][type: u8][payload]`, big-endian. Keep
  `docs/protocol-spec.md` and `tools/protocol_vectors.py` in sync with any change.
- UI follows `design_handoff_desklink_os_ui/`. Reimplement in each platform's native
  patterns (SwiftUI / Compose), driven by real observable state — not the HTML, and
  not hardcoded screens.
- Swift 6 strict concurrency: prefer immutable `static let` lazy init over mutable
  globals; reach for `nonisolated(unsafe)` only when it is genuinely the right model,
  with a comment.

## Definition of done
1. Root cause named and addressed.
2. Builds clean (`macos`: `./scripts/build_and_run.command`; `android`: Gradle with
   JDK 17/21).
3. Tests written/updated and passing; protocol vectors still pass if the wire changed.
4. No new warnings introduced where avoidable.
5. Architecture layering and naming conventions respected.

## Communication
- Respond in Korean.
- Be concise and direct; cut words that don't change the meaning.
- Use double quotes for emphasis.
- State facts objectively; do not fabricate. Say so when something is uncertain or
  may be outdated, and verify current facts via web search when they may have changed.
- If the user is wrong, say so and explain why.
