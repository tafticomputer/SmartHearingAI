cmake_minimum_required(VERSION 3.18)
project(rnnoise_jni C)

set(RN ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)

add_library(rnnoise_jni SHARED
    rnnoise_jni.c
    ${RN}/src/denoise.c
    ${RN}/src/rnn.c
    ${RN}/src/pitch.c
    ${RN}/src/kiss_fft.c
    ${RN}/src/celt_lpc.c
    ${RN}/src/nnet.c
    ${RN}/src/nnet_default.c
    ${RN}/src/parse_lpcnet_weights.c
    ${RN}/src/rnnoise_data.c
    ${RN}/src/rnnoise_tables.c
)

target_include_directories(rnnoise_jni PRIVATE ${RN}/include ${RN}/src)
target_compile_options(rnnoise_jni PRIVATE -O3)
target_link_libraries(rnnoise_jni m)
