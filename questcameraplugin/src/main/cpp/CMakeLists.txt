cmake_minimum_required(VERSION 3.22.1)
project("questcameraplugin")

add_library(questcameraplugin SHARED
    questcamera_jni.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(questcameraplugin
    ${log-lib}
    ${android-lib})