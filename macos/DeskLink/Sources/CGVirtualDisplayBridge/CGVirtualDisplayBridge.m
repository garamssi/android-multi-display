#import "CGVirtualDisplayBridge.h"
#import <objc/runtime.h>
#import <objc/message.h>

NSErrorDomain const VirtualDisplayBridgeErrorDomain = @"com.desklink.VirtualDisplayBridge";

// Typed init-family declaration so ARC gives -initWithDescriptor: correct ownership (avoids performSelector and its ARC leak warning).
@protocol DLKVirtualDisplayInit <NSObject>
- (instancetype)initWithDescriptor:(id)descriptor;
@end

// Typed declaration required: this initializer takes scalars, which performSelector: cannot pass.
@protocol DLKVirtualDisplayMode <NSObject>
- (instancetype)initWithWidth:(uint32_t)width height:(uint32_t)height refreshRate:(double)refreshRate;
@end

@protocol DLKVirtualDisplayApply <NSObject>
- (BOOL)applySettings:(id)settings;
@end

@implementation VirtualDisplayBridge {
    id _virtualDisplay;
    id _displayDescriptor;
    NSString *_displayName;
    NSUInteger _currentPPI;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _displayID = 0;
        _isActive = NO;
        _currentPPI = 220;
    }
    return self;
}

- (void)dealloc {
    [self destroyDisplay];
}

// KVC set that ignores an absent key: private-API property names vary between macOS versions.
- (void)trySetValue:(id)value forKey:(NSString *)key on:(id)object {
    @try {
        [object setValue:value forKey:key];
    } @catch (NSException *exception) {
        // Optional key not present on this version; ignore.
    }
}

