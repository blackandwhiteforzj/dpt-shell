//
// Created by luoyesiqiu
//

#include "dpt_risk.h"

DPT_ENCRYPT NO_INLINE void dpt_crash() {
#ifdef __aarch64__
    asm volatile(
            "mov x30,#0\t\n"
            );
#elif __arm__
    asm volatile(
        "mov lr,#0\t\n"
    );
#elif __i386__
    asm volatile(
            "ret\t\n"
            );
#elif __x86_64__
    asm volatile(
            "pop %rbp\t\n"
    );
#endif
}

DPT_ENCRYPT void junkCodeDexProtect(JNIEnv *env) {
    jclass klass = dpt::jni::FindClass(env,JUNK_CLASS_FULL_NAME);
    if(klass == nullptr) {
//        dpt_crash();
    }
}

[[noreturn]] DPT_ENCRYPT void *detectFridaOnThread(__unused void *args) {
    while (true) {
        int frida_so_count = find_in_maps(1,"frida-agent");
        if(frida_so_count > 0) {
            DLOGD("detectFridaOnThread found frida so");
            dpt_crash();
        }
        int frida_thread_count = find_in_threads_list(4
                ,"pool-frida"
                ,"gmain"
                ,"gdbus"
                ,"gum-js-loop");

        if(frida_thread_count >= 2) {
            DLOGD("detectFridaOnThread found frida threads");
            dpt_crash();
        }
        sleep(10);
    }
}


DPT_ENCRYPT void detectFrida() {
    pthread_t t;
    pthread_create(&t, nullptr,detectFridaOnThread,nullptr);
}

DPT_ENCRYPT void doPtrace() {
    __unused int ret = sys_ptrace(PTRACE_TRACEME,0,0,0);
    DLOGD("doPtrace result: %d",ret);
}

DPT_ENCRYPT void *protectProcessOnThread(void *args) {
    pid_t child = *((pid_t *)args);

    DLOGD("%s waitpid %d", __FUNCTION__ ,child);

    free(args);

    int pid = waitpid(child, nullptr, 0);
    if(pid > 0) {
        DLOGW("%s detect child process %d exited", __FUNCTION__, pid);
        dpt_crash();
    }
    DLOGD("%s waitpid %d end", __FUNCTION__ ,child);

    return nullptr;
}

DPT_ENCRYPT void protectChildProcess(pid_t pid) {
    pthread_t t;
    pid_t *child = (pid_t *) malloc(sizeof(pid_t));
    *child = pid;
    pthread_create(&t, nullptr,protectProcessOnThread,child);
}