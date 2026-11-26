// This library works only on a descriptor already opened read-only by the system picker.
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <unistd.h>

enum operation { INSPECT = 0, EVICT = 1, SYNC_FILE = 2 };

static jlongArray fail(JNIEnv *env, const char *detail, int err) {
    char message[320];
    if (err == 0) snprintf(message, sizeof(message), "%s", detail);
    else snprintf(message, sizeof(message), "%s（errno %d）", detail, err);
    jclass exception = (*env)->FindClass(env, "java/io/IOException");
    if (exception) (*env)->ThrowNew(env, exception, message);
    return NULL;
}

static int local_path(const char *path) {
    static const char *const allowed[] = {
        "/storage/", "/mnt/user/", "/mnt/runtime/", "/mnt/pass_through/",
        "/mnt/media_rw/", "/mnt/installer/", "/mnt/androidwritable/"
    };
    for (size_t i = 0; i < sizeof(allowed) / sizeof(allowed[0]); ++i)
        if (strncmp(path, allowed[i], strlen(allowed[i])) == 0) return 1;
    return 0;
}

// FUSE can represent either emulated storage or a proxy/computed document.
// Even for local FUSE descriptors, the lower backing cache isn't measurable here.
static int classify_filesystem(int fd, const char *fdpath) {
    struct statfs fs;
    if (fstatfs(fd, &fs) != 0) return 0;
    switch ((uint32_t)fs.f_type) {
    case 0xef53:     // ext2/3/4
    case 0xf2f52010: // F2FS
    case 0x2011bab0: // exFAT
    case 0x4d44:     // FAT
    case 0xe0f5e1e2: // EROFS
        return 1;
    case 0x65735546: // FUSE
    case 0x5dca2df5: { // sdcardfs
        char path[PATH_MAX];
        ssize_t len = readlink(fdpath, path, sizeof(path) - 1);
        if (len <= 0 || len == (ssize_t)(sizeof(path) - 1)) return 0;
        path[len] = '\0';
        return local_path(path) ? 2 : 0;
    }
    default:
        return 0;
    }
}

static int credible(const struct stat *st, const char *fdpath) {
    // Linux may return an all-ones vector if mincore's inode ownership/write check fails.
    // access() uses the real identity. Only rely on it with the normal app's matching IDs.
    return st->st_uid == geteuid()
        || (getuid() == geteuid() && getgid() == getegid() && access(fdpath, W_OK) == 0);
}

#include "file_cache_common.h"

JNIEXPORT jlongArray JNICALL
Java_dev_pagetrim_app_FilesScope_operate(JNIEnv *env, jclass unused, jint fd, jint mode) {
    (void)unused;
    if (mode < INSPECT || mode > SYNC_FILE) return fail(env, "未知的文件操作。", 0);
    struct stat before, after;
    if (fstat(fd, &before) < 0) return fail(env, "无法核实文件身份。", errno);
    if (!S_ISREG(before.st_mode) || before.st_size < 0 || lseek(fd, 0, SEEK_CUR) < 0)
        return fail(env, "请选择可定位的本机普通文件。管道或虚拟文档无法定向处理。", 0);
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || (flags & O_ACCMODE) != O_RDONLY)
        return fail(env, "未取得预期的只读文件描述符。", 0);
    char fdpath[64];
    snprintf(fdpath, sizeof(fdpath), "/proc/self/fd/%d", fd);
    int storage = classify_filesystem(fd, fdpath);
    if (!storage) return fail(env, "未识别为直接本机文件或本机共享存储，已跳过。", 0);
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0 || page_size > (1 << 20))
        return fail(env, "系统页大小无法核实。", 0);
    const off_t max_sample = LEAF_SAMPLE_LIMIT;
    size_t bytes = before.st_size < max_sample ? (size_t)before.st_size : (size_t)max_sample;
    jlong result[] = { before.st_size, page_size, (jlong)bytes, -1, -1, 0 };
    if (mode == SYNC_FILE) {
        int rc;
        do { rc = fsync(fd); } while (rc != 0 && errno == EINTR);
        if (rc != 0) return fail(env, "文件系统拒绝同步此文件。", errno);
    } else {
        if (storage == 1 && credible(&before, fdpath))
            result[3] = leaf_sample(fd, bytes, (size_t)page_size);
        if (mode == EVICT && before.st_size != 0) {
            // len=0 advises through EOF. The kernel may skip busy/dirty pages.
            int rc = posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
            if (rc) return fail(env, "文件系统拒绝文件缓存逐出建议。", rc);
            if (result[3] >= 0) result[4] = leaf_sample(fd, bytes, (size_t)page_size);
        }
        if (fstat(fd, &after) != 0 || !leaf_same_stat(&before, &after)) result[5] = 1;
        else if (result[3] >= 0 && !credible(&after, fdpath)) result[3] = result[4] = -1;
    }
    jlongArray output = (*env)->NewLongArray(env, sizeof(result) / sizeof(result[0]));
    if (output) (*env)->SetLongArrayRegion(env, output, 0,
        sizeof(result) / sizeof(result[0]), result);
    return output;
}
