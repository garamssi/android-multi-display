# Handoff: DeskLink OS UI Redesign

## Overview
DeskLink is a USB screen-streaming product: a **macOS menu-bar server app** that mirrors the Mac desktop to an **Android tablet client**. This handoff covers a full visual redesign of four surfaces:

1. macOS menu-bar popover (Server) — Disconnected + Connected states
2. Tablet connect flow — Start / Connecting (loading) / Error states
3. Tablet Settings screen (adaptive to any tablet resolution)
4. Tablet floating overlay control (collapsed → expands to reveal +2 actions)

The redesign replaces the previous "clunky" look with a unified, refined dark "pro tool" aesthetic (Linear/Raycast direction).

## About the Design Files
The file in this bundle — `DeskLink OS UI.dc.html` — is a **design reference created in HTML**. It is a prototype showing the intended look and behavior, **not production code to copy directly**. The `.dc.html` format wraps the markup in a small runtime; ignore the runtime and read the inline HTML/CSS as the spec.

The task is to **recreate these designs in DeskLink's existing codebases**, using each platform's established patterns and libraries:
- **macOS server** → the Mac app's native stack (SwiftUI / AppKit `NSMenu`/`NSPopover`, or Electron if that's what's in use).
- **Tablet client** → the Android app's stack (Jetpack Compose, or the existing View system).

Do **not** ship the HTML. Reproduce the visuals pixel-accurately using native components.

## Fidelity
**High-fidelity (hifi).** Final colors, typography, spacing, radii, shadows, and interaction states are all specified below. Recreate pixel-accurately using the codebase's existing UI libraries. Icons are drawn as inline SVG line icons — map them to the project's existing icon set (e.g. Lucide/SF Symbols/Material Symbols) matching the described glyph and stroke weight.

---

## Design Tokens

### Colors
| Token | Hex / value | Use |
|---|---|---|
| Accent gradient | `linear-gradient(180deg, #7079FF, #5B6BFF)` | Primary buttons, selected segments |
| Accent solid | `#5B6BFF` | Focus/selected borders, dots |
| Accent light | `#7C86FF` | Selected radio, icon tints, glow |
| Accent violet | `#8A5BFF` | Gradient end on app glyph |
| App-glyph gradient | `linear-gradient(150deg, #7C86FF, #5B6BFF 55%, #8A5BFF)` | Logo tile |
| Success green | `#35D0A5` | Connected dot, USB-detected chip |
| Success green (text) | `#4fe0ba` | Live/connected accents |
| Error red | `#FF5C5C` / `#FF7A7A` / `#FF8A8A` | Error icon, disconnect, quit hover |
| Warning amber | `#E0A64B` | "Not connected" status dot |
| Page background | `radial-gradient(1300px 850px at 28% -12%, #13161f, #090a0f 55%, #050609)` | Studio canvas only (not the app) |
| App base bg | `#0A0C10` | Tablet screen background |
| Panel bg | `linear-gradient(180deg, rgba(32,36,45,.94), rgba(18,21,27,.96))` | macOS popover |
| Surface subtle | `rgba(255,255,255,.03)` – `.05` | Cards, segmented tracks, chips |
| Surface hover | `rgba(255,255,255,.06)` – `.09` | Row/button hover |
| Border subtle | `rgba(255,255,255,.07)` – `.08` | Card/track borders |
| Border strong | `rgba(255,255,255,.10)` – `.16` | Popover, glass buttons |
| Text primary | `#EAEDF3` | Titles, values |
| Text secondary | `#98A0AF` | Subtitles, body |
| Text tertiary | `#7A8290` / `#626A78` | Captions, mono labels |

### Typography
- **UI font:** `IBM Plex Sans` (weights 400 / 500 / 600 / 700)
- **Technical/values font:** `IBM Plex Mono` (weights 400 / 500 / 600) — used for resolutions, fps, bitrate, timers, status chips, port/link info, error codes, section labels.
- Scale used: page title 34/700; screen title (tablet) 40/700; section headings 22–26/600; body 15–16/400–500; controls 14–17/500–600; mono labels 11–12.5 with `letter-spacing: .16em–.28em`, uppercase.

### Spacing & shape
- Radii: buttons 10–16px; cards/segmented 12–14px; app glyph 16–22px; popover 16px; circular overlay buttons 50%; device screen 23–24px.
- Card padding: 13–16px (popover), 15–16px (settings rows), 30–34px (settings page).
- Segmented control: 4px inner padding, 4px gap between segments.

