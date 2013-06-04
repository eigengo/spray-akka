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
}
- (id)initWithUrl:(NSURL*)url session:(NSString*)sessionId andDelegate:(id<CVServerConnectionDelegate>)delegate;
- (void)initConnectionInput;
- (CVServerConnectionInputStats)getStats;
@end

@interface CVServerConnectionInputStatic : AbstractCVServerConnectionInput<CVServerConnectionInput>
@end

@interface CVServerConnectionInputStream : AbstractCVServerConnectionInput<CVServerConnectionInput> 
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

- (id<CVServerConnectionInput>)streamInput:(id<CVServerConnectionDelegate>)delegate {
	return [[CVServerConnectionInputStream alloc] initWithUrl:[self inputUrl:@"stream"] session:sessionId andDelegate:delegate];
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

@end

#pragma mark - Single image posts

/**
 * Uses plain JPEG encoding to submit the images from the incoming stream of frames
 */
@implementation CVServerConnectionInputStatic {
	ImageEncoder *imageEncoder;
	
}

- (void)initConnectionInput {
	imageEncoder = [[ImageEncoder alloc] init];
}

- (void)submitFrame:(CMSampleBufferRef)frame {
	[imageEncoder encode:frame withSuccess:^(NSData* data) {
		[self submitFrameRaw:data];
	}];
}

- (void)submitFrameRaw:(NSData *)rawFrame {
	NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
	[request setTimeoutInterval:30.0];
	[request setHTTPMethod:@"POST"];
	[request setHTTPBody:rawFrame];
	[request addValue:@"application/octet-stream" forHTTPHeaderField:@"Content-Type"];
	AFHTTPRequestOperation* operation = [[AFHTTPRequestOperation alloc] initWithRequest:request];
	[operation setCompletionBlockWithSuccess:^(AFHTTPRequestOperation *operation, id responseObject) {
		[delegate cvServerConnectionOk:responseObject];
	} failure:^(AFHTTPRequestOperation *operation, NSError *error) {
		[delegate cvServerConnectionFailed:error];
	}];
	[operation start];
	[operation waitUntilFinished];
	
	stats.requestCount++;
	stats.networkBytes += [rawFrame length];
}

- (void)stopRunning {
	// This is a static connection. Nothing to see here.
}

@end

#pragma mark - HTTP Streaming post

/**
 * Uses the i264 encoder to encode the incoming stream of frames. 
 */
@implementation CVServerConnectionInputStream {
	BlockingQueueInputStream *stream;
	bool encoding;
	AVEncoder* encoder;
}

- (void)transportData:(NSData*)frame {
	NSData* sessionIdData = [sessionId dataUsingEncoding:NSASCIIStringEncoding];
	NSMutableData *frameWithSessionId = [NSMutableData dataWithData:sessionIdData];
	[frameWithSessionId appendData:frame];
	[stream appendData:frameWithSessionId];
}

- (void)initConnectionInput {
	stats.networkBytes = 0;
	stats.requestCount = 1;
	stats.networkTime = 0;
	
	stream = [[BlockingQueueInputStream alloc] init];

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
	
	encoder = [AVEncoder encoderForHeight:480 andWidth:720];
	__block bool paramsSent = false;
	[encoder encodeWithBlock:^int(NSArray *data, double pts) {
		for (NSData* e in data) {
			stats.networkBytes += e.length;
			[self transportData:e];
		}
		return 0;
	} onParams:^int(NSData *params) {
		if (paramsSent) return 0;
		
		avcCHeader avcC((const BYTE*)[params bytes], [params length]);
		
		SeqParamSet seqParams;
		seqParams.Parse(avcC.sps());
		int cx = seqParams.EncodedWidth();
		int cy = seqParams.EncodedHeight();
		
		NSLog(@"%d * %d", cx, cy);

		uint8_t zzzo[] = {0, 0, 0, 1};
		
		NSMutableData *data = [NSMutableData dataWithBytes:zzzo length:4];
		[data appendBytes:avcC.sps()->Start() length:avcC.sps()->Length()];
		[data appendBytes:avcC.pps()->Start() length:avcC.pps()->Length()];
		[self transportData:data];
		paramsSent = true;
		return 0;
	}];
}

- (void)submitFrame:(CMSampleBufferRef)frame {
	[encoder encodeFrame:frame];
}

- (void)submitFrameRaw:(NSData *)rawFrame {
	NSMutableData *data = [NSMutableData dataWithData:[sessionId dataUsingEncoding:NSASCIIStringEncoding]];
	[data appendData:rawFrame];
	[stream appendData:data];
}

- (void)stopRunning {
	[stream appendData:[sessionId dataUsingEncoding:NSASCIIStringEncoding]];
	[stream close];
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

- (void)submitFrameRaw:(NSData *)rawFrame {
	// do nothing
}

- (void)stopRunning {
	[server shutdownServer];
}

@end
