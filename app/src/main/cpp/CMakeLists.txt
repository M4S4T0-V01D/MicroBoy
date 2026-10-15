cmake_minimum_required(VERSION 3.22.1)
project(microboy_native C)

# --- mGBA (MPL-2.0), consumed unmodified from the git submodule -------------
set(MGBA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/mgba)
set(LIBMGBA_ONLY ON CACHE BOOL "" FORCE)
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(M_CORE_GB OFF CACHE BOOL "" FORCE)
set(USE_DEBUGGERS OFF CACHE BOOL "" FORCE)
set(USE_GDB_STUB OFF CACHE BOOL "" FORCE)
set(USE_LUA OFF CACHE BOOL "" FORCE)
set(USE_ELF OFF CACHE BOOL "" FORCE)
set(USE_LZMA OFF CACHE BOOL "" FORCE)
set(USE_MINIZIP OFF CACHE BOOL "" FORCE)
set(USE_LIBZIP OFF CACHE BOOL "" FORCE)
set(USE_EPOXY OFF CACHE BOOL "" FORCE)
set(BUILD_GL OFF CACHE BOOL "" FORCE)
set(BUILD_GLES2 OFF CACHE BOOL "" FORCE)
set(BUILD_GLES3 OFF CACHE BOOL "" FORCE)
set(BUILD_LTO OFF CACHE BOOL "" FORCE)
add_subdirectory(${MGBA_DIR} mgba EXCLUDE_FROM_ALL)

# --- JNI bridge ------------------------------------------------------------
add_library(microboy SHARED
    jni_bridge.c
    audio.c
    link.c
)
target_include_directories(microboy PRIVATE
    ${MGBA_DIR}/include
    ${MGBA_DIR}/src
    ${CMAKE_CURRENT_BINARY_DIR}/mgba/include
)
# Feature flags come from mGBA's generated <mgba/flags.h> so struct layouts match the library.
target_compile_definitions(microboy PRIVATE __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
target_compile_options(microboy PRIVATE -Wall -O3 -Werror=unguarded-availability)
target_link_libraries(microboy mgba aaudio log android)
target_link_options(microboy PRIVATE "-Wl,-z,max-page-size=16384")
