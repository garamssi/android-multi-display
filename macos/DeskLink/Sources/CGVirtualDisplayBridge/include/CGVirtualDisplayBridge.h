#ifndef CGVirtualDisplayBridge_h
#define CGVirtualDisplayBridge_h

#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

@interface VirtualDisplayBridge : NSObject

@property (nonatomic, readonly) CGDirectDisplayID displayID;

@property (nonatomic, readonly) BOOL isActive;

- (BOOL)createDisplayWithWidth:(NSUInteger)width
                        height:(NSUInteger)height
                           ppi:(NSUInteger)ppi
                          name:(NSString *)name
                         error:(NSError * _Nullable * _Nullable)error;

- (void)destroyDisplay;

- (BOOL)updateResolutionWithWidth:(NSUInteger)width
                           height:(NSUInteger)height
                            error:(NSError * _Nullable * _Nullable)error;

@end

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
