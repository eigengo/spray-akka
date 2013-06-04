#include "rtest.h"
#include "faces.h"

using namespace eigengo::akka;

class FaceCounterTest : public OpenCVTest {
protected:
	FaceCounter counter;
};

TEST_F(FaceCounterTest, ThreeFaces) {
	auto image = load("faces3.png");
	EXPECT_EQ(3, counter.count(image).size());
}

TEST_F(FaceCounterTest, NoFaces) {
	auto image = load("xb.jpg");
	EXPECT_EQ(0, counter.count(image).size());
}
