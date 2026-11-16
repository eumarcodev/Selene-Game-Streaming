cmake_minimum_required(VERSION 3.22)
project(selene-pyrowave LANGUAGES C CXX)

# PyroWave and the small subset of Granite it needs come from the git submodule.
set(PW ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/pyrowave)

set(CMAKE_CXX_STANDARD 17)
set(GRANITE_VULKAN_SHADER_MANAGER_RUNTIME_COMPILER OFF CACHE BOOL "" FORCE)
set(GRANITE_SHADER_COMPILER_OPTIMIZE OFF CACHE BOOL "" FORCE)
set(GRANITE_POSITION_INDEPENDENT ON CACHE BOOL "" FORCE)
set(GRANITE_SHIPPING ON CACHE BOOL "" FORCE)
set(GRANITE_VULKAN_SPIRV_CROSS OFF CACHE BOOL "" FORCE)
set(GRANITE_VULKAN_SYSTEM_HANDLES OFF CACHE BOOL "" FORCE)
set(GRANITE_RENDERER OFF CACHE BOOL "" FORCE)
set(GRANITE_VULKAN_FOSSILIZE OFF CACHE BOOL "" FORCE)
set(GRANITE_PLATFORM "null" CACHE STRING "" FORCE)
add_subdirectory(${PW}/Granite granite EXCLUDE_FROM_ALL)
add_subdirectory(${PW} pyrowave EXCLUDE_FROM_ALL)

# One library: the codec C API, the scaler it needs, and the Selene presentation glue (JNI).
add_library(pyrowave-shared SHARED
    ${PW}/pyrowave_c.cpp
    ${PW}/Granite/video/scaler.cpp
    src/main/cpp/pyrowave_player.cpp)
target_link_libraries(pyrowave-shared PRIVATE pyrowave granite-vulkan granite-math granite-volk-headers android log)
target_compile_definitions(pyrowave-shared PRIVATE PYROWAVE_EXPORT_SYMBOLS)
target_include_directories(pyrowave-shared PRIVATE ${PW} ${PW}/shaders ${PW}/Granite/video src/main/cpp)
