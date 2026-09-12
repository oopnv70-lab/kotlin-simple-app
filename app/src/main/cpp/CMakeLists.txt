cmake_minimum_required(VERSION 3.22.1)

project("license" LANGUAGES CXX)

add_library(
    license
    SHARED
    license.cpp
)

target_compile_features(license PRIVATE cxx_std_17)

# 优化 + 符号尽量精简（增加逆向难度；这里不做 strip，交给 AGP 的 release 配置）
target_compile_options(license PRIVATE
    -O2
    -fvisibility=hidden        # 隐藏默认导出符号，减少暴露面
    -fno-exceptions            # 关闭异常，减小体积
    -fno-rtti                  # 关闭 RTTI，减小体积
)

# 链接 Android log 库（用于 LOGW）
find_library(log-lib log)
target_link_libraries(license ${log-lib})