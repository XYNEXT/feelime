cmake_minimum_required(VERSION 3.22)
project(feelime_hunspell LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(HUNSPELL_SOURCES
    hunspell/src/hunspell/affentry.cxx
    hunspell/src/hunspell/affixmgr.cxx
    hunspell/src/hunspell/csutil.cxx
    hunspell/src/hunspell/filemgr.cxx
    hunspell/src/hunspell/hashmgr.cxx
    hunspell/src/hunspell/hunspell.cxx
    hunspell/src/hunspell/hunspelltrace.cxx
    hunspell/src/hunspell/hunzip.cxx
    hunspell/src/hunspell/phonet.cxx
    hunspell/src/hunspell/replist.cxx
    hunspell/src/hunspell/suggestmgr.cxx)

add_library(feelime_hunspell SHARED feelime_hunspell_bridge.cpp ${HUNSPELL_SOURCES})
target_include_directories(feelime_hunspell PRIVATE hunspell/src/hunspell)
target_compile_definitions(feelime_hunspell PRIVATE BUILDING_LIBHUNSPELL)
target_compile_options(feelime_hunspell PRIVATE -fvisibility=hidden)
target_link_options(feelime_hunspell PRIVATE "LINKER:-z,max-page-size=16384")

