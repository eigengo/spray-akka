#include "rtest.h"
#include "coins.h"

using namespace eigengo::akka;

class CoinCounterTest : public OpenCVTest {
protected:
	CoinCounter counter;
};

TEST_F(CoinCounterTest, ThreeCoins) {
	auto image = load("coins3.png");
	EXPECT_EQ(3, counter.count(image).size());
}

TEST_F(CoinCounterTest, NoCoins) {
	auto image = load("f.jpg");
	EXPECT_EQ(0, counter.count(image).size());
}
