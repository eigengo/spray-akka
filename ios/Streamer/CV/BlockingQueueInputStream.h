#import <Foundation/Foundation.h>

/**
 * Subclass of NSInputStream that is intended to be used from two separate threads: a reader thread that appends
 * data; and the writer thread that consumes the written data.
 *
 * The two threads execute in a round-robin fashion. We begin by -read, which blocks until the -appendData method finishes; 
 * the stream does not accept any further writes until all data is read in the -read method. Once that happens, further 
 * calls to -read are blocked until the (now unblocked) -appendData method is called.
 *
 * Typical usage is:
 *
 * BlockingQueueInputStream *s = [[BlockingQueueInputStream alloc] init];
 * // in thread 1
 * [s read...] // blocks until some thread calls -appendData
 *
 * // in thread 2
 * [s appendData...] // ^^ the call above unblocks 
 * [s appendData...] // blocks until all data has been read (in thread 1)
 */
@interface BlockingQueueInputStream : NSInputStream {
@private
    NSData *data;
	NSData *chunkHeader;
	dispatch_semaphore_t readLock;
	dispatch_semaphore_t writeLock;
}
  /**
   * Make an instance of the stream; set up the locks.
   */
- (id)initWithChunkHeader:(NSData*)chunkHeader;

  /**
   * Make the ``data`` available to reading. Blocks if the existing data has not yet been all read.
   * @param data the data to be made available for ``-read``ing
   */
- (void)appendData:(NSData*)data;
@end