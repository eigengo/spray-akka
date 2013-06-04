#import "BlockingQueueInputStream.h"

@implementation BlockingQueueInputStream {
    NSStreamStatus streamStatus;
    id <NSStreamDelegate> delegate;
	NSData *end;
	NSData *mid;
	int chunkSize;
}

- (id)initWithChunkHeader:(NSData *)aChunkHeader {
    self = [super init];
    if (self) {
        // Initialization code here.
        streamStatus = NSStreamStatusNotOpen;
		readLock = dispatch_semaphore_create(0);
		writeLock = dispatch_semaphore_create(1);
		data = nil;
		chunkHeader = aChunkHeader;
		end = [@"E" dataUsingEncoding:NSASCIIStringEncoding];
		mid = [@"M" dataUsingEncoding:NSASCIIStringEncoding];
		chunkSize = 32768 - chunkHeader.length - 1;
    }
    
    return self;
}

#pragma mark - NSStream subclass overrides

- (void)open {
    streamStatus = NSStreamStatusOpen;
}

- (void)close {
    streamStatus = NSStreamStatusClosed;
	dispatch_semaphore_signal(readLock);
	dispatch_semaphore_signal(writeLock);
}

- (id<NSStreamDelegate>)delegate {
    return delegate;
}

- (void)setDelegate:(id<NSStreamDelegate>)aDelegate {
    delegate = aDelegate;
}

- (void)scheduleInRunLoop:(NSRunLoop *)aRunLoop forMode:(NSString *)mode {
    // Nothing to do here, because this stream does not need a run loop to produce its data.
}

- (void)removeFromRunLoop:(NSRunLoop *)aRunLoop forMode:(NSString *)mode {
    // Nothing to do here, because this stream does not need a run loop to produce its data.
}

- (id)propertyForKey:(NSString *)key {
    return nil;
}

- (BOOL)setProperty:(id)property forKey:(NSString *)key {
    return NO;
}

- (NSStreamStatus)streamStatus {
    return streamStatus;
}

- (NSError *)streamError {
    return nil;
}

- (void)appendData:(NSData *)aData {
	dispatch_semaphore_wait(writeLock, DISPATCH_TIME_FOREVER);
	NSMutableData *newData = [NSMutableData dataWithData:chunkHeader];
	if (aData.length > chunkSize) {
		[newData appendData:mid];
	} else {
		[newData appendData:end];
	}
	[newData appendData:aData];
	data = newData;
	dispatch_semaphore_signal(readLock);
}

#pragma mark - NSInputStream subclass overrides

- (NSInteger)read:(uint8_t *)buffer maxLength:(NSUInteger)len {
	if (streamStatus != NSStreamStatusOpen) return -1;
	
	dispatch_semaphore_wait(readLock, DISPATCH_TIME_FOREVER);
	if (streamStatus != NSStreamStatusOpen) return -1;
	
	NSUInteger dataLen = [data length];
	NSUInteger readLen = MIN(dataLen, len);
	uint8_t* dataBuffer = (uint8_t*)[data bytes];
	for (NSUInteger i = 0; i < readLen; i++) {
		buffer[i] = dataBuffer[i];
	}
	
	if (dataLen > len) {
		NSLog(@"len = %d", len);
		NSMutableData *newData = [NSMutableData dataWithData:chunkHeader];
		if (newData.length - chunkHeader.length - 1 > chunkSize) {
			[newData appendData:mid];
		} else {
			[newData appendData:end];
		}
		[newData appendData:[data subdataWithRange:NSMakeRange(len, dataLen - len)]];
		data = newData;
		dispatch_semaphore_signal(readLock);
	} else {
		dispatch_semaphore_signal(writeLock);
	}
	
	return readLen;
}

- (BOOL)getBuffer:(uint8_t **)buffer length:(NSUInteger *)len {
	// Not appropriate for this kind of stream; return NO.
	return NO;
}

- (BOOL)hasBytesAvailable {
	// There are always bytes available.
	return YES;
}

#pragma mark - Undocumented CFReadStream bridged methods

- (void)_scheduleInCFRunLoop:(CFRunLoopRef)aRunLoop forMode:(CFStringRef)aMode {
	// Nothing to do here, because this stream does not need a run loop to produce its data.
}

- (BOOL)_setCFClientFlags:(CFOptionFlags)inFlags
                 callback:(CFReadStreamClientCallBack)inCallback
                  context:(CFStreamClientContext *)inContext {
	return YES;
}

- (void)_unscheduleFromCFRunLoop:(CFRunLoopRef)aRunLoop forMode:(CFStringRef)aMode {
	// Nothing to do here, because this stream does not need a run loop to produce its data.
}


@end