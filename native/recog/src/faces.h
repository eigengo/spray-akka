#ifndef faces_h
#define faces_h

#include <opencv2/opencv.hpp>
#include <opencv2/gpu/gpu.hpp>
#include <vector>

namespace eigengo { namespace akka {

	struct Face {
		double left;
		double top;
		double width;
		double height;
	};
	
	class FaceCounter {
	private:
		cv::CascadeClassifier faceClassifierCpu;
		cv::gpu::CascadeClassifier_GPU faceClassifierGpu;
		
		Face fromRect(cv::Rect);
		
		std::vector<Face> countGpu(const cv::Mat &image);
		std::vector<Face> countCpu(const cv::Mat &image);
	public:
		FaceCounter();
		std::vector<Face> count(const cv::Mat &image);
	};
		
}
}

#endif