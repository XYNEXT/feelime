cmake_minimum_required(VERSION 3.22)
project(feelime_native_smoke LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(FEELIME_PREBUILT_DIR "" CACHE PATH "Directory containing pinned engine libraries")
if(NOT FEELIME_PREBUILT_DIR)
  message(FATAL_ERROR "FEELIME_PREBUILT_DIR is required")
endif()

add_library(feelime_rime SHARED IMPORTED)
set_target_properties(feelime_rime PROPERTIES
    IMPORTED_LOCATION "${FEELIME_PREBUILT_DIR}/libfeelime_rime.so")
add_library(feelime_hunspell SHARED IMPORTED)
set_target_properties(feelime_hunspell PROPERTIES
    IMPORTED_LOCATION "${FEELIME_PREBUILT_DIR}/libfeelime_hunspell.so")

add_library(feelime_smoke SHARED native_smoke.cpp)
target_link_libraries(feelime_smoke PRIVATE feelime_rime feelime_hunspell log)
target_link_options(feelime_smoke PRIVATE "LINKER:-z,max-page-size=16384")
