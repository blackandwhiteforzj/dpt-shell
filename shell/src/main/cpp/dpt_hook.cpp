//
// Created by luoyesiqiu
//

#include <map>
#include <unordered_map>
#include <vector>
#include <sys/system_properties.h>
#include "dex/CodeItem.h"
#include "common/dpt_string.h"
#include "dpt_hook.h"
#include "dpt_risk.h"
#include "dpt_util.h"
#include "bytehook.h"

using namespace dpt;

extern std::unordered_map<int, std::vector<data::CodeItem*>*> dexMap;
std::map<int,uint8_t *> dexMemMap;

// 是否走了 LoadClass 降级路径(Android 16+ 上 ClassLinker::DefineClass 变更/内联,
// hook 会导致启动闪退,故 hook_DefineClass 失败后改 hook LoadClass)。
// 但新版 ART 上 LoadClass 也大量被内联/走 oat 快速路径,该 hook 几乎不命中
// (实测 Android 16 回填十几个 dex,Android 17 仅 1 个),导致部分受保护 dex 的
// code item 未还原而触发 VerifyError。该标志为真时,在 dex 挂载完成、应用类校验
// 之前主动预还原全部受保护 dex。
bool g_loadClassFallback = false;
int g_sdkLevel = 0;
extern ShellConfig g_shell_config;

void dpt_hook() {
    bytehook_init(BYTEHOOK_MODE_AUTOMATIC,false);
    g_sdkLevel = android_get_device_api_level();
    hook_execve();
    hook_mmap();
    hook_write();
    bool hookSuccess = hook_DefineClass();
    if(!hookSuccess) {
        g_loadClassFallback = true;
        hook_LoadClass();
    }
}

const char *GetArtLibPath() {
    if(g_sdkLevel < 29)
        return  "/system/" LIB_DIR "/libart.so" ;
    else if(g_sdkLevel == 29) {
        return "/apex/com.android.runtime/" LIB_DIR "/libart.so";
    }
    else {
        return "/apex/com.android.art/" LIB_DIR "/libart.so";
    }
}

const char *GetArtBaseLibPath() {
    if(g_sdkLevel == 29) {
        return "/apex/com.android.runtime/" LIB_DIR "/libartbase.so";
    }
    else {
        return "/apex/com.android.art/" LIB_DIR "/libartbase.so";
    }
}

const char *GetClassLinkerDefineClassLibPath(){
    return GetArtLibPath();
}

void change_dex_protective(uint8_t * begin,int dexSize,int dexIndex){
    if (begin == nullptr || dexSize <= 0) {
        DLOGW("skip mprotect dex[%d], begin=%p, dexSize=%d", dexIndex, begin, dexSize);
        return;
    }

    for(int i = 0;i < 10;) {
        int ret = dpt_mprotect(begin, begin + dexSize, PROT_READ | PROT_WRITE);

        if (ret != 0) {
            DLOGE("mprotect fail, address: %p, reason: %d!", begin, ret);
            i++;
        } else {
            dexMemMap.insert(std::pair<int,uint8_t *>(dexIndex,begin));
            DLOGD("mprotect success, address: %p.", begin);
            break;
        }
    }
}

