#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <objc/runtime.h>
#import <os/log.h>

// Siri/Intents compatibility shim for apps running without
// com.apple.developer.siri (for example inside LiveContainer).
//
// Covers two common crash paths:
//   1. INVocabulary      - used by WhatsApp and other apps
//   2. INPreferences     - used by WeChat/Telegram and other apps
//
// Instead of pretending Siri is available, this tweak reports Siri as denied
// and swallows vocabulary writes. This keeps the host app alive while leaving
// Siri-related functionality disabled.

typedef NS_ENUM(NSInteger, INSiriAuthorizationStatus) {
    INSiriAuthorizationStatusDenied = 2,
};

@interface MyDummyVocabulary : NSObject
@end

@implementation MyDummyVocabulary

- (void)setVocabulary:(NSSet *)vocabulary ofType:(NSInteger)type {
    NSLog(@"[DisableSiriEntitlement] swallowed INVocabulary setVocabulary:ofType: (%ld)",
                (long)type);
}

- (NSMethodSignature *)methodSignatureForSelector:(SEL)aSelector {
    return [NSMethodSignature signatureWithObjCTypes:"v@:"];
}

- (void)forwardInvocation:(NSInvocation *)anInvocation {
    NSLog(@"[DisableSiriEntitlement] swallowed INVocabulary selector: %s",
                sel_getName(anInvocation.selector));
}

@end

static void installINVocabularyHook(void) {
    Class vocabularyClass = objc_getClass("INVocabulary");
    if (!vocabularyClass) {
        NSLog(@"[DisableSiriEntitlement] INVocabulary class not found");
        return;
    }

    id (^sharedVocabularyBlock)(id) = ^id(id self) {
        static MyDummyVocabulary *dummy = nil;
        static dispatch_once_t onceToken;
        dispatch_once(&onceToken, ^{
            dummy = [[MyDummyVocabulary alloc] init];
        });
        return dummy;
    };

    SEL selector = sel_registerName("sharedVocabulary");
    IMP implementation = imp_implementationWithBlock(sharedVocabularyBlock);
    class_replaceMethod(object_getClass(vocabularyClass), selector,
                        implementation, "@@:");

    NSLog(@"[DisableSiriEntitlement] INVocabulary hook installed");
}

static void installINPreferencesHooks(void) {
    Class preferencesClass = objc_getClass("INPreferences");
    if (!preferencesClass) {
        NSLog(@"[DisableSiriEntitlement] INPreferences class not found");
        return;
    }

    // +siriAuthorizationStatus -> Denied.
    // Returning Denied avoids the entitlement-protected code path.
    NSInteger (^statusBlock)(id) = ^NSInteger(id self) {
        NSLog(@"[DisableSiriEntitlement] siriAuthorizationStatus -> Denied");
        return INSiriAuthorizationStatusDenied;
    };

    SEL statusSelector = sel_registerName("siriAuthorizationStatus");
    IMP statusImplementation = imp_implementationWithBlock(statusBlock);
    class_replaceMethod(object_getClass(preferencesClass), statusSelector,
                        statusImplementation, "q@:");

    // +requestSiriAuthorization: -> invoke completion immediately with Denied.
    void (^requestBlock)(id, void (^)(NSInteger)) =
        ^(id self, void (^handler)(NSInteger)) {
            NSLog(@"[DisableSiriEntitlement] requestSiriAuthorization: -> Denied");
            if (handler) {
                handler(INSiriAuthorizationStatusDenied);
            }
        };

    SEL requestSelector = sel_registerName("requestSiriAuthorization:");
    IMP requestImplementation = imp_implementationWithBlock(requestBlock);
    class_replaceMethod(object_getClass(preferencesClass), requestSelector,
                        requestImplementation, "v@:@");

    NSLog(@"[DisableSiriEntitlement] INPreferences hooks installed");
}

void init() {
    installINVocabularyHook();
    installINPreferencesHooks();
}
