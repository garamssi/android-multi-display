import Foundation
import CoreGraphics
import ApplicationServices

/// Injects touch events as mouse events via the CGEvent API.
/// Maps normalized coordinates (0-1) onto the target display's on-screen rectangle
/// in the global coordinate space (points), via `CGDisplayBounds` (see `globalPoint`).
///
/// - S-M6: injection requires Accessibility (AXIsProcessTrusted) permission.
///   `injectEvent` throws `inputPermissionDenied` (1301) when the process is not
///   trusted, so the caller can surface a permission prompt.
/// - S-M7: mouse-down state is tracked **per pointerId** rather than with a single
///   shared `Bool`, so interleaved multi-pointer streams do not corrupt each
///   other's down/drag state.
///
/// ## macOS single-pointer limitation
/// macOS has no public multi-touch injection API; all pointers are mapped onto the
/// **single system mouse cursor** and the left mouse button. True simultaneous
/// multi-touch (e.g. pinch-zoom) therefore cannot be reproduced — concurrent
/// pointers are serialized onto one cursor. Per-pointer down tracking still matters:
/// it keeps each pointer's MOVE correctly classified as drag vs. hover and ensures a
/// stray UP for one pointer doesn't cancel another pointer's drag. Practically,
/// injection is driven by the primary (first-down) pointer.
public final class CGEventInjector: InputReceiving, @unchecked Sendable {
    private let lock = NSLock()

    /// Set of pointerIds currently in the "down" state. Empty means no button held.
    private var downPointers: Set<UInt8> = []

    public init() {}

    public func startReceiving(port: UInt16) -> AsyncThrowingStream<TouchEvent, Error> {
        // Input receiving is handled by TCPServer + TouchDeserializer; the actual
        // events are delivered via ReceiveInputUseCase. This stub returns an empty
        // stream to satisfy the protocol.
        AsyncThrowingStream<TouchEvent, Error>(bufferingPolicy: .bufferingNewest(60)) { _ in }
    }

    public func stopReceiving() async {
        lock.withLock {
            downPointers.removeAll()
        }
    }

    /// Returns true if the process is trusted to post input events (Accessibility).
    public static func isTrusted() -> Bool {
        AXIsProcessTrusted()
    }

    /// Requests Accessibility permission, prompting the user if it is not yet granted.
    /// Unlike `AXIsProcessTrusted()` (which only checks, silently), this ALSO registers
    /// the app in System Settings > Privacy & Security > Accessibility so the user can
    /// enable it — without this, injected mouse events are blocked and the app never
    /// even appears in the list. Returns the current trust state.
    @discardableResult
    public static func requestAccessibility() -> Bool {
        // Key is `kAXTrustedCheckOptionPrompt`; the string literal avoids CFString /
        // Unmanaged bridging differences across SDKs. `true` shows the system prompt and
        // lists the app when not yet trusted (no dialog if already trusted).
        let options = ["AXTrustedCheckOptionPrompt": true] as CFDictionary
        return AXIsProcessTrustedWithOptions(options)
    }

    /// Maps normalized [0,1] coordinates onto a point in the GLOBAL display coordinate
    /// space, given the target display's bounds. CGEvent cursor positions are in
    /// points, and `CGDisplayBounds` is the display's on-screen rectangle in points —
    /// so we scale by the point size, NOT the pixel resolution. This is correct on
    /// HiDPI/Retina (where points != pixels) and on multi-display layouts (the origin
    /// offset places the point on the right screen). Input is clamped defensively.
    static func globalPoint(normalizedX: Float, normalizedY: Float, displayBounds: CGRect) -> CGPoint {
        let clampedX = min(max(Double(normalizedX), 0), 1)
        let clampedY = min(max(Double(normalizedY), 0), 1)
        return CGPoint(
            x: displayBounds.origin.x + clampedX * displayBounds.width,
            y: displayBounds.origin.y + clampedY * displayBounds.height
        )
    }

    public func injectEvent(_ event: TouchEvent, displayID: UInt32) async throws {
        // S-M6: require Accessibility permission before posting any event.
        guard AXIsProcessTrusted() else {
            throw ConnectionError.inputPermissionDenied
        }

        // Map onto the display's on-screen rectangle (points). An empty rect means the
        // display id is invalid / not ready — treat as an injection failure.
        let displayBounds = CGDisplayBounds(displayID)
        guard displayBounds.width > 0, displayBounds.height > 0 else {
            throw ConnectionError.inputInjectionFailed
        }
        let point = Self.globalPoint(
            normalizedX: event.x,
            normalizedY: event.y,
            displayBounds: displayBounds
        )

        switch event.action {
        case .down:
            try injectMouseDown(at: point, pointerId: event.pointerId)
        case .up:
            try injectMouseUp(at: point, pointerId: event.pointerId)
        case .move:
            try injectMouseMove(at: point, pointerId: event.pointerId)
        case .cancel:
            try injectMouseUp(at: point, pointerId: event.pointerId)
        }
    }

    // Natural scrolling (content follows the fingers): a downward two-finger drag moves
    // the content down. macOS posted scroll-wheel deltas use the opposite sign, hence
    // -1. Flip a sign here if the on-device direction feels inverted.
    private static let verticalScrollSign: Double = -1
    private static let horizontalScrollSign: Double = -1

