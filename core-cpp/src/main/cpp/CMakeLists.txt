cmake_minimum_required(VERSION 3.22.1)
project(share64_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(S64_SOURCES
    src/engine.cpp
    src/net.cpp
    src/protocol.cpp
    src/receiver.cpp
    src/sender.cpp
    src/session.cpp
    src/sha256.cpp
    src/sha256_arm.cpp)

# ARMv8 SHA-256 instructions: only this translation unit gets the crypto flag; use is runtime-gated.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64" OR ANDROID_ABI STREQUAL "arm64-v8a")
  set_source_files_properties(src/sha256_arm.cpp PROPERTIES COMPILE_FLAGS "-march=armv8-a+crypto")
endif()

function(s64_common_options target)
  target_include_directories(${target} PUBLIC include PRIVATE src)
  target_compile_definitions(${target} PRIVATE _FILE_OFFSET_BITS=64)
  target_compile_options(${target} PRIVATE -O3 -Wall -Wextra -Wno-unused-parameter
                                         -ffunction-sections -fdata-sections)
endfunction()

if(ANDROID)
  add_library(share64_core SHARED ${S64_SOURCES} src/jni_bridge.cpp)
  s64_common_options(share64_core)
  target_compile_options(share64_core PRIVATE -fvisibility=hidden)
  find_library(log-lib log)
  # 16 KB page alignment is required for Android 15+ devices / Play Store.
  target_link_libraries(share64_core PRIVATE ${log-lib}
                        -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
else()
  # Host build used for the loopback / fault-injection test suite (no JNI).
  find_package(Threads REQUIRED)
  add_library(share64_core STATIC ${S64_SOURCES})
  s64_common_options(share64_core)
  target_link_libraries(share64_core PUBLIC Threads::Threads)
  add_executable(share64_host_test ../../hosttest/host_test.cpp)
  target_link_libraries(share64_host_test PRIVATE share64_core)
endif()
