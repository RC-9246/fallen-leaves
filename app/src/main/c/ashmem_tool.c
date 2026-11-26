// Legacy Android ashmem UAPI. ioctl command sizes use the running executable's ABI.
#include <errno.h>
#include <fcntl.h>
#include <linux/capability.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

struct ashmem_range { uint32_t offset, len; };
#define SET_SIZE _IOW(0x77, 3, size_t)
#define PIN _IOW(0x77, 7, struct ashmem_range)
#define UNPIN _IOW(0x77, 8, struct ashmem_range)
#define GET_PIN_STATUS _IO(0x77, 9)
#define PURGE _IO(0x77, 10)

static int has_admin_capability(void) {
    struct __user_cap_header_struct header = {_LINUX_CAPABILITY_VERSION_3, 0};
    struct __user_cap_data_struct data[2] = {{0}};
    return syscall(__NR_capget, &header, data) == 0 &&
           (data[CAP_SYS_ADMIN / 32].effective & (1U << (CAP_SYS_ADMIN % 32))) != 0;
}

static int report_error(const char *step) {
    printf("ERROR %s %d\n", step, errno);
    return 0;  // The Java caller reports the structured driver error.
}

// Only this short-lived process's own page is touched. Drop the mapping AND fd
// before a real global purge, so the probe cannot inflate the returned count.
static const char *test_unpin(int fd, long page_size) {
    if (page_size <= 0 || (unsigned long)page_size > UINT32_MAX) return "PAGE_SIZE";
    if (ioctl(fd, SET_SIZE, (size_t)page_size) < 0) return "SET_SIZE";
    void *area = mmap(NULL, (size_t)page_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (area == MAP_FAILED) return "MMAP";
    *(volatile unsigned char *)area = 1;
    struct ashmem_range range = {0, (uint32_t)page_size};
    const char *failure = NULL;
    if (ioctl(fd, GET_PIN_STATUS, &range) != 1) failure = "PIN_STATUS";
    else if (ioctl(fd, UNPIN, &range) < 0) failure = "UNPIN";
    else {
        int status = ioctl(fd, GET_PIN_STATUS, &range);
        if (status == 1) failure = "ALWAYS_PINNED";
        else if (status != 0) failure = "PIN_STATUS";
        else if (ioctl(fd, PIN, &range) < 0) failure = "PIN";
    }
    int saved_errno = errno;
    munmap(area, (size_t)page_size);
    errno = saved_errno;
    return failure;
}

// Some Android releases name the real character device after this boot's UUID.
static int open_device(void) {
    const int flags = O_RDWR | O_CLOEXEC | O_NOFOLLOW;
    int fd = open("/dev/ashmem", flags);
    if (fd >= 0 || (errno != ENOENT && errno != ELOOP)) return fd;

    FILE *boot = fopen("/proc/sys/kernel/random/boot_id", "r");
    if (!boot) return -1;
    char uuid[38] = {0};
    int ok = fgets(uuid, sizeof(uuid), boot) != NULL;
    fclose(boot);
    uuid[strcspn(uuid, "\r\n")] = 0;
    if (!ok || strlen(uuid) != 36 || strspn(uuid, "0123456789abcdef-") != 36) {
        errno = ENODEV;
        return -1;
    }
    char path[sizeof("/dev/ashmem") + 36];
    snprintf(path, sizeof(path), "/dev/ashmem%.36s", uuid);
    return open(path, flags);
}

int main(int argc, char **argv) {
    if (argc != 2 || (strcmp(argv[1], "probe") && strcmp(argv[1], "purge"))) return 64;
    if (!has_admin_capability()) { puts("NO_CAP"); return 0; }
    int fd = open_device();
    if (fd < 0) return report_error("OPEN");
    struct stat node;
    if (fstat(fd, &node) != 0 || !S_ISCHR(node.st_mode)) { puts("NO_DEVICE"); close(fd); return 0; }
    const char *failure = test_unpin(fd, sysconf(_SC_PAGESIZE));
    int saved_errno = errno;
    close(fd);
    if (failure) {
        if (!strcmp(failure, "ALWAYS_PINNED")) puts(failure);
        else { errno = saved_errno; return report_error(failure); }
        return 0;
    }
    if (!strcmp(argv[1], "probe")) { puts("READY"); return 0; }
    fd = open_device();
    if (fd < 0) return report_error("OPEN");
    int pages = ioctl(fd, PURGE, 0);
    saved_errno = errno;
    close(fd);
    if (pages < 0) { errno = saved_errno; return report_error("PURGE"); }
    printf("SCANNED %d\n", pages);
    return 0;
}
