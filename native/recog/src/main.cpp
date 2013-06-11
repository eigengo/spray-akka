#include "main.h"
#include "im.h"
#include "jzon.h"
#include <opencv2/gpu/gpu.hpp>

using namespace eigengo::akka;

Main::Main(const std::string queue, const std::string exchange, const std::string routingKey) :
RabbitRpcServer::RabbitRpcServer(queue, exchange, routingKey) {
	
}

std::string Main::handleMessage(const AmqpClient::BasicMessage::ptr_t message, const AmqpClient::Channel::ptr_t channel) {
	ImageMessage imageMessage(message);
	
	Jzon::Object responseJson;
	try {
		Jzon::Array coinsJson;
		//Jzon::Array facesJson;
		auto imageData = imageMessage.headImage();
		auto imageMat = cv::imdecode(cv::Mat(imageData), 1);
		// ponies & unicorns
		auto coins = coinCounter.count(imageMat);
		
		for (auto i = coins.begin(); i != coins.end(); ++i) {
			/*
			Jzon::Object faceJson;
			faceJson.Add("left", face.left);
			faceJson.Add("top", face.top);
			faceJson.Add("width", face.width);
			faceJson.Add("height", face.height);
			facesJson.Add(faceJson);
			*/
			Jzon::Object coinJson;
			coinJson.Add("center", i->center);
			coinJson.Add("radius", i->radius);
			coinsJson.Add(coinJson);
		}
		//responseJson.Add("faces", facesJson);
		responseJson.Add("coins", coinsJson);
		responseJson.Add("succeeded", true);
	} catch (std::exception &e) {
		// bantha poodoo!
		std::cerr << e.what() << std::endl;
		responseJson.Add("succeeded", false);
	} catch (...) {
		// bantha poodoo!
		responseJson.Add("succeeded", false);
	}
	Jzon::Writer writer(responseJson, Jzon::NoFormat);
	writer.Write();

	return writer.GetResult();
}

void Main::inThreadInit() {
	using namespace cv::gpu;
	int deviceCount = getCudaEnabledDeviceCount();
	if (deviceCount > 0) {
		setDevice(0);
	}
}

int main(int argc, char** argv) {
	Main main("count", "amq.direct", "count.key");
	main.runAndJoin(8);
	return 0;
}