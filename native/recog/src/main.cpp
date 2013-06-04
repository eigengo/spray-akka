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
		Jzon::Array facesJson;
		auto imageData = imageMessage.headImage();
		auto imageMat = cv::imdecode(cv::Mat(imageData), 1);
		// ponies & unicorns
		auto faces = faceCounter.count(imageMat);
		
		for (auto i = faces.begin(); i != faces.end(); ++i) {
			auto face = *i;
			Jzon::Object faceJson;
			faceJson.Add("left", face.left);
			faceJson.Add("top", face.top);
			faceJson.Add("width", face.width);
			faceJson.Add("height", face.height);
			facesJson.Add(faceJson);
		}
		responseJson.Add("faces", facesJson);
		responseJson.Add("succeeded", true);
	} catch (std::exception &e) {
		// bantha poodoo!
		std::cerr << e.what() << std::endl;
		responseJson.Add("succeeded", false);
	}
	Jzon::Writer writer(responseJson, Jzon::NoFormat);
	writer.Write();

	return writer.GetResult();
}

void Main::inThreadInit() {
	cv::gpu::setDevice(0);
}

int main(int argc, char** argv) {
	Main main("count", "amq.direct", "count.key");
	main.runAndJoin(1);
	return 0;
}