#import "CVServer.h"
#import "BlockingQueueInputStream.h"
#import "AFNetworking/AFHTTPRequestOperation.h"
#import "AFNetworking/AFHTTPClient.h"
#import "H264/AVEncoder.h"
#import "ImageEncoder.h"
#import "H264/NALUnit.h"

@interface AbstractCVServerConnectionInput : NSObject {
@protected
	NSURL *url;
	NSString *sessionId;
	id<CVServerConnectionDelegate> delegate;
	CVServerConnectionInputStats stats;
	ImageEncoder *imageEncoder;
}
- (id)initWithUrl:(NSURL*)url session:(NSString*)sessionId andDelegate:(id<CVServerConnectionDelegate>)delegate;
- (void)initConnectionInput;
- (CVServerConnectionInputStats)getStats;
- (void)submitFrameRaw:(NSData *)rawFrame;
@end

@interface AbstractStreamingCVServerConnectionInput : AbstractCVServerConnectionInput
- (NSData*)marker;
- (void)initConnectionInput;
- (void)submitFrameRaw:(NSData*)rawFrame;
- (void)stopRunning;
@end

@interface CVServerConnectionInputStatic : AbstractCVServerConnectionInput<CVServerConnectionInput>
@end

@interface CVServerConnectionInputH264 : AbstractStreamingCVServerConnectionInput<CVServerConnectionInput>
@end

@interface CVServerConnectionInputMJPEG : AbstractStreamingCVServerConnectionInput<CVServerConnectionInput>
@end

@interface CVServerConnectionRTSPServer : AbstractCVServerConnectionInput<CVServerConnectionInput>
- (id)initWithUrl:(NSURL *)url rtspUrl:(NSURL *)rtspUrl session:(NSString *)sessionId andDelegate:(id<CVServerConnectionDelegate>)delegate;
@end

@implementation CVServerTransactionConnection {
	NSURL *baseUrl;
	NSString *sessionId;
}

- (CVServerTransactionConnection*)initWithUrl:(NSURL*)aBaseUrl andSessionId:(NSString*)aSessionId {
	self = [super init];
	if (self) {
		baseUrl = aBaseUrl;
		sessionId = aSessionId;
	}
	return self;
}

- (NSURL*)inputUrl:(NSString*)path {
	NSString *pathWithSessionId = [NSString stringWithFormat:@"%@/%@", path, sessionId];
	return [baseUrl URLByAppendingPathComponent:pathWithSessionId];
}

- (id<CVServerConnectionInput>)staticInput:(id<CVServerConnectionDelegate>)delegate {
	return [[CVServerConnectionInputStatic alloc] initWithUrl:[self inputUrl:@"static"] session:sessionId andDelegate:delegate];
}

- (id<CVServerConnectionInput>)h264Input:(id<CVServerConnectionDelegate>)delegate {
	return [[CVServerConnectionInputH264 alloc] initWithUrl:[self inputUrl:@"h264"] session:sessionId andDelegate:delegate];
}

- (id<CVServerConnectionInput>)mjpegInput:(id<CVServerConnectionDelegate>)delegate {
	return [[CVServerConnectionInputMJPEG alloc] initWithUrl:[self inputUrl:@"mjpeg"] session:sessionId andDelegate:delegate];
}

- (id<CVServerConnectionInput>)rtspServerInput:(id<CVServerConnectionDelegate>)delegate url:(out NSURL**)url {
    NSString* ipaddr = [RTSPServer getIPAddress];
	*url = [NSURL URLWithString:[NSString stringWithFormat:@"rtsp://%@/", ipaddr]];
	CVServerConnectionRTSPServer *conn = [[CVServerConnectionRTSPServer alloc] initWithUrl:[self inputUrl:@"rtsp"] rtspUrl:*url session:sessionId andDelegate:delegate];
	return conn;
}

@end

#pragma mark - Connection to CV server 

@implementation CVServerConnection {
	NSURL *baseUrl;
}

- (id)initWithUrl:(NSURL *)aBaseUrl {
	self = [super init];
	if (self) {
		baseUrl = aBaseUrl;
	}
	
	return self;
}

