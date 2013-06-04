#import "ImageEncoder.h"

@implementation ImageEncoder

- (void)encode:(CMSampleBufferRef)frame withPreflight:(bool (^)(CGImageRef))preflight andSuccess:(void (^)(NSData *))success {
	// TODO: complete me
	success([@"FU" dataUsingEncoding:NSASCIIStringEncoding]);
}

- (void)encode:(CMSampleBufferRef)frame withSuccess:(void (^)(NSData *))success {
	return [self encode:frame withPreflight:nil andSuccess:success];
}

@end
