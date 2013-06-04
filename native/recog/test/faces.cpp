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

TEST_F(FaceCounterTest, Two_1) {
	auto image = load("faces2_1.png");
	EXPECT_EQ(2, counter.count(image).size());
}

TEST_F(FaceCounterTest, Two_2) {
	auto image = load("faces2_2.png");
	EXPECT_EQ(2, counter.count(image).size());
}

TEST_F(FaceCounterTest, NoFaces) {
	auto image = load("xb.jpg");
	EXPECT_EQ(0, counter.count(image).size());
}