- (BOOL)createDisplayWithWidth:(NSUInteger)width
                        height:(NSUInteger)height
                           ppi:(NSUInteger)ppi
                          name:(NSString *)name
                         error:(NSError **)error {
    if (_isActive) {
        if (error) {
            *error = [NSError errorWithDomain:VirtualDisplayBridgeErrorDomain
                                         code:VirtualDisplayBridgeErrorAlreadyActive
                                     userInfo:@{NSLocalizedDescriptionKey: @"Virtual display is already active"}];
        }
        return NO;
    }

    if (width == 0 || height == 0 || width > 7680 || height > 4320) {
        if (error) {
            *error = [NSError errorWithDomain:VirtualDisplayBridgeErrorDomain
                                         code:VirtualDisplayBridgeErrorInvalidResolution
                                     userInfo:@{NSLocalizedDescriptionKey:
                                         [NSString stringWithFormat:@"Invalid resolution: %lux%lu", (unsigned long)width, (unsigned long)height]}];
        }
        return NO;
    }

    Class descriptorClass = NSClassFromString(@"CGVirtualDisplayDescriptor");
    if (!descriptorClass) {
        if (error) {
            *error = [NSError errorWithDomain:VirtualDisplayBridgeErrorDomain
                                         code:VirtualDisplayBridgeErrorAPINotAvailable
                                     userInfo:@{NSLocalizedDescriptionKey:
                                         @"CGVirtualDisplayDescriptor not available on this macOS version"}];
        }
        return NO;
    }

    Class displayClass = NSClassFromString(@"CGVirtualDisplay");
    if (!displayClass) {
        if (error) {
            *error = [NSError errorWithDomain:VirtualDisplayBridgeErrorDomain
                                         code:VirtualDisplayBridgeErrorAPINotAvailable
                                     userInfo:@{NSLocalizedDescriptionKey:
                                         @"CGVirtualDisplay not available on this macOS version"}];
        }
        return NO;
    }

    _displayDescriptor = [[descriptorClass alloc] init];

    // Private-API KVC keys; @try so an unknown key on some macOS version returns an NSError instead of crashing.
    @try {
        dispatch_queue_t queue = dispatch_queue_create("com.desklink.virtualdisplay", DISPATCH_QUEUE_SERIAL);
        [_displayDescriptor setValue:(name ?: @"DeskLink Display") forKey:@"name"];
        [_displayDescriptor setValue:@(width) forKey:@"maxPixelsWide"];
        [_displayDescriptor setValue:@(height) forKey:@"maxPixelsHigh"];
        [_displayDescriptor setValue:queue forKey:@"queue"];

        double widthMM = (double)width / (double)ppi * 25.4;
        double heightMM = (double)height / (double)ppi * 25.4;
        [_displayDescriptor setValue:[NSValue valueWithSize:NSMakeSize(widthMM, heightMM)]
                              forKey:@"sizeInMillimeters"];

        [self trySetValue:@(0x444C) forKey:@"productID" on:_displayDescriptor];
        [self trySetValue:@(0x444B) forKey:@"vendorID" on:_displayDescriptor];
        [self trySetValue:@(0x0001) forKey:@"serialNum" on:_displayDescriptor];
    } @catch (NSException *ex) {
        _displayDescriptor = nil;
        if (error) {
            *error = [NSError errorWithDomain:VirtualDisplayBridgeErrorDomain
                                         code:VirtualDisplayBridgeErrorCreationFailed
                                     userInfo:@{NSLocalizedDescriptionKey:
                                         [NSString stringWithFormat:@"Descriptor configuration failed: %@",
                                          ex.reason ?: ex.name]}];
        }
        return NO;
    }

    id<DLKVirtualDisplayInit> allocated = [displayClass alloc];
    _virtualDisplay = [allocated initWithDescriptor:_displayDescriptor];

    if (!_virtualDisplay) {
        if (error) {
            *error = [NSError errorWithDomain:VirtualDisplayBridgeErrorDomain
                                         code:VirtualDisplayBridgeErrorCreationFailed
                                     userInfo:@{NSLocalizedDescriptionKey: @"Failed to create CGVirtualDisplay"}];
        }
        _displayDescriptor = nil;
        return NO;
    }

    // hiDPI must be OFF: with hiDPI the mode dims are points and the backing store doubles, so WxH would be captured at 2Wx2H and mismatch the tablet's native pixels.
    Class modeClass = NSClassFromString(@"CGVirtualDisplayMode");
    Class settingsClass = NSClassFromString(@"CGVirtualDisplaySettings");
    if (!modeClass || !settingsClass) {
        // Non-fatal: the display keeps a default mode. Logged so it is visible.
        NSLog(@"DeskLink: CGVirtualDisplayMode/Settings not available; using default mode");
    } else @try {
        id<DLKVirtualDisplayMode> mode =
            [(id<DLKVirtualDisplayMode>)[modeClass alloc] initWithWidth:(uint32_t)width
                                                                 height:(uint32_t)height
                                                            refreshRate:60.0];
        id settings = [[settingsClass alloc] init];
        [settings setValue:@[mode] forKey:@"modes"];
        [self trySetValue:@(0) forKey:@"hiDPI" on:settings];

        id<DLKVirtualDisplayApply> applier = (id<DLKVirtualDisplayApply>)_virtualDisplay;
        // Non-fatal on failure: a rejected mode keeps the default mode (logged below) rather than tearing down the connection.
        if (![applier respondsToSelector:@selector(applySettings:)]) {
            NSLog(@"DeskLink: CGVirtualDisplay has no applySettings: on this macOS version; using default mode");
        } else if (![applier applySettings:settings]) {
            NSLog(@"DeskLink: applySettings: returned NO for %lux%lu (continuing with default mode)",
                  (unsigned long)width, (unsigned long)height);
        }
    } @catch (NSException *ex) {
        NSLog(@"DeskLink: applying virtual display settings threw: %@ (continuing)", ex.reason ?: ex.name);
    }

    NSNumber *displayIDValue = [_virtualDisplay valueForKey:@"displayID"];
    _displayID = displayIDValue ? [displayIDValue unsignedIntValue] : 0;
    if (_displayID == 0) {
        NSLog(@"DeskLink: virtual display has no valid display ID");
    }

    if (_displayID != 0) {
        CGDisplayModeRef activeMode = CGDisplayCopyDisplayMode(_displayID);
        if (activeMode) {
            size_t actualWidth = CGDisplayModeGetPixelWidth(activeMode);
            size_t actualHeight = CGDisplayModeGetPixelHeight(activeMode);
            CGDisplayModeRelease(activeMode);
            NSLog(@"DeskLink: virtual display requested %lux%lu, active %zux%zu",
                  (unsigned long)width, (unsigned long)height, actualWidth, actualHeight);
        }
    }

    _displayName = name ?: @"DeskLink Display";
    _currentPPI = ppi;
    _isActive = YES;

    return YES;
}

- (void)destroyDisplay {
    if (_virtualDisplay) {
        _virtualDisplay = nil;
    }
    if (_displayDescriptor) {
        _displayDescriptor = nil;
    }
    _displayID = 0;
    _isActive = NO;
}

- (BOOL)updateResolutionWithWidth:(NSUInteger)width
                           height:(NSUInteger)height
                            error:(NSError **)error {
    if (!_isActive) {
        if (error) {
            *error = [NSError errorWithDomain:VirtualDisplayBridgeErrorDomain
                                         code:VirtualDisplayBridgeErrorNotActive
                                     userInfo:@{NSLocalizedDescriptionKey: @"No virtual display is active"}];
        }
        return NO;
    }

    // Must recreate to change resolution
    NSString *name = _displayName;
    NSUInteger ppi = _currentPPI;
    [self destroyDisplay];
    return [self createDisplayWithWidth:width height:height ppi:ppi name:name error:error];
}

@end
