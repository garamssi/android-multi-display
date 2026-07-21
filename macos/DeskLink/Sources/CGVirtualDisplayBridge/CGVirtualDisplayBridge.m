#import "CGVirtualDisplayBridge.h"
#import <objc/runtime.h>
#import <objc/message.h>
#import <unistd.h>

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

/// Builds an NSError in the bridge's domain (keeps the failure paths terse).
- (NSError *)bridgeErrorWithCode:(VirtualDisplayBridgeError)code message:(NSString *)message {
    return [NSError errorWithDomain:VirtualDisplayBridgeErrorDomain
                               code:code
                           userInfo:@{NSLocalizedDescriptionKey: message}];
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
    // hiDPI is deliberately OFF. With hiDPI the mode's width/height are treated as
    // POINTS and the backing store becomes 2x, so a WxH mode would be captured at
    // 2Wx2H and no longer match the tablet's advertised native pixels. The capturer
    // adopts the display's actual pixel size and streams it 1:1 to the tablet, so the
    // virtual display's pixel size must equal the requested resolution exactly.
    // hiDPI=0 makes points == pixels == the requested WxH (== maxPixelsWide/High).
    // This is the root cause of the historical 1280x800 fallback: with hiDPI on and
    // maxPixels set to the logical size, the mode was rejected and the display fell
    // back to a default mode; the previous code discarded the -applySettings: result
    // and swallowed the exception, so the wrong resolution was streamed silently.
    Class modeClass = NSClassFromString(@"CGVirtualDisplayMode");
    Class settingsClass = NSClassFromString(@"CGVirtualDisplaySettings");
    if (!modeClass || !settingsClass) {
        if (error) {
            *error = [self bridgeErrorWithCode:VirtualDisplayBridgeErrorAPINotAvailable
                                       message:@"CGVirtualDisplayMode/Settings not available on this macOS version"];
        }
        [self destroyDisplay];
        return NO;
    }

    @try {
        id<DLKVirtualDisplayMode> mode =
            [(id<DLKVirtualDisplayMode>)[modeClass alloc] initWithWidth:(uint32_t)width
                                                                 height:(uint32_t)height
                                                            refreshRate:60.0];
        id settings = [[settingsClass alloc] init];
        [settings setValue:@[mode] forKey:@"modes"];
        [self trySetValue:@(0) forKey:@"hiDPI" on:settings];

        id<DLKVirtualDisplayApply> applier = (id<DLKVirtualDisplayApply>)_virtualDisplay;
        if (![applier respondsToSelector:@selector(applySettings:)]) {
            if (error) {
                *error = [self bridgeErrorWithCode:VirtualDisplayBridgeErrorAPINotAvailable
                                           message:@"CGVirtualDisplay does not respond to applySettings: on this macOS version"];
            }
            [self destroyDisplay];
            return NO;
        }
        if (![applier applySettings:settings]) {
            if (error) {
                *error = [self bridgeErrorWithCode:VirtualDisplayBridgeErrorSettingsApplyFailed
                                           message:[NSString stringWithFormat:@"applySettings: rejected mode %lux%lu",
                                                    (unsigned long)width, (unsigned long)height]];
            }
            [self destroyDisplay];
            return NO;
        }
    } @catch (NSException *ex) {
        if (error) {
            *error = [self bridgeErrorWithCode:VirtualDisplayBridgeErrorSettingsApplyFailed
                                       message:[NSString stringWithFormat:@"Applying virtual display settings failed: %@",
                                                ex.reason ?: ex.name]];
        }
        [self destroyDisplay];
        return NO;
    }

    // The display must have a valid CGDirectDisplayID; without one the capturer has
    // nothing to attach to. A missing/zero ID means creation did not really succeed.
    NSNumber *displayIDValue = [_virtualDisplay valueForKey:@"displayID"];
    if (!displayIDValue || [displayIDValue unsignedIntValue] == 0) {
        if (error) {
            *error = [self bridgeErrorWithCode:VirtualDisplayBridgeErrorCreationFailed
                                       message:@"Virtual display has no valid display ID"];
        }
        [self destroyDisplay];
        return NO;
    }
    _displayID = [displayIDValue unsignedIntValue];

    // Read back the active mode and confirm the display really is at the requested
    // pixel size. The virtual display is registered with CoreGraphics asynchronously
    // (the descriptor carries a dispatch queue), so the active mode may not reflect the
    // applied settings the instant -applySettings: returns. Poll — bounded — until it
    // matches. This distinguishes "not yet settled" from a genuine fallback
    // substitution (the 1280x800 bug): a persistent mismatch after the ceiling is a
    // real error, while a match short-circuits immediately. The bound keeps this from
    // hiding a logic bug — it only waits out the CG registration latency.
    static const useconds_t kModeSettlePollIntervalUs = 25 * 1000; // 25 ms
    static const int kModeSettleMaxAttempts = 80;                  // ~2 s ceiling

    size_t actualWidth = 0;
    size_t actualHeight = 0;
    BOOL resolutionMatches = NO;
    for (int attempt = 0; attempt < kModeSettleMaxAttempts; attempt++) {
        CGDisplayModeRef activeMode = CGDisplayCopyDisplayMode(_displayID);
        if (activeMode) {
            actualWidth = CGDisplayModeGetPixelWidth(activeMode);
            actualHeight = CGDisplayModeGetPixelHeight(activeMode);
            CGDisplayModeRelease(activeMode);
            if (actualWidth == width && actualHeight == height) {
                resolutionMatches = YES;
                break;
            }
        }
        usleep(kModeSettlePollIntervalUs);
    }

    NSLog(@"DeskLink: virtual display requested %lux%lu, active %zux%zu",
          (unsigned long)width, (unsigned long)height, actualWidth, actualHeight);

    if (!resolutionMatches) {
        if (error) {
            *error = [self bridgeErrorWithCode:VirtualDisplayBridgeErrorResolutionMismatch
                                       message:[NSString stringWithFormat:
                                                @"Requested %lux%lu but display settled at %zux%zu",
                                                (unsigned long)width, (unsigned long)height,
                                                actualWidth, actualHeight]];
        }
        [self destroyDisplay];
        return NO;
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
