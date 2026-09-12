cmake_minimum_required(VERSION 3.22.1)

project("license" LANGUAGES CXX)

add_library(
    license
    SHARED
    license.cpp
)

target_compile_features(license PRIVATE cxx_std_17)

# ===================== 反逆向：符号/调试信息彻底清理 =====================
# -g0                        : 不生成调试信息（DWARF）
# -fvisibility=hidden        : 默认隐藏所有符号（非 extern "C" 导出）
# -fvisibility-inlines-hidden: 隐藏内联函数符号
# -ffunction-sections/-fdata-sections : 每个函数/数据独立 section，便于 --gc-sections 剔除
# -fno-unwind-tables/-fno-asynchronous-unwind-tables : 去掉异常展开表
# -fomit-frame-pointer       : 省略帧指针
target_compile_options(license PRIVATE
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
    -fno-ident                 # 不写入编译器版本标识
)

# 链接期：
#   --gc-sections  : 丢弃未被引用的 section
#   -s / --strip-all : 链接后 strip 掉 .symtab/.strtab（只保留 .dynsym 必需项）
#   --build-id=none: 去掉 build-id（避免关联构建信息）
target_link_options(license PRIVATE
    -Wl,--gc-sections
    -Wl,-s
    -Wl,--strip-all
    -Wl,--build-id=none
    -Wl,--exclude-libs,ALL
)

# 链接 Android log 库（用于 LOGW）
find_library(log-lib log)
target_link_libraries(license ${log-lib})

# ===================== 构建后强制 strip（双保险） =====================
# 即使上面的链接选项被工具链忽略，这一步也保证产物无符号表。
find_program(LLVM_STRIP llvm-strip)
if(NOT LLVM_STRIP)
    set(LLVM_STRIP ${CMAKE_STRIP})
endif()
if(LLVM_STRIP)
    add_custom_command(TARGET license POST_BUILD
        COMMAND ${LLVM_STRIP}
                --strip-all
                --remove-section=.comment
                --remove-section=.note
                --remove-section=.note.gnu.build-id
                $<TARGET_FILE:license>
        COMMENT "[anti-re] strip symbols from liblicense.so"
    )
endif()

# macOS / 主机调试时可额外：set(CMAKE_CXX_FLAGS_DEBUG ...) 但 CI 只跑 Release