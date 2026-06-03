//
// Created by luoyesiqiu
//

#ifndef DPT_DPT_HOOK_H
#define DPT_DPT_HOOK_H
#include <iostream>
#include <cstdint>
#include <jni.h>
#include <sys/mman.h>
#include <android/api-level.h>
#include <cstdint>
#include "dpt_util.h"
#include "dpt.h"
#include "dex/dex_file.h"
#include "common/dpt_log.h"
#include "common/dpt_macro.h"
#include "common/obfuscate.h"
#include "dobby.h"


void dpt_hook();

// 是否走了 LoadClass 降级路径(见 dpt_hook.cpp 中说明)。
extern bool g_loadClassFallback;

// 预还原全部受保护 dex 的 code item(仅 LoadClass 降级路径下使用)。
void patchAllProtectedDexes(JNIEnv *env, jobjectArray dexElements);

static void* (*g_originDefineClassV22)(void* thiz,
        void* self,
        const char* descriptor,
        size_t hash,
        void* class_loader,
        const void* dex_file,
        const void* dex_class_def);

static void* (*g_originDefineClassV21)(void* thiz,
                                    const char* descriptor,
                                    void* class_loader,
                                    const void* dex_file,
                                    const void* dex_class_def);


static void (*g_originLoadClassV23)(void* thiz,
                                       const void* self,
                                       const void* dex_file,
                                       const void* dex_class_def,
                                       const char* klass);
bool hook_LoadClass();
bool hook_DefineClass();
void hook_mmap();
void hook_execve();
void hook_write();
#endif //DPT_DPT_HOOK_H
