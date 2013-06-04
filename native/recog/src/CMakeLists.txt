set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/..)

add_executable(recog main.cpp recog.cpp coins.cpp faces.cpp ../../../jzon.cpp ../../../rabbit.cpp ../../../im.cpp)
target_link_libraries(recog ${Rabbitmqc_LIBRARY} ${Boost_LIBRARIES} ${SimpleAmqpClient_LIBRARY} ${OpenCV_LIBS})