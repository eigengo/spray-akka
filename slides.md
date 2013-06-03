!SLIDE

#Akka in heterogeneous environments

!SLIDE

#Counting coins

We have a video with coins on a table. We want to process the video, count the coins, and send IM on change of the count. 

In summary, we are building this system:

H.264 -- (HTTP) --> Scala/Akka -- (AMQP) --> C++/CUDA 
                        |                       |
                        +<------- (AMQP) -------+
                        |
                        +------- (Jabber) ------> 

The main components:

* Scala & Akka processing core (Maintain multiple recognition sessions, deal with integration points.)
* Spray for the REST API (Chunked HTTP posts to receive the video.)
* AMQP connecting the native components (Array[Byte] out; JSON in.)
* C++ / CUDA for the image processing (OpenCV with CUDA build. Coin counting logic.)
* iOS client that sends the H.264 stream (Make chunked POST request to the Spray endpoint.)

#Simple Akka app
Just the command loop.