DPT_ENCRYPT
ALWAYS_INLINE
void patchMethod(uint8_t *begin,
                             __unused const char *location,
                             uint32_t dexSize,
                             int dexIndex,
                             uint32_t methodIdx,
                             uint32_t codeOff) {

    auto dexIt = dexMap.find(dexIndex);
    if (LIKELY(dexIt != dexMap.end())) {
        auto dexMemIt = dexMemMap.find(dexIndex);
        if(UNLIKELY(dexMemIt == dexMemMap.end())){
            change_dex_protective(begin, dexSize, dexIndex);
        }

        auto codeItemVec = dexIt->second;
        auto codeItem = codeItemVec->at(methodIdx);
        if (LIKELY(codeItem != nullptr)) {
            if(codeOff == 0) {
                NLOG("dex: %d methodIndex: %d no need patch!",dexIndex,methodIdx);
                return;
            }

            auto *dexCodeItem = (dex::CodeItem *)(begin + codeOff);

            auto *realInsnsPtr = (uint8_t *)(dexCodeItem->insns_);

            NLOG("codeItem patch, methodIndex = %d, insnsSize = %d >>> %p(0x%x)",
                 codeItem->getMethodIdx(),
                 codeItem->getInsnsSize(),
                 realInsnsPtr,
                 (unsigned int)(realInsnsPtr - begin));

            uint32_t xorKey = g_shell_config.insns_xor_key;
            if (xorKey == 0) {
                memcpy(realInsnsPtr, codeItem->getInsns(), codeItem->getInsnsSize());
            } else {
                thread_local std::vector<uint8_t> tmp;
                uint32_t sz = codeItem->getInsnsSize();
                tmp.resize(sz);
                const uint8_t* enc = codeItem->getInsns();
                for (uint32_t i = 0; i < sz; i++) {
                    uint32_t shift = (i & 3u) << 3u;
                    tmp[i] = (uint8_t)(enc[i] ^ ((xorKey >> shift) & 0xffu));
                }
                memcpy(realInsnsPtr, tmp.data(), sz);
            }
        }
        else{
            NLOG("cannot find  methodId: %d in codeitem map, dex index: %d(%s)", methodIdx, dexIndex, location);
        }
    }
    else{
        DLOGW("cannot find dex: '%s' in dex map", location);
    }
}

DPT_ENCRYPT void patchClass(__unused const char* descriptor,
                 const void* dex_file,
                 const void* dex_class_def) {

    const char *junkClassName = AY_OBFUSCATE(JUNK_CLASS_FULL_NAME);
    if(descriptor != nullptr && UNLIKELY(dpt_strstr(descriptor, junkClassName) != nullptr)) {
        size_t descriptorLength = dpt_strlen(descriptor);
        char ch = descriptor[descriptorLength - 2];
        DLOGD("Attempt patch junk class %s ,char is '%c'",descriptor,ch);
        if(isdigit(ch)) {
            DLOGE("Find illegal call, desc: %s!", descriptor);
            dpt_crash();
            return;
        }

    }

    if(LIKELY(dex_file != nullptr)){
        std::string location;
        uint8_t *begin = nullptr;
        uint64_t dexSize = 0;
        if(g_sdkLevel >= 35) {
            auto* dexFileV35 = (V35::DexFile *)dex_file;
            location = dexFileV35->location_;
            begin = (uint8_t *)dexFileV35->begin_;
            dexSize = dexFileV35->header_->file_size_;
        }
        else if(g_sdkLevel >= __ANDROID_API_P__){
            auto* dexFileV28 = (V28::DexFile *)dex_file;
            location = dexFileV28->location_;
            begin = (uint8_t *)dexFileV28->begin_;
            dexSize = dexFileV28->size_ == 0 ? dexFileV28->header_->file_size_ : dexFileV28->size_;
        }
        else {
            auto* dexFileV21 = (V21::DexFile *)dex_file;
            location = dexFileV21->location_;
            begin = (uint8_t *)dexFileV21->begin_;
            dexSize = dexFileV21->size_ == 0 ? dexFileV21->header_->file_size_ : dexFileV21->size_;
        }

        if(location.rfind(DEXES_ZIP_NAME) != std::string::npos && dex_class_def){
            int dexIndex = parse_dex_number(location);

            auto* class_def = (dex::ClassDef *)dex_class_def;
            NLOG("class_desc = '%s', class_idx_ = 0x%x, class data off = 0x%x",descriptor,class_def->class_idx_,class_def->class_data_off_);

            if(LIKELY(class_def->class_data_off_ != 0)) {
                size_t read = 0;
                auto *class_data = (uint8_t *) ((uint8_t *) begin + class_def->class_data_off_);

                uint64_t static_fields_size = 0;
                read += DexFileUtils::readUleb128(class_data, &static_fields_size);

                uint64_t instance_fields_size = 0;
                read += DexFileUtils::readUleb128(class_data + read, &instance_fields_size);

                uint64_t direct_methods_size = 0;
                read += DexFileUtils::readUleb128(class_data + read, &direct_methods_size);

                uint64_t virtual_methods_size = 0;
                read += DexFileUtils::readUleb128(class_data + read, &virtual_methods_size);

                // staticFields
                read += DexFileUtils::getFieldsSize(class_data + read, static_fields_size);

                // instanceFields
                read += DexFileUtils::getFieldsSize(class_data + read, instance_fields_size);

                auto *directMethods = new dex::ClassDataMethod[direct_methods_size];
                read += DexFileUtils::readMethods(class_data + read, directMethods,
                                                  direct_methods_size);

                auto *virtualMethods = new dex::ClassDataMethod[virtual_methods_size];
                read += DexFileUtils::readMethods(class_data + read, virtualMethods,
                                                  virtual_methods_size);

                for (uint64_t i = 0; i < direct_methods_size; i++) {
                    auto method = directMethods[i];
                    patchMethod(begin, location.c_str(), dexSize, dexIndex,
                                method.method_idx_delta_, method.code_off_);
                }

                for (uint64_t i = 0; i < virtual_methods_size; i++) {
                    auto method = virtualMethods[i];
                    patchMethod(begin, location.c_str(), dexSize, dexIndex,
                                method.method_idx_delta_, method.code_off_);
                }

                delete[] directMethods;
                delete[] virtualMethods;
            }
            else {
                NLOG("class_def->class_data_off_ is zero");
            }
        }
    }
}

