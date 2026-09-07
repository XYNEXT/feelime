set(Boost_FOUND TRUE)
file(GLOB Boost_INCLUDE_DIRS LIST_DIRECTORIES TRUE "${CMAKE_SOURCE_DIR}/boost/libs/*/include")
set(Boost_LIBRARIES Boost::regex)
