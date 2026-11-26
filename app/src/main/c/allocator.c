#include <dlfcn.h>
#include <jni.h>
#include <malloc.h>

// Introduced after the minimum SDK. Resolve at runtime on the actual allocator.
JNIEXPORT jint JNICALL Java_dev_pagetrim_app_Cooperative_purgeAllocator(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    typedef int (*mallopt_fn)(int, int);
    mallopt_fn tune = (mallopt_fn)dlsym(RTLD_DEFAULT, "mallopt");
    return tune ? tune(M_PURGE, 0) : -1;
}
