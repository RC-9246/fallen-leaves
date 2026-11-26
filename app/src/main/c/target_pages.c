// The caller pins /proc/PID as cwd after checking the user's selected cached UID and cohort.
// Only private mappings discovered in smaps may be selected. No page contents are read.
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

#ifndef __NR_pidfd_open
#define __NR_pidfd_open 434
#endif
#ifndef __NR_process_madvise
#define __NR_process_madvise 440
#endif
#define ADVICE_COLD 20
#define ADVICE_PAGEOUT 21
#define MAX_ROWS 24
#define MAX_HEADER 1024
#define MAX_CHUNK (4ULL << 20)

struct mapping {
    unsigned long long start, end, rss_kib;
    char header[MAX_HEADER], label[90];
    int anon;
};

static int fail(const char *message) {
    fprintf(stderr, "%s\n", message);
    return 1;
}

static int number(const char *text, unsigned long long *value) {
    char *end;
    if (!isdigit((unsigned char)*text)) return 0;
    errno = 0;
    *value = strtoull(text, &end, 10);
    return !errno && !*end;
}

static void sanitize(char *dst, size_t capacity, const char *src) {
    size_t i = 0;
    for (; src[i] && i + 1 < capacity; i++) {
        unsigned char c = (unsigned char)src[i];
        dst[i] = (isalnum(c) || strchr(" .:/[]_-+", c)) ? (char)c : '_';
    }
    dst[i] = 0;
}

static int allowed_name(int anon, const char *name) {
    if (anon)
        return !*name || !strcmp(name, "[heap]") || !strncmp(name, "[anon:libc_malloc", 17)
              || !strncmp(name, "[anon:scudo:", 12) || !strncmp(name, "[anon:dalvik-", 13);
    static const char *const prefixes[] = {
        "/data/app/", "/data/user/", "/data/user_de/", "/data/data/",
        "/mnt/expand/", "/storage/emulated/", "/system/", "/apex/", "/product/", "/vendor/"
    };
    for (size_t i = 0; i < sizeof(prefixes) / sizeof(prefixes[0]); i++)
        if (!strncmp(name, prefixes[i], strlen(prefixes[i]))) return 1;
    return 0;
}

static int header(const char *line, const char *kind, struct mapping *m) {
    unsigned long long offset, inode, start, end;
    unsigned int major, minor;
    char perms[5];
    int consumed = 0;
    if (sscanf(line, "%llx-%llx %4s %llx %x:%x %llu %n", &start, &end, perms,
               &offset, &major, &minor, &inode, &consumed) != 7 || !consumed) return 0;
    memset(m, 0, sizeof(*m));
    size_t len = strlen(line);
    if (len >= sizeof(m->header) || end <= start || end > UINTPTR_MAX) return 1;
    m->anon = !inode && !major && !minor;
    const char *name = line + consumed;
    if (strlen(perms) != 4 || perms[0] != 'r' || perms[3] != 'p' ||
        (m->anon && (perms[1] != 'w' || perms[2] == 'x')) ||
        (!m->anon && (!inode || perms[1] == 'w')) ||
        (!strcmp(kind,"anon") && !m->anon) || (!strcmp(kind,"file") && m->anon) ||
        !allowed_name(m->anon, name)) return 1;
    memcpy(m->header, line, len + 1);
    m->start = start;
    m->end = end;
    const char *base = strrchr(name, '/');
    sanitize(m->label, sizeof(m->label), *name ? (base ? base + 1 : name) : "anonymous");
    return 1;
}

static int good_flags(char *flags) {
    char *context, *token = strtok_r(flags, " \t\n", &context);
    int read_flag = 0;
    while (token != NULL) {
        if (!strcmp(token,"lo") || !strcmp(token,"ht") || !strcmp(token,"io") ||
            !strcmp(token,"pf") || !strcmp(token,"sh") || !strcmp(token,"gd") ||
            !strcmp(token,"mm")) return 0;
        if (!strcmp(token, "rd")) read_flag = 1;
        token = strtok_r(NULL, " \t\n", &context);
    }
    return read_flag;
}

static void encode_header(const char *src, char *dst) {
    const char digits[] = "0123456789abcdef";
    while (*src) {
        unsigned char c = (unsigned char)*src++;
        *dst++ = digits[c >> 4]; *dst++ = digits[c & 15];
    }
    *dst = 0;
}

