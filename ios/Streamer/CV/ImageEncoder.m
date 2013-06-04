#import "ImageEncoder.h"

@implementation ImageEncoder

- (UIImage *)imageWithImage:(UIImage *)image scaledToSize:(CGSize)newSize {
	UIGraphicsBeginImageContextWithOptions(newSize, NO, 0.0);
	[image drawInRect:CGRectMake(0, 0, newSize.width, newSize.height)];
	UIImage *newImage = UIGraphicsGetImageFromCurrentImageContext();
	UIGraphicsEndImageContext();
	return newImage;
}

- (void)encode:(CMSampleBufferRef)frame withPreflight:(bool (^)(CGImageRef))preflight andSuccess:(void (^)(NSData *))success {

	CVImageBufferRef imageBuffer = CMSampleBufferGetImageBuffer(frame);
	// Lock the image buffer
	CVPixelBufferLockBaseAddress(imageBuffer, 0);
	// Get information about the image
	uint8_t *baseAddress = (uint8_t *)CVPixelBufferGetBaseAddress(imageBuffer);
	size_t bytesPerRow = CVPixelBufferGetBytesPerRow(imageBuffer);
	size_t width = CVPixelBufferGetWidth(imageBuffer);
	size_t height = CVPixelBufferGetHeight(imageBuffer);
	
	// Create a CGImageRef from the CVImageBufferRef
	CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
	//CGContextRef newContext;
	CGContextRef newContext = CGBitmapContextCreate(baseAddress, width, height, 8, bytesPerRow, colorSpace, kCGBitmapByteOrder32Little | kCGImageAlphaPremultipliedFirst);
	CGImageRef newImage = CGBitmapContextCreateImage(newContext);
	
	// We release some components
	CGContextRelease(newContext);
	CGColorSpaceRelease(colorSpace);

	// preflight
	bool accepted = true;
	if (preflight != nil) accepted = preflight(newImage);
	
	if (accepted) {
		// scale & save
		UIImage *image = [UIImage imageWithCGImage:newImage];
//		[self imageWithImage:image scaledToSize:CGSizeMake(640, 452)];
		NSData *jpeg = UIImageJPEGRepresentation(image, 0.2);
		
		success(jpeg);
	}
	
	// We relase the CGImageRef
	CGImageRelease(newImage);
	
	// We unlock the  image buffer
	CVPixelBufferUnlockBaseAddress(imageBuffer, 0);
}

- (void)encode:(CMSampleBufferRef)frame withSuccess:(void (^)(NSData *))success {
	return [self encode:frame withPreflight:nil andSuccess:success];
}

@end