### Shadows
- Primary button: `0 8px 18px -7px rgba(91,107,255,.85)` + inset `0 1px 0 rgba(255,255,255,.3)` (larger on tablet CTA: `0 18px 36px -12px`).
- Popover/elevated: `0 32px 70px -22px rgba(0,0,0,.75)` + inset `0 1px 0 rgba(255,255,255,.06)`.
- Glass overlay button: `0 10px 24px -10px rgba(0,0,0,.7)` + `backdrop-filter: blur(22px)`.

---

## Screens / Views

### 1. macOS Menu-bar Popover
**Purpose:** Control the server from the Mac menu bar.
**Layout:** 320px-wide rounded (16px) translucent panel with `backdrop-filter: blur(30px)`, anchored under a highlighted display icon in the menu bar. 15px padding.

**Components (top → bottom):**
- **Header row:** 32px accent-gradient rounded-square glyph (monitor line icon, white) + "DeskLink Server" (15/600) + "v1.4.0" (mono 11, `#7A8290`). Connected variant adds a `LIVE` pill (mono 10.5, green, right-aligned).
- **Divider:** 1px `rgba(255,255,255,.07)`.
- **Status row:** colored dot + label + right-aligned mono meta.
  - Disconnected: amber dot (pulsing, `dl-pulse` 1.8s), "Not connected", chip "USB · idle".
  - Connected: green dot (glow), "Connected" (500), timer "02:14:08" (mono).
- **Connected only — stats panel:** `rgba(255,255,255,.04)` card, 12px radius, 2-col grid, all mono. Fields: Device `Galaxy Tab S9`, Link `USB 3.2`, Output `2560×1600`, Frame `60 fps · H.265`. Each field = uppercase 10px label (`#626A78`) + 13px value (`#D3D8E2`).
- **Primary action:** full-width 40px accent-gradient button.
  - Disconnected: "Start Server" + play glyph.
  - Connected: "Stop Server" + stop glyph, restyled to red-tint surface (`rgba(255,92,92,.1)`, border `rgba(255,92,92,.28)`, text `#FF8A8A`).
- **Ghost rows:** 38px transparent rows w/ leading icon; hover `rgba(255,255,255,.06)`. Items: "Settings…" (gear). Then divider, then "Quit DeskLink" (logout glyph, hover turns red-tinted).

### 2. Tablet — Start screen
**Purpose:** Entry point; initiate USB connection.
**Layout:** Full-screen, centered column on `radial-gradient(900px 520px at 50% 18%, #14161f, #0A0C10 68%)`. Top status bar (30px, mono time + wifi/battery icons at 42% opacity).
**Components:** 78px app glyph → "DeskLink" (40/700) → "Connect to your Mac over USB" (16, secondary) → **Connect** button (230×56, 16px radius, accent gradient, USB-link glyph, hover `brightness(1.08)`) → "Settings" ghost text button → bottom pill chip "● USB · Mac detected" (green dot, mono).

### 3. Tablet — Connecting (loading) state
Same layout as Start. Center: 78px app glyph wrapped in a spinning ring (`dl-spin` 0.9s, 2.5px, top border `#7C86FF`) → "Connecting…" (24/600) → **indeterminate progress bar** (300×6, track `rgba(255,255,255,.08)`, 42%-wide moving fill `linear-gradient(90deg,#7079FF,#8A5BFF)`, `dl-indeterminate` 1.3s ease loop) → "Negotiating video stream · H.265" (mono 13) → outlined **Cancel** button.

### 4. Tablet — Error state
Same layout, bg tinted `radial-gradient(... #1c1418 ...)`. Center (max-width 400, centered text): 78px red-tint rounded-square with alert-triangle icon (`#FF7A7A`) → "Connection failed" (26/600) → message "Couldn't reach the DeskLink server. Make sure the Mac app is running and your USB cable is securely connected." (15.5, secondary) → button row: **Try again** (accent gradient + refresh glyph) + **Open Settings** (outlined) → error-code chip "ERR_USB_NO_SERVER" (mono 12, red-tinted).

