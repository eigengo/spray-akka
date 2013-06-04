#import <Foundation/Foundation.h>
#import <CoreMedia/CoreMedia.h>

@interface ImageEncoder : NSObject

- (void)encode:(CMSampleBufferRef)frame withSuccess:(void (^)(NSData*))success;
- (void)encode:(CMSampleBufferRef)frame withPreflight:(bool (^)(CGImageRef))preflight andSuccess:(void (^)(NSData*))success;

@end