// 还原单个 dex 内全部类的全部方法 code item。供预还原(eager restore)使用,
// 逻辑与 patchClass 中按类还原的部分一致,只是遍历该 dex 的所有 class_def。
DPT_ENCRYPT
int patchAllClassesInDex(uint8_t *begin, const char *location, uint64_t dexSize, int dexIndex) {
    auto *header = (dex::Header *) begin;
    uint32_t classDefsSize = header->class_defs_size_;
    auto *classDefs = (dex::ClassDef *) (begin + header->class_defs_off_);

    int methodCandidates = 0;
    for (uint32_t c = 0; c < classDefsSize; c++) {
        dex::ClassDef *class_def = &classDefs[c];
        if (class_def->class_data_off_ == 0) {
            continue;
        }

        size_t read = 0;
        auto *class_data = (uint8_t *) (begin + class_def->class_data_off_);

        uint64_t static_fields_size = 0;
        read += DexFileUtils::readUleb128(class_data, &static_fields_size);

        uint64_t instance_fields_size = 0;
        read += DexFileUtils::readUleb128(class_data + read, &instance_fields_size);

        uint64_t direct_methods_size = 0;
        read += DexFileUtils::readUleb128(class_data + read, &direct_methods_size);

        uint64_t virtual_methods_size = 0;
        read += DexFileUtils::readUleb128(class_data + read, &virtual_methods_size);

        read += DexFileUtils::getFieldsSize(class_data + read, static_fields_size);
        read += DexFileUtils::getFieldsSize(class_data + read, instance_fields_size);

        auto *directMethods = new dex::ClassDataMethod[direct_methods_size];
        read += DexFileUtils::readMethods(class_data + read, directMethods, direct_methods_size);

        auto *virtualMethods = new dex::ClassDataMethod[virtual_methods_size];
        read += DexFileUtils::readMethods(class_data + read, virtualMethods, virtual_methods_size);

        for (uint64_t i = 0; i < direct_methods_size; i++) {
            if (directMethods[i].code_off_ != 0) methodCandidates++;
            patchMethod(begin, location, dexSize, dexIndex,
                        directMethods[i].method_idx_delta_, directMethods[i].code_off_);
        }
        for (uint64_t i = 0; i < virtual_methods_size; i++) {
            if (virtualMethods[i].code_off_ != 0) methodCandidates++;
            patchMethod(begin, location, dexSize, dexIndex,
                        virtualMethods[i].method_idx_delta_, virtualMethods[i].code_off_);
        }

        delete[] directMethods;
        delete[] virtualMethods;
    }
    return methodCandidates;
}

