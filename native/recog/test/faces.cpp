#include "rtest.h"
#include "coins.h"

using namespace eigengo::akka;

class FaceCounterTest : public OpenCVTest {
protected:
	FaceCounter counter;
};

TEST_F(FaceCounterTest, ThreeCoins) {
	auto image = load("faces3.png");
	EXPECT_EQ(3, counter.count(image).size());
}

TEST_F(FaceCounterTest, NoCoins) {
	auto image = load("x.jpg");
	EXPECT_EQ(0, counter.count(image).size());
}
