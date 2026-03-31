#import "CGVirtualDisplayBridge.h"
#import <objc/runtime.h>
#import <objc/message.h>

NSErrorDomain const VirtualDisplayBridgeErrorDomain = @"com.desklink.VirtualDisplayBridge";

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

    // Configure descriptor using KVC (since these are private API properties)
    dispatch_queue_t queue = dispatch_queue_create("com.desklink.virtualdisplay", DISPATCH_QUEUE_SERIAL);
    [_displayDescriptor setValue:@(width) forKey:@"width"];
    [_displayDescriptor setValue:@(height) forKey:@"height"];
    [_displayDescriptor setValue:@(ppi) forKey:@"pixelsPerInch"];
    [_displayDescriptor setValue:queue forKey:@"queue"];
    [_displayDescriptor setValue:(name ?: @"DeskLink Display") forKey:@"name"];

    // Set display size in millimeters (calculated from pixels and PPI)
    // 1 inch = 25.4mm
    double widthMM = (double)width / (double)ppi * 25.4;
    double heightMM = (double)height / (double)ppi * 25.4;
    NSSize sizeInMillimeters = NSMakeSize(widthMM, heightMM);
    [_displayDescriptor setValue:[NSValue valueWithSize:sizeInMillimeters] forKey:@"sizeInMillimeters"];

    // Create virtual display
    SEL initSel = NSSelectorFromString(@"initWithDescriptor:");
    _virtualDisplay = [[displayClass alloc] performSelector:initSel withObject:_displayDescriptor];

    if (!_virtualDisplay) {
        if (error) {
            *error = [NSError errorWithDomain:VirtualDisplayBridgeErrorDomain
                                         code:VirtualDisplayBridgeErrorCreationFailed
                                     userInfo:@{NSLocalizedDescriptionKey: @"Failed to create CGVirtualDisplay"}];
        }
        _displayDescriptor = nil;
        return NO;
    }

    // Get the display ID
    NSNumber *displayIDValue = [_virtualDisplay valueForKey:@"displayID"];
    if (displayIDValue) {
        _displayID = [displayIDValue unsignedIntValue];
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
