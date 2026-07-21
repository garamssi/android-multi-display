#ifndef CGVirtualDisplayBridge_h
#define CGVirtualDisplayBridge_h

#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

/// Wrapper around macOS private CGVirtualDisplay API.
/// CGVirtualDisplay is a private framework available since macOS 13+.
/// This bridge provides a stable Objective-C interface for Swift consumption.

@interface VirtualDisplayBridge : NSObject

/// The CGDirectDisplayID of the created virtual display, or 0 if none.
@property (nonatomic, readonly) CGDirectDisplayID displayID;

/// Whether a virtual display is currently active.
@property (nonatomic, readonly) BOOL isActive;

/// Creates a virtual display with the given parameters.
/// @param width Display width in pixels
/// @param height Display height in pixels
/// @param ppi Pixels per inch (default: 220 for Retina)
/// @param name Display name shown in System Settings
/// @param error Error output if creation fails
/// @return YES if successful
- (BOOL)createDisplayWithWidth:(NSUInteger)width
                        height:(NSUInteger)height
                           ppi:(NSUInteger)ppi
                          name:(NSString *)name
                         error:(NSError * _Nullable * _Nullable)error;

/// Destroys the virtual display if one exists.
- (void)destroyDisplay;

/// Updates the resolution of the existing virtual display.
/// Requires destroying and recreating the display.
/// @return YES if successful
- (BOOL)updateResolutionWithWidth:(NSUInteger)width
                           height:(NSUInteger)height
                            error:(NSError * _Nullable * _Nullable)error;

@end

/// Error domain for VirtualDisplayBridge errors
extern NSErrorDomain const VirtualDisplayBridgeErrorDomain;

typedef NS_ERROR_ENUM(VirtualDisplayBridgeErrorDomain, VirtualDisplayBridgeError) {
    VirtualDisplayBridgeErrorAPINotAvailable = 1,
    VirtualDisplayBridgeErrorCreationFailed = 2,
    VirtualDisplayBridgeErrorAlreadyActive = 3,
    VirtualDisplayBridgeErrorNotActive = 4,
    VirtualDisplayBridgeErrorInvalidResolution = 5,
};

NS_ASSUME_NONNULL_END

#endif /* CGVirtualDisplayBridge_h */