// 预还原:从 DexPathList$Element[] 的 DexFile cookie 里取出每个 art::DexFile*,
// 在应用类被校验之前一次性还原所有受保护 dex 的 code item。
// 仅在 LoadClass 降级路径下调用(Android 16+ 类加载 hook 命中不全),用于补齐
// hook 覆盖不到的 dex(如作为父类被链接解析的 Application 基类所在 dex)。
DPT_ENCRYPT
void patchAllProtectedDexes(JNIEnv *env, jobjectArray dexElements) {
    if (env == nullptr || dexElements == nullptr) {
        return;
    }

    jclass elementClass = env->FindClass("dalvik/system/DexPathList$Element");
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    jfieldID dexFileField = env->GetFieldID(elementClass, "dexFile", "Ldalvik/system/DexFile;");
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    jclass dexFileClass = env->FindClass("dalvik/system/DexFile");
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    jfieldID cookieField = env->GetFieldID(dexFileClass, "mCookie", "Ljava/lang/Object;");
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }

    [[maybe_unused]] int restoredDexCount = 0;
    jsize elemCount = env->GetArrayLength(dexElements);
    for (jsize e = 0; e < elemCount; e++) {
        jobject elementObj = env->GetObjectArrayElement(dexElements, e);
        if (elementObj == nullptr) {
            continue;
        }
        jobject dexFileObj = env->GetObjectField(elementObj, dexFileField);
        jobject cookieObj = (dexFileObj != nullptr)
                            ? env->GetObjectField(dexFileObj, cookieField) : nullptr;

        if (cookieObj != nullptr) {
            auto cookieArr = (jlongArray) cookieObj;
            jsize n = env->GetArrayLength(cookieArr);
            jlong *longs = env->GetLongArrayElements(cookieArr, nullptr);
            if (longs != nullptr) {
                DLOGI("eager: element[%d] cookie length = %d", e, n);
                // cookie[0] 是 oat 文件指针;cookie[1..] 为 art::DexFile*,且严格按
                // dex 在 zip 内的顺序排列(classes.dex, classes2.dex, ...),与抽取顺序、
                // 也就是 dexMap 的 key 0..6 一一对应。
                //
                // 关键:不要再用 parse_dex_number(location) 推断 dexIndex —— 在
                // Android 17 上 DexFile.location_ 不再带 "!classesN.dex" 后缀,会导致
                // 所有 dex 都被解析成 index 0,只有 dex0 被正确还原(这正是 A16 有十几次
                // change_dex_protective、A17 只有 1 次的根因)。改为按 cookie 顺序分配。
                for (jsize k = 1; k < n; k++) {
                    auto dexFilePtr = (const void *) (intptr_t) longs[k];
                    if (dexFilePtr == nullptr) {
                        continue;
                    }
                    // begin_ 在 V21/V28/V35 里都紧跟 vtable(偏移 8),先取出做 magic 校验,
                    // 确认是真正的 DexFile*,避免误把其它指针当 dex 解析导致崩溃。
                    auto *begin = (uint8_t *) ((V35::DexFile *) dexFilePtr)->begin_;
                    if (begin == nullptr ||
                        !(begin[0] == 'd' && begin[1] == 'e' && begin[2] == 'x' && begin[3] == '\n')) {
                        DLOGI("eager: cookie[%d] begin=%p not a dex, skip", k, begin);
                        continue;
                    }

                    std::string location;
                    uint64_t dexSize = 0;
                    if (g_sdkLevel >= 35) {
                        auto *d = (V35::DexFile *) dexFilePtr;
                        location = d->location_;
                        dexSize = d->header_ != nullptr ? d->header_->file_size_ : 0;
                    } else if (g_sdkLevel >= __ANDROID_API_P__) {
                        auto *d = (V28::DexFile *) dexFilePtr;
                        location = d->location_;
                        dexSize = d->size_ == 0 ? d->header_->file_size_ : d->size_;
                    } else {
                        auto *d = (V21::DexFile *) dexFilePtr;
                        location = d->location_;
                        dexSize = d->size_ == 0 ? d->header_->file_size_ : d->size_;
                    }

                    if (location.rfind(DEXES_ZIP_NAME) == std::string::npos) {
                        DLOGI("eager: cookie[%d] not protected zip, loc=%s", k, location.c_str());
                        continue;
                    }

                    // 用(已修复的)parse_dex_number 从 location 的 "!N" 后缀解析下标,
                    // 与 dexMap key 严格对齐。不用 cookie 顺序:cookie 里可能混入额外/非
                    // 受保护 dex,按顺序会整体错位。
                    int dexIndex = parse_dex_number(location);
                    if (dexMap.find(dexIndex) == dexMap.end()) {
                        DLOGI("eager: dex index %d (loc=%s) not in dexMap, skip", dexIndex, location.c_str());
                        continue;
                    }
                    [[maybe_unused]] int candidates =
                            patchAllClassesInDex(begin, location.c_str(), dexSize, dexIndex);
                    DLOGI("eager restore dex[%d] begin=%p size=%llu candidates=%d loc=%s",
                          dexIndex, begin, (unsigned long long) dexSize, candidates, location.c_str());
                    restoredDexCount++;
                }
                env->ReleaseLongArrayElements(cookieArr, longs, JNI_ABORT);
            }
            env->DeleteLocalRef(cookieObj);
        }

        if (dexFileObj != nullptr) {
            env->DeleteLocalRef(dexFileObj);
        }
        env->DeleteLocalRef(elementObj);
    }
    DLOGI("patchAllProtectedDexes done, eager restored %d dex(es), dexMap size = %zu",
          restoredDexCount, dexMap.size());
}

