#include "faces.h"
#include <opencv2/gpu/gpu.hpp>

using namespace eigengo::akka; 

FaceCounter::FaceCounter() {
	if (!faceClassifierCpu.load("face_cascade.xml")) throw std::exception();
	if (!faceClassifierGpu.load("face_cascade.xml")) throw std::exception();
}

std::vector<Face> FaceCounter::countCpu(const cv::Mat &image) {
	
}

std::vector<Face> FaceCounter::countGpu(const cv::Mat &image) {
	
}

std::vector<Face> FaceCounter::count(const cv::Mat &image) {
	if (cv::gpu::getCudaEnabledDeviceCount() > 0) return countGpu(image);
	return countCpu(image);
}