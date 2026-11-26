// Execute the actual helper with syscalls replaced by a one-page ashmem driver.
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/capability.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static const char *driver;
static int opens, closes, pinned, purges, mapped;
static unsigned char page[4096];

static long mock_capget(long nr, void *header, struct __user_cap_data_struct *caps) {
    (void)nr; (void)header;
    caps[0].effective = strcmp(driver, "nocap") ? 1U << CAP_SYS_ADMIN : 0;
    return 0;
}
static int mock_open(const char *path, int flags) {
    (void)flags; assert(!strcmp(path, "/dev/ashmem")); pinned = 1; return ++opens;
}
static int mock_close(int fd) { assert(fd == ++closes); return 0; }
static int mock_fstat(int fd, struct stat *stat) { (void)fd; stat->st_mode = S_IFCHR; return 0; }
static void *mock_mmap(void *addr, size_t size, int prot, int flags, int fd, off_t off) {
    (void)addr; (void)size; (void)prot; (void)flags; (void)fd; (void)off;
    mapped = 1; return page;
}
static int mock_munmap(void *addr, size_t size) {
    (void)size; assert(addr == page && mapped); mapped = 0; return 0;
}
static int mock_ioctl(int fd, unsigned long request, ...);

#define main helper_main
#define open mock_open
#define close mock_close
#define fstat mock_fstat
#define mmap mock_mmap
#define munmap mock_munmap
#define ioctl mock_ioctl
#define syscall mock_capget
#include "../app/src/main/c/ashmem_tool.c"
#undef main

static int mock_ioctl(int fd, unsigned long request, ...) {
    if (request == PURGE) {
        assert(fd == 2 && closes == 1 && !mapped);
        purges++;
        if (!strcmp(driver, "denied")) { errno = EPERM; return -1; }
        return !strcmp(driver, "empty") ? 0 : 9;
    }
    assert(fd == 1);
    if (request == SET_SIZE) return 0;
    assert(mapped);
    if (request == GET_PIN_STATUS) return pinned;
    if (request == UNPIN) { if (strcmp(driver, "stub")) pinned = 0; return 0; }
    if (request == PIN) { pinned = 1; return 0; }
    abort();
}

int main(int argc, char **argv) {
    assert(argc == 4);
    driver = argv[2];
    assert(helper_main(2, argv) == 0);
    assert(purges == atoi(argv[3]) && opens == closes && !mapped);
    return 0;
}