DPT_ENCRYPT void LoadClassV23(void* thiz,
                               const void* self,
                               const void* dex_file,
                               const void* dex_class_def,
                               const char* klass) {
    if(LIKELY(g_originLoadClassV23 != nullptr)) {
        patchClass(nullptr,dex_file,dex_class_def);
        g_originLoadClassV23(thiz, self, dex_file, dex_class_def, klass);
    }
}

DPT_ENCRYPT bool hook_LoadClass() {
    if(g_sdkLevel < __ANDROID_API_M__) {
        return false;
    }

    void* loadClassAddress = nullptr;

    char sym[256] = {0};
    find_symbol_in_elf_file(GetClassLinkerDefineClassLibPath(), sym, ARRAY_LENGTH(sym), 2, "ClassLinker", "LoadClass");

    loadClassAddress = DobbySymbolResolver(GetArtLibPath(), sym);

    int hookResult = DobbyHook(loadClassAddress, (dobby_dummy_func_t) LoadClassV23, (dobby_dummy_func_t *) &g_originLoadClassV23);

    DLOGD("hook result: %d", hookResult);
    return hookResult == 0;
}

DPT_ENCRYPT void *DefineClassV22(void* thiz,void* self,
                 const char* descriptor,
                 size_t hash,
                 void* class_loader,
                 const void* dex_file,
                 const void* dex_class_def) {

    if(LIKELY(g_originDefineClassV22 != nullptr)) {

        patchClass(descriptor,dex_file,dex_class_def);

        return g_originDefineClassV22( thiz,self,descriptor,hash,class_loader, dex_file, dex_class_def);

    }
    return nullptr;
}

DPT_ENCRYPT void *DefineClassV21(void* thiz,
                     const char* descriptor,
                     void* class_loader,
                     const void* dex_file,
                     const void* dex_class_def) {

    if(LIKELY(g_originDefineClassV21 != nullptr)) {
        patchClass(descriptor,dex_file,dex_class_def);
        return g_originDefineClassV21( thiz,descriptor,class_loader, dex_file, dex_class_def);

    }
    return nullptr;
}

DPT_ENCRYPT bool hook_DefineClass() {
    char sym[256] = {0};
    find_symbol_in_elf_file(GetClassLinkerDefineClassLibPath(), sym, ARRAY_LENGTH(sym), 2, "ClassLinker", "DefineClass");

    if(strlen(sym) == 0) {
        DLOGW("cannot find symbol: DefineClass");
        return false;
    }

    void* defineClassAddress = DobbySymbolResolver(GetClassLinkerDefineClassLibPath(), sym);

    if(defineClassAddress == nullptr) {
        DLOGE("defineClass address is null, sym: %s", sym);
        return false;
    }

    int hookResult;
    if(g_sdkLevel >= __ANDROID_API_L_MR1__) {
        hookResult = DobbyHook(defineClassAddress, (dobby_dummy_func_t) DefineClassV22, (dobby_dummy_func_t *) &g_originDefineClassV22);
    }
    else {
        hookResult = DobbyHook(defineClassAddress, (dobby_dummy_func_t) DefineClassV21, (dobby_dummy_func_t *) &g_originDefineClassV21);
    }

    if(hookResult == 0) {
        DLOGD("hook success.");
        return true;
    }
    else {
        DLOGE("hook fail!");
        return false;
    }
}