+ (CVServerConnection*)connection:(NSURL *)baseUrl {
	[[NSURLCache sharedURLCache] setMemoryCapacity:0];
	[[NSURLCache sharedURLCache] setDiskCapacity:0];
	
	return [[CVServerConnection alloc] initWithUrl:baseUrl];
}

- (CVServerTransactionConnection*)begin:(id)configuration {
	NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:baseUrl];
	[request setTimeoutInterval:30.0];
	[request setHTTPMethod:@"POST"];
	AFHTTPRequestOperation* operation = [[AFHTTPRequestOperation alloc] initWithRequest:request];
	[operation start];
	[operation waitUntilFinished];
	NSString* sessionId = [operation responseString];
	return [[CVServerTransactionConnection alloc] initWithUrl:baseUrl andSessionId:sessionId];
}

@end

#pragma mark - AbstractCVServerConnectionInput

@implementation AbstractCVServerConnectionInput

- (id)initWithUrl:(NSURL*)aUrl session:(NSString*)aSessionId andDelegate:(id<CVServerConnectionDelegate>)aDelegate {
	self = [super init];
	if (self) {
		url = aUrl;
		sessionId = aSessionId;
		delegate = aDelegate;
		imageEncoder = [[ImageEncoder alloc] init];
		[self initConnectionInput];
	}
	return self;
}

- (void)initConnectionInput {
	// nothing in the abstract class
}

- (CVServerConnectionInputStats)getStats {
	return stats;
}

- (void)submitFrameRaw:(NSData *)rawFrame {
	// nothing to do
}

@end

#pragma mark - Single image posts

/**
 * Uses plain JPEG encoding to submit the images from the incoming stream of frames
 */
@implementation CVServerConnectionInputStatic

- (void)submitFrame:(CMSampleBufferRef)frame {
	[self submitFrame:frame andPreflight:^bool(CGImageRef) {
		return true;
	}];
}

- (void)submitFrame:(CMSampleBufferRef)frame andPreflight:(bool (^)(CGImageRef))preflight {
	[imageEncoder encode:frame withPreflight:preflight andSuccess:^(NSData* data) {
		[self submitFrameRaw:data];
	}];
}

- (void)stopRunning {
	// This is a static connection. Nothing to see here.
}

@end

#pragma mark - HTTP Streaming delegates

@implementation AbstractStreamingCVServerConnectionInput {
	BlockingQueueInputStream *stream;
	bool encoding;
}

- (NSData*)marker {
	return [@" " dataUsingEncoding:NSASCIIStringEncoding];
}

- (void)initConnectionInput {
	stats.networkBytes = 0;
	stats.requestCount = 1;
	stats.networkTime = 0;
	
	NSData* sessionIdData = [sessionId dataUsingEncoding:NSASCIIStringEncoding];
	NSMutableData *chunkHeader = [NSMutableData dataWithData:sessionIdData];
	[chunkHeader appendData:[self marker]];
	stream = [[BlockingQueueInputStream alloc] initWithChunkHeader:chunkHeader];
	
	NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
	[request setTimeoutInterval:30.0];
	[request setHTTPMethod:@"POST"];
	[request setHTTPBodyStream:stream];
	[request addValue:@"application/octet-stream" forHTTPHeaderField:@"Content-Type"];
	AFHTTPRequestOperation* operation = [[AFHTTPRequestOperation alloc] initWithRequest:request];
	[operation setCompletionBlockWithSuccess:^(AFHTTPRequestOperation *operation, id responseObject) {
		[delegate cvServerConnectionOk:responseObject];
	} failure:^(AFHTTPRequestOperation *operation, NSError *error) {
		[delegate cvServerConnectionFailed:error];
	}];
	[operation start];
}

- (void)transportData:(NSData*)frame {
	[stream appendData:frame];
	
	stats.networkBytes += frame.length;
	stats.requestCount += 1;
}

- (void)submitFrameRaw:(NSData *)rawFrame {
	[self transportData:rawFrame];
}

- (void)stopRunning {
	[self transportData:[[NSData alloc] init]];
	[self transportData:[[NSData alloc] init]];
	[stream close];
}

@end

/**
 * Uses the i264 encoder to encode the incoming stream of frames. 
 */
@implementation CVServerConnectionInputH264 {
	AVEncoder* encoder;
}