static int scan(const char *kind, const char *wanted, struct mapping *found) {
    FILE *f = fopen("smaps", "r");
    if (!f) return fail("目标进程的 smaps 不可读取。");
    struct mapping m = {0}, best[MAX_ROWS];
    int n = 0, matched = 0;
    size_t capacity = 0;
    char *line = NULL;
    while (getline(&line, &capacity, f) >= 0) {
        line[strcspn(line, "\n")] = 0;
        if (header(line, kind, &m) || !m.header[0]) continue;
        if (!strncmp(line, "Rss:", 4)) {
            (void)sscanf(line + 4, "%llu", &m.rss_kib);
        } else if (!strncmp(line,"VmFlags:",8)) {
            int safe = good_flags(line + 8);
            if (safe && wanted) {
                char token[2 * MAX_HEADER];
                encode_header(m.header, token);
                if (!strcmp(token, wanted)) { *found = m; matched = 1; break; }
            } else if (safe && m.rss_kib && !wanted) {
                int i = n;
                // Keep the biggest resident mappings; never emit a whole process map.
                while (i > 0 && best[i-1].rss_kib < m.rss_kib) {
                    if (i < MAX_ROWS) best[i] = best[i-1];
                    i--;
                }
                if (i < MAX_ROWS) { best[i] = m; if (n < MAX_ROWS) n++; }
            }
            m.header[0] = 0;
        }
    }
    int err = ferror(f);
    free(line);
    fclose(f);
    if (err) return fail("目标进程的映射记录未读完整。");
    if (wanted) return matched ? 0 : fail("映射身份或属性已改变，请重新选择。");
    for (int i=0;i<n;i++) {
        char token[2*MAX_HEADER];
        encode_header(best[i].header, token);
        printf("MAP %s %llu %llu %s %s\n", token,best[i].end-best[i].start,best[i].rss_kib,
               best[i].anon?"anon":"file",best[i].label);
    }
    return 0;
}

static int advise(const char *mode, int pidfd, uintptr_t start, size_t size) {
    if (!strcmp(mode,"range")) {
        char cmd[96];
        int length = snprintf(cmd, sizeof(cmd), "0x%llx %zu\n", (unsigned long long)start, size);
        int fd = open("reclaim", O_WRONLY | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) return fail("目标不提供可写的区间回收接口。");
        ssize_t accepted = write(fd, cmd, (size_t)length);
        close(fd);
        if (accepted != length) return fail("内核未完整接受区间回收请求。");
        printf("ACCEPT %zu\n", size);
        return 0;
    }
    struct iovec vec = { (void *)start, size };
    long n = syscall(__NR_process_madvise, pidfd, &vec, (size_t)1,
                     !strcmp(mode,"mark") ? ADVICE_COLD : ADVICE_PAGEOUT, 0);
    if (n < 0) {
        if (errno == ENOSYS) return fail("此内核未开放 process_madvise。");
        if (errno == EPERM || errno == EACCES) return fail("内核或安全策略拒绝远程页建议。");
        return fail("内核未接受所选映射的页建议。");
    }
    if (n == 0 || (size_t)n > size) return fail("内核未接受有效长度。");
    printf("ACCEPT %ld\n", n);
    return 0;
}

int main(int argc, char **argv) {
    // Check syscall and policy without touching any target memory.
    if (argc == 2 && !strcmp(argv[1], "probe")) {
        int fd = (int)syscall(__NR_pidfd_open, getpid(), 0);
        if (fd < 0) return fail("内核或安全策略未开放 pidfd。");
        long n = syscall(__NR_process_madvise, fd, NULL, (size_t)0, ADVICE_COLD, 0);
        close(fd);
        return n == 0 ? 0 : fail("远程页建议接口未开放或被当前安全策略拒绝。");
    }
    if (argc == 3 && !strcmp(argv[1], "list") &&
        (!strcmp(argv[2],"anon") || !strcmp(argv[2],"file") || !strcmp(argv[2],"mixed")))
        return scan(argv[2], NULL, NULL);
    // MODE KIND PID exact-header OFFSET BYTE_COUNT. No users can supply free-form address inputs.
    if (argc != 7 || (strcmp(argv[1],"range") && strcmp(argv[1],"pageout") && strcmp(argv[1],"mark")) ||
        (strcmp(argv[2],"anon") && strcmp(argv[2],"file") && strcmp(argv[2],"mixed"))) return 64;
    unsigned long long numeric_pid, offset, bytes;
    if (!number(argv[3], &numeric_pid) || numeric_pid < 1 || numeric_pid > INT_MAX ||
        strlen(argv[4]) >= 2*MAX_HEADER || !number(argv[5],&offset) ||
        !number(argv[6], &bytes) || !bytes || bytes > MAX_CHUNK) return 64;
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0 || offset % (unsigned long)page || bytes % (unsigned long)page) return 64;
    int pidfd = -1, rc = 1;
    if (strcmp(argv[1],"range")) {
        pidfd = (int)syscall(__NR_pidfd_open, (int)numeric_pid, 0);
        if (pidfd < 0) return fail("无法为目标建立 pidfd，内核或安全策略不支持此操作。");
    }
    // Verify the numeric PID still refers to the pinned directory after opening pidfd.
    char path[64];
    snprintf(path, sizeof(path), "/proc/%u", (unsigned)numeric_pid);
    struct stat a,b;
    if (stat(".", &a) || stat(path, &b) || a.st_ino != b.st_ino || a.st_dev != b.st_dev) {
        rc = fail("进程已更替。"); goto done;
    }
    struct mapping m;
    if (scan(argv[2], argv[4], &m)) goto done;
    if (offset >= m.end-m.start || bytes > m.end-m.start-offset) {rc = fail("区间超出原映射。");goto done;}
    FILE *score = fopen("oom_score_adj", "r");
    int adj = 0;
    if (score) {if (fscanf(score,"%d",&adj) != 1) adj=0;fclose(score);}
    if (adj < 900 || adj > 999) {rc = fail("进程已离开缓存状态。");goto done;}
    rc = advise(argv[1], pidfd, (uintptr_t)(m.start+offset), (size_t)bytes);
done:
    if (pidfd >= 0) close(pidfd);
    return rc;
}
