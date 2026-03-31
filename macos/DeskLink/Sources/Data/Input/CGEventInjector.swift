import Foundation
import CoreGraphics

/// Injects touch events as mouse events via CGEvent API.
/// Maps normalized coordinates (0-1) to the virtual display's pixel space.
public final class CGEventInjector: InputReceiving, @unchecked Sendable {
    private let lock = NSLock()
    private var displayID: UInt32 = 0
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var isMouseDown = false

    public init() {}

    public func startReceiving(port: UInt16) -> AsyncThrowingStream<TouchEvent, Error> {
        // Input receiving is handled by TCPServer + TouchDeserializer
        // This protocol method returns a stream for the use case layer
        AsyncThrowingStream<TouchEvent, Error>(bufferingPolicy: .bufferingNewest(60)) { _ in
            // Stream will be fed externally via the network layer
        }
    }

    public func stopReceiving() async {
        lock.withLock {
            isMouseDown = false
        }
    }

    /// Configures the injector for a specific virtual display.
    public func configure(displayID: UInt32, width: Int, height: Int) {
        lock.withLock {
            self.displayID = displayID
            self.displayWidth = width
            self.displayHeight = height
        }
    }

    public func injectEvent(_ event: TouchEvent, displayID: UInt32) async throws {
        let (width, height) = lock.withLock { (displayWidth, displayHeight) }
        guard width > 0, height > 0 else {
            throw ConnectionError.inputInjectionFailed
        }

        // Convert normalized coordinates to display pixels
        let pixelX = Double(event.x) * Double(width)
        let pixelY = Double(event.y) * Double(height)

        // Get the virtual display's origin in global coordinates
        let displayBounds = CGDisplayBounds(displayID)
        let globalX = displayBounds.origin.x + pixelX
        let globalY = displayBounds.origin.y + pixelY

        let point = CGPoint(x: globalX, y: globalY)

        switch event.action {
        case .down:
            try injectMouseDown(at: point)
        case .up:
            try injectMouseUp(at: point)
        case .move:
            try injectMouseMove(at: point)
        case .cancel:
            try injectMouseUp(at: point)
        }
    }

    // MARK: - Private

    private func injectMouseDown(at point: CGPoint) throws {
        guard let event = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown,
                                   mouseCursorPosition: point, mouseButton: .left) else {
            throw ConnectionError.inputInjectionFailed
        }
        event.post(tap: .cghidEventTap)
        lock.withLock { isMouseDown = true }
    }

    private func injectMouseUp(at point: CGPoint) throws {
        guard let event = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp,
                                   mouseCursorPosition: point, mouseButton: .left) else {
            throw ConnectionError.inputInjectionFailed
        }
        event.post(tap: .cghidEventTap)
        lock.withLock { isMouseDown = false }
    }

    private func injectMouseMove(at point: CGPoint) throws {
        let mouseDown = lock.withLock { isMouseDown }
        let eventType: CGEventType = mouseDown ? .leftMouseDragged : .mouseMoved

        guard let event = CGEvent(mouseEventSource: nil, mouseType: eventType,
                                   mouseCursorPosition: point, mouseButton: .left) else {
            throw ConnectionError.inputInjectionFailed
        }
        event.post(tap: .cghidEventTap)
    }
}
