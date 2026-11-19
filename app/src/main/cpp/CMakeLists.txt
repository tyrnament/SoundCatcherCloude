cmake_minimum_required(VERSION 3.22.1)
project(lamejni C)

add_library(lamejni SHARED
    lame_jni.c
    libmp3lame/bitstream.c
    libmp3lame/encoder.c
    libmp3lame/fft.c
    libmp3lame/gain_analysis.c
    libmp3lame/id3tag.c
    libmp3lame/lame.c
    libmp3lame/mpglib_interface.c
    libmp3lame/newmdct.c
    libmp3lame/presets.c
    libmp3lame/psymodel.c
    libmp3lame/quantize.c
    libmp3lame/quantize_pvt.c
    libmp3lame/reservoir.c
    libmp3lame/set_get.c
    libmp3lame/tables.c
    libmp3lame/takehiro.c
    libmp3lame/util.c
    libmp3lame/vbrquantize.c
    libmp3lame/VbrTag.c
    libmp3lame/version.c)

target_include_directories(lamejni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(lamejni PRIVATE STDC_HEADERS HAVE_STDINT_H)
target_compile_options(lamejni PRIVATE -O3 -w)
target_link_libraries(lamejni m)
