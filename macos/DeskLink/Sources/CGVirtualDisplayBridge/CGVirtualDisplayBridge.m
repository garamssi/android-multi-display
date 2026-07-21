#import "CGVirtualDisplayBridge.h"
#import <objc/runtime.h>
#import <objc/message.h>

NSErrorDomain const VirtualDisplayBridgeErrorDomain = @"com.desklink.VirtualDisplayBridge";

// Typed declaration of the private CGVirtualDisplay initializer. Casting the
// allocated instance to this protocol lets ARC treat -initWithDescriptor: as a
// real init-family method with correct ownership, so we call it directly instead
// of via dynamic performSelector:. This removes the root cause of the ARC
// "performSelector may cause a leak" warning rather than suppressing it.
@protocol DLKVirtualDisplayInit <NSObject>
- (instancetype)initWithDescriptor:(id)descriptor;
@end

// The private CGVirtualDisplayMode initializer takes primitive args, so a typed
// declaration is required (performSelector: cannot pass scalars).
@protocol DLKVirtualDisplayMode <NSObject>
- (instancetype)initWithWidth:(uint32_t)width height:(uint32_t)height refreshRate:(double)refreshRate;
@end

// The private -applySettings: on CGVirtualDisplay.
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

/// Sets a KVC value, ignoring the exception if the key is absent on this macOS
/// version (private-API property names can vary between releases).
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

    // Load CGVirtualDisplayDescriptor class (private API)
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

    // Load CGVirtualDisplay class (private API)
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

    // Create descriptor
    _displayDescriptor = [[descriptorClass alloc] init];

    // Configure the descriptor. These are private-API KVC properties; the correct
    // keys are name / maxPixelsWide / maxPixelsHigh / sizeInMillimeters / queue.
    // (The actual resolution is applied afterwards via a CGVirtualDisplayMode.)
    // Wrapped in @try so an unknown key on a given macOS version returns an NSError
    // instead of terminating the app with an uncaught NSException.
    @try {
        dispatch_queue_t queue = dispatch_queue_create("com.desklink.virtualdisplay", DISPATCH_QUEUE_SERIAL);
        [_displayDescriptor setValue:(name ?: @"DeskLink Display") forKey:@"name"];
        [_displayDescriptor setValue:@(width) forKey:@"maxPixelsWide"];
        [_displayDescriptor setValue:@(height) forKey:@"maxPixelsHigh"];
        [_displayDescriptor setValue:queue forKey:@"queue"];

        // Physical size in millimeters (from pixels and PPI; 1 inch = 25.4mm).
        double widthMM = (double)width / (double)ppi * 25.4;
        double heightMM = (double)height / (double)ppi * 25.4;
        [_displayDescriptor setValue:[NSValue valueWithSize:NSMakeSize(widthMM, heightMM)]
                              forKey:@"sizeInMillimeters"];

        // Best-effort identifiers (ignored if the key is absent on this version).
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

    // Create the virtual display via the private -initWithDescriptor:. Casting the
    // allocated instance to DLKVirtualDisplayInit gives ARC a real init-family
    // signature (correct ownership), so no performSelector: is needed.
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

    // Apply a display mode so the virtual display runs at the REQUESTED pixel
    // resolution. On the private API the resolution lives in a CGVirtualDisplayMode
    // inside a CGVirtualDisplaySettings, applied via -applySettings:.
    //
    // hiDPI is set OFF: with hiDPI the mode's width/height are treated as POINTS and the
    // backing store becomes 2x, so a WxH mode would be captured at 2Wx2H and no longer
    // match the tablet's advertised native pixels. The capturer adopts the display's
    // actual pixel size and streams it 1:1 to the tablet, so the virtual display's pixel
    // size should equal the requested resolution exactly; hiDPI=0 makes points == pixels
    // == the requested WxH. Whether this actually resolves the historical 1280x800
    // fallback is confirmed by the requested-vs-active log below (private-API behavior
    // cannot be verified at build time).
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
        // Non-fatal on failure: if the mode is rejected the display keeps a default mode,
        // which is worse than the request but far better than tearing down the whole
        // connection. The actual resolution is logged below (and by the Swift layer) so a
        // fallback is visible and can be fixed at its source rather than hidden.
        if (![applier respondsToSelector:@selector(applySettings:)]) {
            NSLog(@"DeskLink: CGVirtualDisplay has no applySettings: on this macOS version; using default mode");
        } else if (![applier applySettings:settings]) {
            NSLog(@"DeskLink: applySettings: returned NO for %lux%lu (continuing with default mode)",
                  (unsigned long)width, (unsigned long)height);
        }
    } @catch (NSException *ex) {
        NSLog(@"DeskLink: applying virtual display settings threw: %@ (continuing)", ex.reason ?: ex.name);
    }

    // Display ID (the capturer attaches to it). Non-fatal if absent, but logged.
    NSNumber *displayIDValue = [_virtualDisplay valueForKey:@"displayID"];
    _displayID = displayIDValue ? [displayIDValue unsignedIntValue] : 0;
    if (_displayID == 0) {
        NSLog(@"DeskLink: virtual display has no valid display ID");
    }

    // Diagnostic: log requested vs active pixel size. A mismatch means the private API
    // substituted a fallback mode (the 1280x800 bug). Surfaced here (and by the Swift
    // layer via Log.stream) so it is visible without breaking the connection.
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
