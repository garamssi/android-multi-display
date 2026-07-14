import Foundation
import CoreGraphics
import ApplicationServices

// macOS has no public multi-touch injection API; all pointers map onto the single system cursor. Per-pointer down tracking keeps each pointer's MOVE classified as drag vs hover.
public final class CGEventInjector: InputReceiving, @unchecked Sendable {
    private let lock = NSLock()

    private var downPointers: Set<UInt8> = []

    public init() {}

    public func startReceiving(port: UInt16) -> AsyncThrowingStream<TouchEvent, Error> {
        // Stub: input arrives via TCPServer + TouchDeserializer; empty stream satisfies the protocol.
        AsyncThrowingStream<TouchEvent, Error>(bufferingPolicy: .bufferingNewest(60)) { _ in }
    }

    public func stopReceiving() async {
        lock.withLock {
            downPointers.removeAll()
        }
    }

    public static func isTrusted() -> Bool {
        AXIsProcessTrusted()
    }

    // Prompting (unlike the silent AXIsProcessTrusted check) also registers the app in the Accessibility list; without it the app never appears there and input stays blocked.
    @discardableResult
    public static func requestAccessibility() -> Bool {
        // String-literal key avoids CFString/Unmanaged bridging differences across SDKs (vs kAXTrustedCheckOptionPrompt).
        let options = ["AXTrustedCheckOptionPrompt": true] as CFDictionary
        return AXIsProcessTrustedWithOptions(options)
    }

    // Scale by point size (CGDisplayBounds), NOT pixel resolution, so mapping is correct on HiDPI/Retina and multi-display layouts.
    static func globalPoint(normalizedX: Float, normalizedY: Float, displayBounds: CGRect) -> CGPoint {
        let clampedX = min(max(Double(normalizedX), 0), 1)
        let clampedY = min(max(Double(normalizedY), 0), 1)
        return CGPoint(
            x: displayBounds.origin.x + clampedX * displayBounds.width,
            y: displayBounds.origin.y + clampedY * displayBounds.height
        )
    }

    public func injectEvent(_ event: TouchEvent, displayID: UInt32) async throws {
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

    // macOS posted scroll-wheel deltas use the opposite sign to natural scrolling, hence -1.
    private static let verticalScrollSign: Double = -1
    private static let horizontalScrollSign: Double = -1

    // Sub-pixel scroll remainder carried between events so slow scrolling isn't rounded to 0 (integer pixel deltas). Guarded by `lock`.
    private var scrollResidualVertical: Double = 0
    private var scrollResidualHorizontal: Double = 0

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
        let (vertical, horizontal): (Int32, Int32) = lock.withLock {
            scrollResidualVertical += vScaled
            scrollResidualHorizontal += hScaled
            let v = scrollResidualVertical.rounded(.towardZero)
            let h = scrollResidualHorizontal.rounded(.towardZero)
            scrollResidualVertical -= v
            scrollResidualHorizontal -= h
            return (Self.clampToInt32(v), Self.clampToInt32(h))
        }
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
        let isDown = lock.withLock { downPointers.contains(pointerId) }
        let eventType: CGEventType = isDown ? .leftMouseDragged : .mouseMoved

        guard let event = CGEvent(mouseEventSource: nil, mouseType: eventType,
                                   mouseCursorPosition: point, mouseButton: .left) else {
            throw ConnectionError.inputInjectionFailed
        }
        event.post(tap: .cghidEventTap)
    }
}
