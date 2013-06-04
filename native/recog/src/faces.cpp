#include "faces.h"
#include <opencv2/gpu/gpu.hpp>

using namespace eigengo::akka; 

FaceCounter::FaceCounter() {
	if (!faceClassifierCpu.load("face_cascade.xml")) throw std::exception();
	if (!faceClassifierGpu.load("face_cascade.xml")) throw std::exception();
}

Face FaceCounter::fromRect(cv::Rect rect) {
	Face face;
	face.left = rect.x;
	face.top =  rect.y;
	face.width = rect.width;
	face.height = rect.height;
	
	return face;
}

std::vector<Face> FaceCounter::countCpu(const cv::Mat &image) {
	using namespace cv;
	std::vector<Rect> objects;
	Mat gray;
	cvtColor(image, gray, CV_RGB2GRAY);
	faceClassifierCpu.detectMultiScale(gray, objects, 1.1, 2, CV_HAAR_DO_ROUGH_SEARCH);
	std::vector<Face> faces;
	for (auto i = objects.begin(); i != objects.end(); ++i) {
		faces.push_back(fromRect(*i));
	}
	return faces;
}

std::vector<Face> FaceCounter::countGpu(const cv::Mat &image) {
	using namespace cv;
	gpu::GpuMat imageGpu;
	gpu::cvtColor(gpu::GpuMat(image), imageGpu, CV_RGB2GRAY);
	gpu::GpuMat objectsGpu;
	faceClassifierGpu.findLargestObject = false;
	int count = faceClassifierGpu.detectMultiScale(imageGpu, objectsGpu, 1.1);
	
	Mat objectsCpu;
	// download only detected number of rectangles
	objectsGpu.colRange(0, count).download(objectsCpu);
	
	Rect *objects = objectsCpu.ptr<Rect>();
	std::vector<Face> faces;
	for (size_t i = 0; i < count; ++i) {
		faces.push_back(fromRect(objects[i]));
	}

	return faces;
}

std::vector<Face> FaceCounter::count(const cv::Mat &image) {
	//if (cv::gpu::getCudaEnabledDeviceCount() > 0) return countGpu(image);
	return countCpu(image);
}