#include "rtest.h"
#include "count.h"

using namespace eigengo::akka;

class CounterTest : public OpenCVTest {
protected:
	CoinCounter counter;
};

TEST_F(CounterTest, ThreeCoins) {
	auto image = load("coins3.png");
	EXPECT_EQ(3, counter.count(image).size());
}

TEST_F(CounterTest, NoCoins) {
	auto image = load("f.jpg");
	EXPECT_EQ(0, counter.count(image).size());
}
