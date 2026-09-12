cmake_minimum_required(VERSION 3.22.1)

project("n" LANGUAGES CXX)

add_library(
    n
    SHARED
    license.cpp
)

target_compile_features(n PRIVATE cxx_std_17)

target_compile_options(n PRIVATE
    -O3
    -g0
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -fno-exceptions
    -fno-rtti
    -ffunction-sections
    -fdata-sections
    -fno-unwind-tables
    -fno-asynchronous-unwind-tables
    -fomit-frame-pointer
    -fno-ident
    -fno-stack-protector
)

target_link_options(n PRIVATE
    -Wl,--gc-sections
    -Wl,-s
    -Wl,--strip-all
    -Wl,--build-id=none
    -Wl,--exclude-libs,ALL
)

find_program(LP llvm-strip)
if(NOT LP)
    set(LP ${CMAKE_STRIP})
endif()
if(LP)
    add_custom_command(TARGET n POST_BUILD
        COMMAND ${LP}
                --strip-all
                --remove-section=.comment
                --remove-section=.note
                --remove-section=.note.gnu.build-id
                $<TARGET_FILE:n>
    )
endif()
