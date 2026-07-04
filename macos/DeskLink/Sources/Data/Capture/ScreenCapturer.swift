// ScreenCapturer (CGDisplayStream-based) was removed.
//
// Screen capture now uses `SCKScreenCapturer` (ScreenCaptureKit / SCStream),
// which is the supported API on modern macOS. The old CGDisplayStream
// implementation is intentionally left empty here because CGDisplayStream is
// deprecated (macOS 14+). This file can be deleted from the Xcode project when
// convenient; it is kept empty only because the review sandbox cannot delete it.