- (NSData*)marker {
	return [@"H" dataUsingEncoding:NSASCIIStringEncoding];
}

- (void)initConnectionInput {
	[super initConnectionInput];
	
	encoder = [AVEncoder encoderForHeight:480 andWidth:720];
	[encoder encodeWithBlock:^int(NSArray *data, double pts) {
		for (NSData* e in data) {
			stats.networkBytes += e.length;
			[self transportData:e];
		}
		return 0;
	} onParams:^int(NSData *params) {
		[self transportData:params];
		return 0;
	}];
}

- (void)submitFrame:(CMSampleBufferRef)frame {
	[encoder encodeFrame:frame];
}

- (void)submitFrame:(CMSampleBufferRef)frame andPreflight:(bool (^)(CGImageRef))preflight {
	[imageEncoder encode:frame withPreflight:preflight andSuccess:^(NSData *) {
		[encoder encodeFrame:frame];
	}];
}

@end

@implementation CVServerConnectionInputMJPEG 

- (NSData*)marker {
	return [@"M" dataUsingEncoding:NSASCIIStringEncoding];
}

- (void)initConnectionInput {
	[super initConnectionInput];
	imageEncoder = [[ImageEncoder alloc] init];
}

- (void)submitFrame:(CMSampleBufferRef)frame {
	[self submitFrame:frame andPreflight:^bool(CGImageRef) {
		return true;
	}];
}

- (void)submitFrame:(CMSampleBufferRef)frame andPreflight:(bool (^)(CGImageRef))preflight {
	[imageEncoder encode:frame withPreflight:preflight andSuccess:^(NSData* data) {
		[self transportData:data];
	}];
}

@end

@implementation CVServerConnectionRTSPServer {
	NSURL* rtspUrl;
	AVEncoder* encoder;
	RTSPServer *server;
}

- (id)initWithUrl:(NSURL *)aUrl rtspUrl:(NSURL *)aRtspUrl session:(NSString *)aSessionId andDelegate:(id<CVServerConnectionDelegate>)aDelegate {
	self = [super init];
	stats.networkBytes = 0;
	stats.networkTime = 0;
	stats.requestCount = 0;
	if (self) {
		url = aUrl;
		sessionId = aSessionId;
		delegate = aDelegate;
		rtspUrl = aRtspUrl;
		[self initConnectionInput];
	}
	return self;

}

- (void)initConnectionInput {
	encoder = [AVEncoder encoderForHeight:480 andWidth:720];
	[encoder encodeWithBlock:^int(NSArray *data, double pts) {
		server.bitrate = encoder.bitspersecond;
		if ([server connectionCount] > 0) {
			for (NSData* e in data) {
				stats.networkBytes += e.length;
			}
			[server onVideoData:data time:pts];
		}
		return 0;
	} onParams:^int(NSData *params) {
		stats.requestCount++;
		server = [RTSPServer setupListener:params];
		return 0;
	}];
	
	NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
	[request setTimeoutInterval:30.0];
	[request setHTTPMethod:@"POST"];
	[request setHTTPBody:[[rtspUrl absoluteString] dataUsingEncoding:NSASCIIStringEncoding]];
	[request addValue:@"application/url" forHTTPHeaderField:@"Content-Type"];
	AFHTTPRequestOperation* operation = [[AFHTTPRequestOperation alloc] initWithRequest:request];
	[operation setCompletionBlockWithSuccess:^(AFHTTPRequestOperation *operation, id responseObject) {
		
	} failure:^(AFHTTPRequestOperation *operation, NSError *error) {
		[delegate cvServerConnectionFailed:error];
	}];
	[operation start];
	[operation waitUntilFinished];
}

- (void)submitFrame:(CMSampleBufferRef)frame {
	[encoder encodeFrame:frame];
}

- (void)submitFrame:(CMSampleBufferRef)frame andPreflight:(bool (^)(CGImageRef))preflight {
	[imageEncoder encode:frame withPreflight:preflight andSuccess:^(NSData *) {
		[encoder encodeFrame:frame];
	}];
}

- (void)submitFrameRaw:(NSData *)rawFrame {
	// do nothing
}

- (void)stopRunning {
	[server shutdownServer];
}

@end
