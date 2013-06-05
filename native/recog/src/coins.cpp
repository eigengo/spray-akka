#include "coins.h"
#include <opencv2/gpu/gpu.hpp>

using namespace eigengo::akka;

std::vector<Coin> CoinCounter::countCpu(const cv::Mat &image) {
	using namespace cv;
	
	std::vector<Coin> coins;
	
	Mat dst;
	std::vector<Vec3f> circles;
	
	cvtColor(image, dst, CV_BGR2GRAY);
	GaussianBlur(dst, dst, Size(9, 9), 3, 3);
	threshold(dst, dst, 150, 255, THRESH_BINARY);
	GaussianBlur(dst, dst, Size(3, 3), 3, 3);
	//Canny(dst, dst, 1000, 1700, 5);
	HoughCircles(dst, circles, CV_HOUGH_GRADIENT,
				 1,    // dp
				 60,   // min dist
				 200,  // canny1
				 20,	   // canny2
				 30,   // min radius
				 100   // max radius
				 );
	
	for (size_t i = 0; i < circles.size(); i++) {
		Coin coin;
		coin.center = circles[i][0];
		coin.radius = circles[i][1];
		coins.push_back(coin);
	}

#ifdef TEST
	Mat x(image);
	for (size_t i = 0; i < circles.size(); i++ ) {
		Point center(cvRound(circles[i][0]), cvRound(circles[i][1]));
		int radius = cvRound(circles[i][2]);
		// draw the circle center
		circle(x, center, 3, Scalar(0,255,0), -1, 8, 0 );
		// draw the circle outline
		circle(x, center, radius, Scalar(0,0,255), 3, 8, 0 );
	}
	cv::imshow("", dst);
	cv::waitKey();
#endif
	return coins;
}

std::vector<Coin> CoinCounter::countGpu(const cv::Mat &cpuImage) {
	std::vector<Coin> coins;
	
	cv::gpu::GpuMat image(cpuImage);
	cv::gpu::GpuMat dst;
	cv::gpu::GpuMat circlesMat;
	
	cv::gpu::cvtColor(image, dst, CV_BGR2GRAY);
	cv::gpu::GaussianBlur(dst, dst, cv::Size(3, 3), 2, 2);
	cv::gpu::Canny(dst, dst, 1000, 1700, 5);
	cv::gpu::GaussianBlur(dst, dst, cv::Size(9, 9), 3, 3);
	cv::gpu::HoughCircles(dst, circlesMat, CV_HOUGH_GRADIENT,
				 1,    // dp
				 40,   // min dist
				 100,  // canny1
				 105,  // canny2
				 10,   // min radius
				 200   // max radius
				 );
	
	std::vector<cv::Vec3f> circles;
	cv::gpu::HoughCirclesDownload(circlesMat, circles);
	
	for (size_t i = 0; i < circles.size(); i++) {
		Coin coin;
		coin.center = circles[i][0];
		coin.radius = circles[i][1];
		
		bool dup = false;
		for (size_t j = 0; j < coins.size(); j++) {
			if (coins.at(j).center == coin.center ||
				coins.at(j).radius == coin.radius) {
				dup = true;
				break;
			}
		}
		if (!dup) coins.push_back(coin);
	}
	
	return coins;
}

std::vector<Coin> CoinCounter::count(const cv::Mat &image) {
	//if (cv::gpu::getCudaEnabledDeviceCount() > 0) return countGpu(image);
	return countCpu(image);
}
