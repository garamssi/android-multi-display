# DeskLink — App Icon Assets & Install Guide

The DeskLink product icon (two overlapping screens — a desktop display behind, a tablet in front — on the indigo→violet brand gradient) rendered as production-ready files for **both platforms**. Hand this whole folder to Claude Code with the prompt at the bottom.

## What's in the box

```
desklink_icon_assets/
├─ macos/
│  ├─ AppIcon.iconset/         ← all 10 sizes, correct @2x names (ready for iconutil)
│  │   icon_16x16.png … icon_512x512@2x.png
│  └─ desklink_macos_1024.png  ← single 1024² master (rounded, transparent corners)
├─ android/
│  ├─ adaptive/
│  │   ic_launcher_background.png   ← 432² gradient background layer
│  │   ic_launcher_foreground.png   ← 432² glyph, transparent, inside 66dp safe zone
│  │   ic_launcher_monochrome.png   ← 432² glyph for Android 13+ themed icons
│  ├─ mipmap-anydpi-v26/
│  │   ic_launcher.xml               ← adaptive-icon definition (bg + fg + monochrome)
│  │   ic_launcher_round.xml
│  ├─ mipmap-mdpi … mipmap-xxxhdpi/  ← legacy PNG fallbacks (ic_launcher + _round)
│  └─ ic_launcher_playstore.png      ← 512² square, for the Play Store listing
└─ source/
   icon_rounded.svg / icon_square.svg / icon_foreground.svg /
   icon_background.svg / icon_monochrome.svg   ← infinitely scalable vector masters
```

The `source/*.svg` files are the true masters — regenerate any raster size from them if needed.

## macOS install

**Option A — build the .icns (recommended):**
```bash
cd desklink_icon_assets/macos
iconutil -c icns AppIcon.iconset -o AppIcon.icns
```
Then add `AppIcon.icns` to the app target and set it as the app icon (Xcode: drag into the asset catalog's AppIcon, or set `CFBundleIconFile`/`ASSETCATALOG`).

**Option B — asset catalog:** drop the individual `AppIcon.iconset/*.png` into `Assets.xcassets/AppIcon.appiconset` matching each slot (16pt…512pt, 1x/2x).

**Menu-bar item:** the menu bar needs a small *template* (monochrome) image, not the color icon. Use `source/icon_monochrome.svg` exported at 18×18 / 36×36 (@2x), name it `menubarTemplate` and set `image.isTemplate = true` so macOS tints it for light/dark automatically.

## Android install

Copy into `app/src/main/res/`:
- `adaptive/ic_launcher_background.png` → `drawable/` (or `mipmap-xxxhdpi/`)
- `adaptive/ic_launcher_foreground.png` → `drawable/`
- `adaptive/ic_launcher_monochrome.png` → `drawable/`
- `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` → `mipmap-anydpi-v26/`
- each `mipmap-<density>/ic_launcher.png` + `ic_launcher_round.png` → the matching `mipmap-<density>/`

Confirm `AndroidManifest.xml` points at them:
```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round" ... >
```
Upload `ic_launcher_playstore.png` (512²) in the Play Console listing.

> Better than PNGs: import `source/icon_foreground.svg` + `icon_background.svg` via **Android Studio → New → Image Asset → Launcher Icons (Adaptive & Legacy)**, which regenerates every density as vector drawables. The PNGs here are a drop-in fallback if you don't want to run that.

## Design spec (for regeneration / tweaks)
- **Background gradient:** `linear-gradient(150deg, #8B93FF 0%, #5B6BFF 52%, #8A5BFF 100%)` + a top→transparent white sheen (`rgba(255,255,255,.32)` → 0 at 56%).
- **Glyph:** desktop = rounded-rect *outline* (white, ~82% opacity, stroke ≈ 6/100 of the box); tablet = solid white rounded-rect, front-right, overlapping. Both `stroke-linejoin: round`, corners rounded.
- **macOS corner radius:** 22.4% of the tile. **Android:** background is full-bleed (OS applies the mask); foreground glyph sits within the centre 66% safe zone.
- **Monochrome/template:** single-colour glyph on transparent — do not bake a background.

---

## Prompt to give Claude Code

> DeskLink 앱의 아이콘을 새 브랜드 아이콘으로 **교체**해줘. `desklink_icon_assets/` 폴더에 macOS·Android용 완성 파일과 `README.md` 설치 가이드가 들어있어. 먼저 README를 전부 읽어.
>
> 작업:
> 1. **저장소 파악** — 이 프로젝트가 macOS 앱인지 Android 앱인지(또는 둘 다인지) 확인하고, 현재 아이콘이 정의된 위치(Xcode asset catalog / `AndroidManifest.xml` + `res/mipmap*`)를 찾아. 기존 아이콘 리소스는 새 것으로 대체하고, 더 이상 참조되지 않는 옛 파일은 정리해.
> 2. **macOS면:** `macos/AppIcon.iconset` 로 `iconutil`을 돌려 `AppIcon.icns`를 만들거나, PNG를 `Assets.xcassets`의 AppIcon 슬롯에 넣어 앱 아이콘으로 설정해. 메뉴바 아이템에는 컬러 아이콘이 아니라 `source/icon_monochrome.svg`를 18/36px로 뽑아 `isTemplate = true` 템플릿 이미지로 써.
> 3. **Android면:** `adaptive/` 레이어와 `mipmap-anydpi-v26/*.xml`, 각 density의 `ic_launcher*.png`를 `res/`의 대응 폴더에 복사하고, `AndroidManifest.xml`의 `android:icon`·`android:roundIcon`이 올바르게 가리키는지 확인해. 가능하면 `source/*.svg`를 Android Studio의 Image Asset으로 재생성하는 게 더 깔끔해.
> 4. 빌드해서 홈스크린/Dock/메뉴바·상태바에서 아이콘이 정상 표시되는지 확인하고, 변경한 파일 목록을 알려줘.
>
> 색·모양은 README의 스펙을 정확히 따르고, 임의로 바꾸지 마. 불명확한 부분은 물어봐.
