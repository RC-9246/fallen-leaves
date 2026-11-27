// Synthetic smaps and an ordinary temporary file exercise the actual native parser and range request.
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <stdlib.h>
#include <errno.h>
#include <stdarg.h>
static long mock_syscall(long number, ...) {
    (void)number;
    errno = ENOSYS;
    return -1;
}
#define syscall mock_syscall
#define main target_pages_main
#include "../app/src/main/c/target_pages.c"
#undef main

static const char *clean = "Anonymous: 0 kB\nPrivate_Dirty: 0 kB\nShared_Dirty: 0 kB\nSwap: 0 kB";
static void maps(const char *head, const char *flags, const char *stats) {
    FILE *f = fopen("smaps", "w");
    assert(f);
    fprintf(f, "%s\nRss: 1024 kB\n%s\nVmFlags: %s\n", head, stats, flags);
    fclose(f);
}

int main(void) {
    const char *anon = "100000-600000 rw-p 00000000 00:00 0   [anon:dalvik-main space]";
    const char *asset = "100000-600000 r-xp 00000000 08:01 202  /data/app/example/base.apk";
    struct mapping m;
    char token[2*MAX_HEADER];
    char flags[] = " rd wr mr mw ac";
    assert(header(anon, "anon", &m) && m.anon && m.header[0] && good_flags(flags));
    assert(m.end-m.start == (5U<<20));
    maps(anon, "rd wr mr mw ac", "Swap: 256 kB");
    encode_header(anon, token);
    assert(!scan("anon", token, &m) && m.kib[RSS] == 1024 && m.kib[SWAP] == 256);
    maps(anon, "rd wr mr mw lo", clean);
    assert(scan("anon", token, &m) != 0);
    maps(asset, "rd ex mr me", clean);
    assert(scan("mixed", token, &m) != 0);
    encode_header(asset, token);
    assert(!scan("file", token, &m) && !m.anon && !strcmp(m.label, "base.apk"));
    assert(scan("anon", token, &m));
    for (int i = 0; i < 4; i++) {
        char stats[160];
        snprintf(stats, sizeof(stats),
                 "Anonymous: %d kB\nPrivate_Dirty: %d kB\nShared_Dirty: %d kB\nSwap: %d kB",
                 i == 0 ? 4 : 0, i == 1 ? 4 : 0, i == 2 ? 4 : 0, i == 3 ? 4 : 0);
        maps(asset, "rd ex mr me", stats);
        assert(scan("file", token, &m));
        assert(scan("mixed", token, &m));
    }
    maps(asset, "rd ex mr me", "Swap: 0 kB");
    assert(scan("file", token, &m));
    assert(header("1000-2000 r--p 0 00:07 99 /data/app/tmp", "file", &m) && !m.header[0]);
    assert(header("1000-2000 r--p 0 08:01 99 /data/app/apk (deleted)", "file", &m) && !m.header[0]);
    assert(header("1000-2000 rw-p 0 00:00 0 [stack]","anon",&m) && !m.header[0]);
    assert(header("1000-2000 r--s 0 00:01 123 /dev/ashmem/test","mixed",&m) && !m.header[0]);
    assert(header("1000-2000 r--p 0 00:01 123 /dev/kgsl-3d0","file",&m) && !m.header[0]);
    assert(!header("Rss: 20 kB","mixed",&m));
    FILE *f = fopen("reclaim", "w");
    assert(f); fclose(f);
    assert(!advise("range", -1, 0x100000, 64*1024));
    f = fopen("reclaim", "r");
    char cmd[64];
    assert(f && fgets(cmd, sizeof(cmd), f) && !strcmp(cmd,"0x100000 65536\n"));
    fclose(f);
    assert(advise("pageout", -1, 0x100000, 64*1024));
    char *probe[] = {"target", "probe"};
    assert(target_pages_main(2, probe) != 0);
    puts("Range checks passed");
    return 0;
}
