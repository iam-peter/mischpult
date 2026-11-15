cmake_minimum_required(VERSION 3.22.1)
project(mischpult_engine LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

# SoundTouch (LGPL 2.1, vendored): time-stretch for key lock
file(GLOB SOUNDTOUCH_SOURCES third_party/soundtouch/source/*.cpp)

add_library(mischpult_engine SHARED
    jni.cpp
    Track.cpp
    Engine.cpp
    Deck.cpp
    ${SOUNDTOUCH_SOURCES}
)
target_include_directories(mischpult_engine PRIVATE third_party/soundtouch/include)
set_source_files_properties(${SOUNDTOUCH_SOURCES} PROPERTIES COMPILE_OPTIONS "-w")
target_compile_options(mischpult_engine PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(mischpult_engine oboe::oboe android log)
