# The app's own native code: pe7z, the 7z extraction for HD texture packs, on the LZMA SDK (lzma/).
cmake_minimum_required(VERSION 3.22.1)
project(pe7z C)

add_library(pe7z SHARED
        pe7z.c
        pe7z_jni.c
        lzma/7zAlloc.c
        lzma/7zArcIn.c
        lzma/7zBuf.c
        lzma/7zCrc.c
        lzma/7zCrcOpt.c
        lzma/7zDec.c
        lzma/7zStream.c
        lzma/Bcj2.c
        lzma/Bra.c
        lzma/Bra86.c
        lzma/BraIA64.c
        lzma/CpuArch.c
        lzma/Delta.c
        lzma/Lzma2Dec.c
        lzma/LzmaDec.c
        lzma/Ppmd7.c
        lzma/Ppmd7Dec.c)

target_compile_options(pe7z PRIVATE -O2 -Wall -Wno-unused-parameter)
