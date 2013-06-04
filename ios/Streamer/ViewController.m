#import "ViewController.h"

#define FRAMES_PER_SECOND_MOD 7

@implementation ViewController {
	CVServerConnection *serverConnection;
	CVServerTransactionConnection *serverTransactionConnection;
	id<CVServerConnectionInput> serverConnectionInput;
	
	AVCaptureSession *captureSession;
	AVCaptureVideoPreviewLayer *previewLayer;
	int frameMod;
	
	bool capturing;
}

#pragma mark - Housekeeping

- (void)viewDidLoad {
    [super viewDidLoad];
	capturing = false;
	[self.statusLabel setText:@""];
	NSURL *serverBaseUrl = [NSURL URLWithString:@"http://192.168.0.5:8080/recog"];
	serverConnection = [CVServerConnection connection:serverBaseUrl];
}

- (void)didReceiveMemoryWarning {
    [super didReceiveMemoryWarning];
}

#pragma mark - Video capture (using the back camera)

- (void)startCapture {
#if !(TARGET_IPHONE_SIMULATOR)
	// Video capture session; without a device attached to it.
	captureSession = [[AVCaptureSession alloc] init];
	
	// Preview layer that will show the video
	previewLayer = [AVCaptureVideoPreviewLayer layerWithSession:captureSession];
	previewLayer.frame = CGRectMake(0, 100, 320, 640);
	previewLayer.contentsGravity = kCAGravityResizeAspectFill;
	previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill;
	[self.view.layer addSublayer:previewLayer];
	
	// begin the capture
	AVCaptureDevice *videoCaptureDevice = [AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeVideo];
	NSError *error = nil;
	
	// video output is the callback
	AVCaptureVideoDataOutput *videoOutput = [[AVCaptureVideoDataOutput alloc] init];
	videoOutput.alwaysDiscardsLateVideoFrames = YES;
	videoOutput.videoSettings = [NSDictionary dictionaryWithObject:[NSNumber numberWithInt:kCVPixelFormatType_32BGRA] forKey:(id)kCVPixelBufferPixelFormatTypeKey];
	dispatch_queue_t queue = dispatch_queue_create("VideoCaptureQueue", NULL);
	[videoOutput setSampleBufferDelegate:self queue:queue];
	
	// video input is the camera
	AVCaptureDeviceInput *videoInput = [AVCaptureDeviceInput deviceInputWithDevice:videoCaptureDevice error:&error];
	
	// capture session connects the input with the output (camera -> self.captureOutput)
	[captureSession addInput:videoInput];
	[captureSession addOutput:videoOutput];
	
	// start the capture session
	[captureSession startRunning];
	
	// begin a transaction
	serverTransactionConnection = [serverConnection begin:nil];
	
	// (a) using static images
	//serverConnectionInput = [serverTransactionConnection staticInput:self];
	
	// (b) using MJPEG stream
	serverConnectionInput = [serverTransactionConnection mjpegInput:self];
	
	// (c) using H.264 stream
	//serverConnectionInput = [serverTransactionConnection h264Input:self];

	// (d) using RTSP server
	//NSURL *url;
	//serverConnectionInput = [serverTransactionConnection rtspServerInput:self url:&url];
	//[self.statusLabel setText:[url absoluteString]];
#endif
}

- (void)stopCapture {
#if !(TARGET_IPHONE_SIMULATOR)
	[captureSession stopRunning];
	[serverConnectionInput stopRunning];
	
	[previewLayer removeFromSuperlayer];
	
	previewLayer = nil;
	captureSession = nil;
	serverConnectionInput = nil;
	serverTransactionConnection = nil;
#endif
}

- (void)captureOutput:(AVCaptureOutput *)captureOutput didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer fromConnection:(AVCaptureConnection *)connection {
#if !(TARGET_IPHONE_SIMULATOR)
	frameMod++;
	if (frameMod % FRAMES_PER_SECOND_MOD == 0) {
		[serverConnectionInput submitFrame:sampleBuffer];
		NSLog(@"Network bytes %ld", [serverConnectionInput getStats].networkBytes);
	}
#endif
}

#pragma mark - UI

- (IBAction)startStop:(id)sender {
	if (capturing) {
		[self stopCapture];
		[self.startStopButton setTitle:@"Start" forState:UIControlStateNormal];
		[self.startStopButton setTintColor:[UIColor greenColor]];
		capturing = false;
		self.predefButton.enabled = true;
	} else {
		[self startCapture];
		[self.startStopButton setTitle:@"Stop" forState:UIControlStateNormal];
		[self.startStopButton setTintColor:[UIColor redColor]];
		capturing = true;
	}
}

- (IBAction)predefStopStart:(id)sender {
	self.startStopButton.enabled = false;

	serverTransactionConnection = [serverConnection begin:nil];
	serverConnectionInput = [serverTransactionConnection h264Input:self];
	
	dispatch_queue_t queue = dispatch_queue_create("Predef", NULL);
	dispatch_sync(queue, ^{
		NSString *filePath = [[NSBundle mainBundle] pathForResource:@"coins2" ofType:@"mp4"];
		NSFileHandle* fileHandle = [NSFileHandle fileHandleForReadingAtPath:filePath];
		while (true) {
			NSData *data = [fileHandle readDataOfLength:16000];
			[serverConnectionInput submitFrameRaw:data];
			[NSThread sleepForTimeInterval:.25];		// 16000 * 4 Bps ~ 64 kB/s
			if (data.length == 0) break;
		}
		[serverConnectionInput submitFrameRaw:[[NSData alloc] init]];
		[serverConnectionInput stopRunning];
		[fileHandle closeFile];
	});
	self.startStopButton.enabled = true;
}

#pragma mark - CVServerConnectionDelegate methods

- (void)cvServerConnectionOk:(id)response {
	NSLog(@":))");
}

- (void)cvServerConnectionAccepted:(id)response {
	NSLog(@":)");
}

- (void)cvServerConnectionRejected:(id)response {
	NSLog(@":(");
}

- (void)cvServerConnectionFailed:(NSError *)reason {
	NSLog(@":((");
}

@end