### 5. Tablet — Settings (adaptive)
**Purpose:** Configure stream quality.
**Layout:** Full screen. 66px header (back button 40px rounded-square + "Settings" 22/600 + right "Reset to defaults" ghost). Body is a **2-column grid** (36px gap, 30–34px padding) that scales to any tablet aspect ratio.
- **Left column — Resolution:** mono section label + 2×2 grid of radio cards. Each card = radio dot + name (15/600) + mono value. Selected card (Native `3200 × 2000`): border `rgba(124,134,255,.5)`, bg `rgba(124,134,255,.1)`, filled radio. Others: QHD `2560×1600`, FHD+ `1920×1200`, HD `1280×800`. Below: an info note card explaining presets auto-adapt to the connected tablet's native resolution.
- **Right column** (flex, 26px gap):
  - **Frame rate** segmented: 30 / **60** / 120 fps (60 selected: gradient + check glyph).
  - **Bitrate** segmented (58px tall, 2-line): Low `10 Mbps` / Medium `20 Mbps` / **High `40 Mbps`** (selected).
  - **Codec** segmented: **H.265 · HEVC** (selected + check) / H.264 · AVC.
  - Bottom: green-tinted summary chip "Estimated stream — 2560×1600 · 60fps · 40Mbps" (values in mono).

**Segmented control spec:** container `rgba(255,255,255,.05)` bg, `rgba(255,255,255,.07)` border, 4px pad, 4px gap. Segments `flex:1`, 46–58px tall, 10px radius. Selected = accent gradient + white text + `0 6px 14px -6px rgba(91,107,255,.8)` shadow (+ leading check glyph). Unselected = text `#98A0AF`.

### 6. Tablet — Floating overlay control
**Purpose:** In-stream controls over the mirrored Mac desktop. A single unobtrusive handle that expands to reveal more actions.
**Layout:** Top-center of the stream, `position:absolute; top:16px`. A horizontal flex group: [expandable actions] + [always-visible handle].
- **Handle button** (always visible): 48px circle, glass (`rgba(18,21,27,.72)` + blur 22, border `rgba(255,255,255,.14)`), vertical-dots (⋮) icon. On tap it toggles open.
- **Expanded (+2 actions)** slide/fade in to the LEFT of the handle:
  - **Settings** — 48px glass circle, gear icon.
  - **Disconnect** — 48px circle, red-tint (`rgba(255,92,92,.16)`, border `rgba(255,92,92,.35)`, icon `#FF8A8A`), X icon.
- **Collapsed** shows only the handle. **Expanded** = 3 buttons total (handle + Settings + Disconnect), i.e. tapping the handle *adds 2 buttons*.

---

## Interactions & Behavior
- **Overlay handle toggle:** tap ⋮ → `menuOpen` flips. Open state: the two extra buttons animate in (max-width 0→160px, opacity 0→1, translateX 14px→0, marginRight 0→12px) over `.34s cubic-bezier(.4,0,.2,1)`; overflow hidden so they collapse cleanly. The handle itself rotates 90° and turns accent-filled (`rgba(91,107,255,.95)`) when open, over `.3s`. When collapsed, extra buttons have `pointer-events:none`.
- **Connect flow:** Start → tap Connect → Connecting (indeterminate bar) → success (stream begins, overlay control appears) OR failure → Error screen. Error "Try again" re-runs the connect attempt; "Open Settings" routes to Settings.
- **Server flow (Mac):** Start Server → status goes amber→green, popover switches to Connected layout with live stats + timer. Stop Server reverses it.
- **Hover states:** primary buttons `brightness(1.08)`; ghost rows/buttons gain `rgba(255,255,255,.06)` bg; Quit gains red tint; unselected resolution cards brighten border to `rgba(255,255,255,.18)`.
- **Animations (keyframes):**
  - `dl-indeterminate` (1.3s cubic-bezier loop): loading bar fill translates left→right.
  - `dl-spin` (0.9s linear loop): connecting ring.
  - `dl-pulse` (1.8s ease loop): amber status dot scale/opacity.

## State Management
- **Mac app:** `serverStatus` (disconnected | connecting | connected), plus connection metadata (device name, link type, resolution, fps, codec, uptime timer).
- **Tablet:** `connectionState` (idle | connecting | connected | error) with `errorCode`; `overlayExpanded` (bool); settings values `{ resolution, frameRate, bitrate, codec }`. Resolution options are **derived from the connected tablet's detected native resolution** — populate the preset list dynamically rather than hardcoding, and cap presets at/below native.

## Assets
- Icons: monitor, wifi, battery, play, stop, gear, logout, chevron-left, check, alert-triangle, refresh, USB/link, vertical-dots — all inline SVG line icons here; **replace with the codebase's existing icon set** matching each glyph and ~1.7px stroke weight.
- Fonts: IBM Plex Sans + IBM Plex Mono (Google Fonts). If the app already has a UI + mono pairing, this is the intended substitution target; otherwise bundle IBM Plex.
- No raster images or logos are required — the app glyph is a gradient tile + monitor icon.

## Files
- `DeskLink OS UI.dc.html` — the full design board (all four surfaces + states). Open in a browser to inspect live; read the inline styles for exact values.