    /// Fractional scroll remainder carried between events. Pixel scroll deltas are
    /// integers, so accumulating the sub-pixel part keeps slow scrolling smooth (no lost
    /// motion / stair-stepping) instead of rounding each tiny delta to 0. Guarded by `lock`.
    private var scrollResidualVertical: Double = 0
    private var scrollResidualHorizontal: Double = 0

    /// Scaled (unrounded) pixel scroll deltas for a normalized delta:
    /// `sign * normalized * displayPointSize`. The tablet applies the user's scroll
    /// sensitivity to the delta before sending, so the Mac injects 1:1. Pure for testing;
    /// rounding and sub-pixel carry happen in `injectScroll`.
    static func scaledScrollPixels(deltaX: Float, deltaY: Float, displayBounds: CGRect) -> (vertical: Double, horizontal: Double) {
        let v = verticalScrollSign * Double(deltaY) * displayBounds.height
        let h = horizontalScrollSign * Double(deltaX) * displayBounds.width
        return (v, h)
    }

    private static func clampToInt32(_ value: Double) -> Int32 {
        guard value.isFinite else { return 0 }
        let clamped = min(max(value, Double(Int32.min)), Double(Int32.max))
        return Int32(clamped)
    }

    public func injectScroll(_ scroll: ScrollEvent, displayID: UInt32) async throws {
        guard AXIsProcessTrusted() else {
            throw ConnectionError.inputPermissionDenied
        }
        let displayBounds = CGDisplayBounds(displayID)
        guard displayBounds.width > 0, displayBounds.height > 0 else {
            throw ConnectionError.inputInjectionFailed
        }
        let (vScaled, hScaled) = Self.scaledScrollPixels(
            deltaX: scroll.deltaX,
            deltaY: scroll.deltaY,
            displayBounds: displayBounds
        )
        // Accumulate sub-pixel remainder, emit the integer part, keep the fraction.
        let (vertical, horizontal): (Int32, Int32) = lock.withLock {
            scrollResidualVertical += vScaled
            scrollResidualHorizontal += hScaled
            let v = scrollResidualVertical.rounded(.towardZero)
            let h = scrollResidualHorizontal.rounded(.towardZero)
            scrollResidualVertical -= v
            scrollResidualHorizontal -= h
            return (Self.clampToInt32(v), Self.clampToInt32(h))
        }
        // Nothing to scroll yet (accumulated delta still sub-pixel).
        guard vertical != 0 || horizontal != 0 else { return }
        guard let event = CGEvent(
            scrollWheelEvent2Source: nil,
            units: .pixel,
            wheelCount: 2,
            wheel1: vertical,   // vertical
            wheel2: horizontal, // horizontal
            wheel3: 0
        ) else {
            throw ConnectionError.inputInjectionFailed
        }
        event.post(tap: .cghidEventTap)
    }

    public func injectPointerButton(_ event: PointerButtonEvent, displayID: UInt32) async throws {
        guard AXIsProcessTrusted() else {
            throw ConnectionError.inputPermissionDenied
        }
        let displayBounds = CGDisplayBounds(displayID)
        guard displayBounds.width > 0, displayBounds.height > 0 else {
            throw ConnectionError.inputInjectionFailed
        }
        let point = Self.globalPoint(
            normalizedX: event.x,
            normalizedY: event.y,
            displayBounds: displayBounds
        )
        let (eventType, cgButton) = Self.mouseEvent(for: event.button, action: event.action)
        guard let cgEvent = CGEvent(
            mouseEventSource: nil,
            mouseType: eventType,
            mouseCursorPosition: point,
            mouseButton: cgButton
        ) else {
            throw ConnectionError.inputInjectionFailed
        }
        cgEvent.post(tap: .cghidEventTap)
    }

    /// Maps a protocol button + action onto the matching CGEvent mouse type and button.
    private static func mouseEvent(
        for button: PointerButtonEvent.Button,
        action: PointerButtonEvent.Action
    ) -> (CGEventType, CGMouseButton) {
        switch (button, action) {
        case (.left, .down): return (.leftMouseDown, .left)
        case (.left, .up): return (.leftMouseUp, .left)
        case (.right, .down): return (.rightMouseDown, .right)
        case (.right, .up): return (.rightMouseUp, .right)
        }
    }

    // MARK: - Private

    private func injectMouseDown(at point: CGPoint, pointerId: UInt8) throws {
        guard let event = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown,
                                   mouseCursorPosition: point, mouseButton: .left) else {
            throw ConnectionError.inputInjectionFailed
        }
        event.post(tap: .cghidEventTap)
        lock.withLock { _ = downPointers.insert(pointerId) }
    }

    private func injectMouseUp(at point: CGPoint, pointerId: UInt8) throws {
        guard let event = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp,
                                   mouseCursorPosition: point, mouseButton: .left) else {
            throw ConnectionError.inputInjectionFailed
        }
        event.post(tap: .cghidEventTap)
        lock.withLock { _ = downPointers.remove(pointerId) }
    }

    private func injectMouseMove(at point: CGPoint, pointerId: UInt8) throws {
        // A MOVE is a drag if THIS pointer is currently down; otherwise a hover.
        let isDown = lock.withLock { downPointers.contains(pointerId) }
        let eventType: CGEventType = isDown ? .leftMouseDragged : .mouseMoved

        guard let event = CGEvent(mouseEventSource: nil, mouseType: eventType,
                                   mouseCursorPosition: point, mouseButton: .left) else {
            throw ConnectionError.inputInjectionFailed
        }
        event.post(tap: .cghidEventTap)
    }
}
