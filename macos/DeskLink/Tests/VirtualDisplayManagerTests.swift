import XCTest
@testable import DeskLink

final class VirtualDisplayManagerTests: XCTestCase {

    func testInitialStateIsInactive() async {
        let manager = VirtualDisplayManager()
        let isActive = await manager.isDisplayActive
        XCTAssertFalse(isActive)
    }

    func testDisplayIDIsZeroWhenInactive() {
        let manager = VirtualDisplayManager()
        XCTAssertEqual(manager.displayID, 0)
    }

    func testDestroyDisplayWhenNotActiveDoesNotCrash() async {
        let manager = VirtualDisplayManager()
        // Should not throw or crash
        await manager.destroyDisplay()
    }
}
