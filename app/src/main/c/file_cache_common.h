#ifndef FALLEN_FILE_CACHE_COMMON_H
#define FALLEN_FILE_CACHE_COMMON_H
#include <stdint.h>
#include <sys/stat.h>
#include <sys/mman.h>
#define LEAF_SAMPLE_LIMIT (128 * 1024 * 1024)

static inline int leaf_same_stat(const struct stat *a, const struct stat *b) {
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino && a->st_size == b->st_size
        && a->st_mtim.tv_sec == b->st_mtim.tv_sec && a->st_mtim.tv_nsec == b->st_mtim.tv_nsec
        && a->st_ctim.tv_sec == b->st_ctim.tv_sec && a->st_ctim.tv_nsec == b->st_ctim.tv_nsec;
}

// Map one window without reading any contents. Unmap before asking for eviction.
static inline int64_t leaf_sample(int fd, size_t bytes, size_t page_size) {
    if (bytes == 0) return 0;
    unsigned char vector[1024];
    size_t step = sizeof(vector) * page_size;
    int64_t resident = 0;
    for (size_t offset = 0; offset < bytes; offset += step) {
        size_t len = bytes - offset < step ? bytes - offset : step;
        void *mapping = mmap(NULL, len, PROT_NONE, MAP_SHARED, fd, (off_t)offset);
        if (mapping == MAP_FAILED) return -1;
        int rc = mincore(mapping, len, vector);
        munmap(mapping, len);
        if (rc < 0) return -1;
        size_t pages = (len + page_size - 1) / page_size;
        for (size_t i = 0; i < pages; ++i) resident += vector[i] & 1U;
    }
    return resident;
}

#endif
