cmake_minimum_required(VERSION 3.4.1)
project(zanokit)

message(STATUS "ABI = ${ANDROID_ABI}")
set(CMAKE_VERBOSE_MAKEFILE ON)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_library(zanokit SHARED
    src/main/cpp/zano_jni.cpp
    src/main/cpp/wallet2_api_c.cpp
    src/main/cpp/helpers.cpp
)

set(EXTERNAL_LIBS_DIR ${CMAKE_SOURCE_DIR}/external-libs)
message(STATUS "EXTERNAL_LIBS_DIR = ${EXTERNAL_LIBS_DIR}")

include_directories(${EXTERNAL_LIBS_DIR}/include)

target_compile_definitions(zanokit PRIVATE ZANO_LIBS_AVAILABLE=1)

find_library(log-lib log)

############
# OpenSSL
############

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libcrypto.a)

add_library(ssl STATIC IMPORTED)
set_target_properties(ssl PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libssl.a)

############
# Boost
############

add_library(boost_atomic STATIC IMPORTED)
set_target_properties(boost_atomic PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libboost_atomic.a)

add_library(boost_chrono STATIC IMPORTED)
set_target_properties(boost_chrono PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libboost_chrono.a)

add_library(boost_date_time STATIC IMPORTED)
set_target_properties(boost_date_time PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libboost_date_time.a)

add_library(boost_filesystem STATIC IMPORTED)
set_target_properties(boost_filesystem PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libboost_filesystem.a)

add_library(boost_program_options STATIC IMPORTED)
set_target_properties(boost_program_options PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libboost_program_options.a)

add_library(boost_regex STATIC IMPORTED)
set_target_properties(boost_regex PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libboost_regex.a)

add_library(boost_serialization STATIC IMPORTED)
set_target_properties(boost_serialization PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libboost_serialization.a)

add_library(boost_system STATIC IMPORTED)
set_target_properties(boost_system PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libboost_system.a)

add_library(boost_thread STATIC IMPORTED)
set_target_properties(boost_thread PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libboost_thread.a)

add_library(boost_timer STATIC IMPORTED)
set_target_properties(boost_timer PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libboost_timer.a)

add_library(boost_wserialization STATIC IMPORTED)
set_target_properties(boost_wserialization PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libboost_wserialization.a)

############
# Zano
############

add_library(zano_wallet STATIC IMPORTED)
set_target_properties(zano_wallet PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libwallet.a)

add_library(zano_currency_core STATIC IMPORTED)
set_target_properties(zano_currency_core PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libcurrency_core.a)

add_library(zano_common STATIC IMPORTED)
set_target_properties(zano_common PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libcommon.a)

add_library(zano_crypto STATIC IMPORTED)
set_target_properties(zano_crypto PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libzano_crypto.a)

add_library(zlib STATIC IMPORTED)
set_target_properties(zlib PROPERTIES IMPORTED_LOCATION
    ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/libz.a)

############
# System
############

target_link_libraries(zanokit
    zano_wallet
    zano_currency_core
    zano_common
    zano_crypto
    zlib

    boost_atomic
    boost_chrono
    boost_date_time
    boost_filesystem
    boost_program_options
    boost_regex
    boost_serialization
    boost_system
    boost_thread
    boost_timer
    boost_wserialization

    ssl
    crypto

    ${log-lib}
)