const char *getArtLibName() {
    if (g_sdkLevel >= 29) {
        return "libartbase.so";
    }
    return "libart.so";
}

DPT_ENCRYPT void* fake_mmap(void* __addr, size_t __size, int __prot, int __flags, int __fd, off_t __offset){
    BYTEHOOK_STACK_SCOPE();

    int prot = __prot;
    int hasRead = (__prot & PROT_READ) == PROT_READ;
    int hasWrite = (__prot & PROT_WRITE) == PROT_WRITE;

    char fd_path[256] = {0};
    dpt_readlink(__fd,fd_path, ARRAY_LENGTH(fd_path));

    std::string fd_path_str = fd_path;
    if(checkWebViewInFilename(fd_path_str)) {
        DLOGW("link path: %s, no need to change prot",fd_path);
        goto tail;
    }

    if(hasRead && !hasWrite) {
        prot = prot | PROT_WRITE;
        DLOGD("append write flag fd = %d, size = %zu, prot = %d, flag = %d",__fd,__size, prot,__flags);
    }

    if(g_sdkLevel == 30){
        if(strstr(fd_path,"base.vdex") != nullptr){
            DLOGE("want to mmap base.vdex");
            __flags = 0;
        }
    }
    tail:
    void *addr = BYTEHOOK_CALL_PREV(fake_mmap,__addr,  __size, prot,  __flags,  __fd,  __offset);
    return addr;
}

DPT_ENCRYPT void hook_mmap(){
    bytehook_stub_t stub = bytehook_hook_single(
            getArtLibName(),
            "libc.so",
            "mmap",
            (void*)fake_mmap,
            nullptr,
            nullptr);
    if(stub != nullptr){
        DLOGD("mmap hook success!");
    }
    else {
        DLOGE("mmap hook fail!");
    }
}

DPT_ENCRYPT int fake_execve(const char *pathname, char *const argv[], char *const envp[]) {
    BYTEHOOK_STACK_SCOPE();
    DLOGD("execve hooked: %s", pathname);
    if (strstr(pathname, "dex2oat") != nullptr) {
        DLOGD("execve blocked: %s", pathname);
        errno = EACCES;
        return -1;
    }
    return BYTEHOOK_CALL_PREV(fake_execve, pathname, argv, envp);
}

DPT_ENCRYPT ssize_t fake_write(int fd, const void *const buf, size_t count) {
    BYTEHOOK_STACK_SCOPE();

    if(buf != nullptr && count > 0x70) {
        uint8_t dex_magic[] = {0x64, 0x65, 0x78, 0x0a};

        if (UNLIKELY(dpt_memcmp(buf, dex_magic, 4) == 0)) {

            std::string hex = to_hex((uint8_t *) buf + 9, 20);
            DLOGD("dex sign: %s", hex.c_str());
            if (dpt_strncasecmp(hex.c_str(), g_shell_config.dex_sign.c_str(), 40) == 0) {
                dpt_crash();
            }
        }
    }

    return BYTEHOOK_CALL_PREV(fake_write, fd, buf, count);
}


DPT_ENCRYPT void hook_execve(){
    bytehook_stub_t stub = bytehook_hook_single(
            getArtLibName(),
            "libc.so",
            "execve",
            (void *) fake_execve,
            nullptr,
            nullptr);
    if (stub != nullptr) {
        DLOGD("execve hook success!");
    }
    else {
        DLOGE("execve hook fail!");
    }
}


DPT_ENCRYPT void hook_write(){
    bytehook_stub_t stub = bytehook_hook_all(
            "libc.so",
            "write",
            (void *) fake_write,
            nullptr,
            nullptr);
    if (stub != nullptr) {
        DLOGD("write hook success!");
    }
    else {
        DLOGE("write hook fail!");
    }
}
