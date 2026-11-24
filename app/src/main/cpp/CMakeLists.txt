cmake_minimum_required(VERSION 3.18)
project(gbaia C CXX)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# mGBA se descarga en ./mgba (ver scripts/get-mgba.sh). Solo el núcleo de GBA, sin extras.
set(MGBA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/mgba)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(DISABLE_FRONTENDS ON CACHE BOOL "" FORCE)
set(DISABLE_DEPS ON CACHE BOOL "" FORCE)
set(M_CORE_GB OFF CACHE BOOL "" FORCE)
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(USE_DEBUGGERS OFF CACHE BOOL "" FORCE)
set(USE_GDB_STUB OFF CACHE BOOL "" FORCE)
set(USE_EDITLINE OFF CACHE BOOL "" FORCE)
set(ENABLE_SCRIPTING OFF CACHE BOOL "" FORCE)
set(USE_LUA OFF CACHE BOOL "" FORCE)
set(BUILD_GL OFF CACHE BOOL "" FORCE)
set(BUILD_GLES2 OFF CACHE BOOL "" FORCE)
set(BUILD_GLES3 ON CACHE BOOL "" FORCE)
set(USE_EPOXY OFF CACHE BOOL "" FORCE)
set(BUILD_LTO OFF CACHE BOOL "" FORCE)
add_subdirectory(${MGBA_DIR} mgba EXCLUDE_FROM_ALL)

# bsnes-hd (SNES con Mode 7 en HD) se descarga en ./bsnes-hd (ver scripts/get-bsnes.sh)
set(BSNES_DIR ${CMAKE_CURRENT_SOURCE_DIR}/bsnes-hd)
set(BSNES_SRC
	bsnes/target-libretro/libretro.cpp bsnes/emulator/emulator.cpp libco/libco.c bsnes/filter/filter.cpp
	bsnes/lzma/lzma.cpp bsnes/sfc/interface/interface.cpp bsnes/sfc/system/system.cpp
	bsnes/sfc/controller/controller.cpp bsnes/sfc/cartridge/cartridge.cpp bsnes/sfc/memory/memory.cpp
	bsnes/sfc/cpu/cpu.cpp bsnes/sfc/smp/smp.cpp bsnes/sfc/dsp/dsp.cpp bsnes/sfc/ppu/ppu.cpp
	bsnes/sfc/ppu-fast/ppu.cpp bsnes/sfc/expansion/expansion.cpp bsnes/sfc/coprocessor/coprocessor.cpp
	bsnes/sfc/slot/slot.cpp bsnes/gb/Core/apu.c bsnes/gb/Core/camera.c bsnes/gb/Core/display.c
	bsnes/gb/Core/gb.c bsnes/gb/Core/joypad.c bsnes/gb/Core/mbc.c bsnes/gb/Core/memory.c
	bsnes/gb/Core/printer.c bsnes/gb/Core/random.c bsnes/gb/Core/rewind.c bsnes/gb/Core/save_state.c
	bsnes/gb/Core/sgb.c bsnes/gb/Core/sm83_cpu.c bsnes/gb/Core/symbol_hash.c bsnes/gb/Core/timing.c
	bsnes/processor/arm7tdmi/arm7tdmi.cpp bsnes/processor/spc700/spc700.cpp bsnes/processor/wdc65816/wdc65816.cpp)
list(TRANSFORM BSNES_SRC PREPEND ${BSNES_DIR}/)
add_library(bsneshd STATIC ${BSNES_SRC})
target_include_directories(bsneshd PRIVATE ${BSNES_DIR} ${BSNES_DIR}/bsnes)
target_compile_definitions(bsneshd PRIVATE __LIBRETRO__ GB_INTERNAL DISABLE_DEBUGGER)
if(ANDROID)
	target_compile_definitions(bsneshd PRIVATE PLATFORM_ANDROID)
endif()
target_compile_options(bsneshd PRIVATE -O3 -fomit-frame-pointer -w $<$<COMPILE_LANGUAGE:CXX>:-std=c++17 -fexceptions -frtti>)

add_library(snescore STATIC snes_core.c)
target_link_libraries(snescore PUBLIC bsneshd)
target_compile_options(snescore PRIVATE -O2)

# Presentación con EGL/GLES y medición de GPU (compartido por GBA y SNES)
add_library(videocore STATIC video_gl.c gpu_timer.c)
if(ANDROID)
	target_link_libraries(videocore PUBLIC EGL GLESv3)
else()
	target_link_libraries(videocore PUBLIC EGL GLESv2)
endif()
target_compile_options(videocore PRIVATE -O2)

add_library(gbacore STATIC gba_core.c ai_gl.c ai_gl_v1.c npu_gl.c hybrid_gl.c)
target_link_libraries(gbacore PUBLIC mgba videocore)
target_compile_options(gbacore PRIVATE -O2)

if(ANDROID)
	add_library(gbaia SHARED jni.c names.c)
	target_link_libraries(gbaia gbacore android log)
	# SNES va en su propia biblioteca (núcleo C++ grande)
	add_library(snesia SHARED snes_jni.c)
	target_link_libraries(snesia snescore videocore android log)
	set_target_properties(snesia PROPERTIES LINKER_LANGUAGE CXX)
else()
	# Prueba en PC: corre una ROM y guarda capturas
	add_executable(gbatest test_host.c)
	target_link_libraries(gbatest gbacore)
	add_executable(gbahib test_hybrid.c)
	target_link_libraries(gbahib gbacore)
	add_executable(gbanpu test_npu.c)
	target_link_libraries(gbanpu gbacore)
	add_executable(gbaai test_ai.c)
	target_link_libraries(gbaai gbacore)
	add_executable(snestest test_snes.c)
	target_link_libraries(snestest snescore)
	set_target_properties(snestest PROPERTIES LINKER_LANGUAGE CXX)
endif()
