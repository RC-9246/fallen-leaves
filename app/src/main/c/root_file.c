// Operate on an explicitly reselected file beneath this Android user's Download directory.
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <unistd.h>
#include "file_cache_common.h"

#define DIR_FLAGS (O_RDONLY | O_NOFOLLOW | O_DIRECTORY | O_CLOEXEC)
enum { PAGE_ENTRIES = 96 };
struct folder { int fd; dev_t device; long mount; char path[PATH_MAX]; };

static int issue(const char *message) { fprintf(stderr,"%s\n",message); return 1; }

static int digit(int b) {
    if (b >= '0' && b <= '9') return b-'0';
    if (b >= 'a' && b <= 'f') return b-'a'+10;
    return -1;
}

static int decode(const char *hex, size_t length, char name[NAME_MAX+1]) {
    if (!length || length > NAME_MAX*2 || length%2 != 0) return 0;
    for (size_t i=0;i<length;i+=2) {
        int a=digit(hex[i]), b=digit(hex[i+1]);
        if (a<0 || b<0) return 0;
        int byte=(a<<4)|b;
        if (byte==0 || byte=='/') return 0;
        name[i/2]=(char)byte;
    }
    name[length/2]=0;
    return strcmp(name,".") && strcmp(name,"..");
}

static int wanted_type(const struct stat *st) {
    return S_ISDIR(st->st_mode) || (S_ISREG(st->st_mode) && st->st_nlink==1);
}

static long mount_id(int fd) {
    char file[64],line[128];
    snprintf(file,sizeof(file),"/proc/self/fdinfo/%d",fd);
    FILE *f=fopen(file,"r");
    if (!f) return -1;
    long id=-1;
    while (fgets(line,sizeof(line),f)) if (sscanf(line,"mnt_id: %ld",&id)==1) break;
    fclose(f);
    return id;
}

static int at_location(int fd, const char *expected) {
    char link[64],path[PATH_MAX];
    snprintf(link,sizeof(link),"/proc/self/fd/%d",fd);
    ssize_t n=readlink(link,path,sizeof(path)-1);
    if (n<1 || n==(ssize_t)sizeof(path)-1) return 0;
    path[n]=0;
    return strcmp(path,expected)==0;
}

static int descend(struct folder *d, const char *name, int check_mount) {
    size_t used=strlen(d->path), size=strlen(name);
    if (size+used+2>sizeof(d->path)) return 0;
    int newfd=openat(d->fd,name,DIR_FLAGS);
    if (newfd<0) return 0;
    d->path[used]='/'; memcpy(d->path+used+1,name,size+1);
    struct stat st;
    if (fstat(newfd,&st) || !at_location(newfd,d->path)
        || (check_mount && (st.st_dev!=d->device || mount_id(newfd)!=d->mount))) {
        close(newfd);return 0;
    }
    close(d->fd); d->fd=newfd;
    return 1;
}

static int open_folder(struct folder *d,const char *user,const char *dir) {
    if (!*user || strlen(user)>5 || strspn(user,"0123456789")!=strlen(user)
        || (user[0]=='0' && user[1])) return 0;
    d->fd=open("/",DIR_FLAGS);
    if (d->fd<0 || !descend(d,"data",0)) return 0;
    struct stat st; struct statfs fs;
    if (fstat(d->fd,&st) || fstatfs(d->fd,&fs) ||
        ((uint32_t)fs.f_type!=0xef53 && (uint32_t)fs.f_type!=0xf2f52010)) return 0;
    d->device=st.st_dev;d->mount=mount_id(d->fd);
    if (d->mount<0 || !descend(d,"media",1) || !descend(d,user,1) || !descend(d,"Download",1)) return 0;
    if (!strcmp(dir,"-")) return 1;
    for (;;) {
        const char *slash=strchr(dir,'/'); char name[NAME_MAX+1];
        if (!decode(dir,slash?(size_t)(slash-dir):strlen(dir),name) || !descend(d,name,1)) return 0;
        if (!slash) return 1;
        dir=slash+1;
    }
}

// Snapshot includes nanosecond modification and change times, plus link count checks at use.
static int stamp(char *buf,size_t n,const struct stat *s) {
    int used=snprintf(buf,n,"%" PRIuMAX ":%" PRIuMAX ":%" PRIdMAX ":%" PRIdMAX ":%ld:%" PRIdMAX ":%ld",
        (uintmax_t)s->st_dev,(uintmax_t)s->st_ino,(intmax_t)s->st_size,
        (intmax_t)s->st_mtim.tv_sec,s->st_mtim.tv_nsec,(intmax_t)s->st_ctim.tv_sec,s->st_ctim.tv_nsec);
    return used>0 && (size_t)used<n;
}

struct listed_entry { char name[NAME_MAX+1]; struct stat st; };

static void encode_name(const char *name, char hex[2*NAME_MAX+1]) {
    static const char digits[]="0123456789abcdef";
    size_t i=0;
    for (; name[i]; i++) {
        unsigned char byte=(unsigned char)name[i];
        hex[2*i]=digits[byte>>4]; hex[2*i+1]=digits[byte&15];
    }
    hex[2*i]=0;
}

static int list_folder(struct folder *d,const char *cursor) {
    struct stat before,after; char id[192],last[NAME_MAX+1]="";
    if (fstat(d->fd,&before) || !stamp(id,sizeof(id),&before)) return issue("无法核对目录状态。");
    if (strcmp(cursor,"-")) {
        const char *end=strchr(cursor,'_');
        if (!end || !decode(cursor,(size_t)(end-cursor),last) || strcmp(end+1,id))
            return issue("下载目录已变化，请重新进入。");
    }
    int clone=dup(d->fd);
    if (clone<0) return issue("无法打开目录。");
    DIR *dir=fdopendir(clone);
    if (!dir) {close(clone);return issue("无法读取目录。");}
    // A cursor crosses helper processes. Use the last raw filename, not a DIR-stream cookie.
    // Keep only the smallest page plus one qualifying entry to establish whether more exist.
    struct listed_entry *storage=calloc(PAGE_ENTRIES+1,sizeof(*storage));
    if (!storage) {closedir(dir);return issue("无法暂存目录清单。");}
    struct listed_entry *rows[PAGE_ENTRIES+1];
    size_t count=0;
    int failure=0;
    for (;;) {
        errno=0;struct dirent *entry=readdir(dir);
        if (!entry) {failure=errno;break;}
        const char *name=entry->d_name;
        if (!strcmp(name,".") || !strcmp(name,"..") || strcmp(name,last)<=0) continue;
        if (count>PAGE_ENTRIES && strcmp(name,rows[PAGE_ENTRIES]->name)>=0) continue;
        struct stat st;
        if (fstatat(d->fd,name,&st,AT_SYMLINK_NOFOLLOW) || !wanted_type(&st) || st.st_dev!=d->device)
            continue;
        size_t length=strlen(name);
        if (length>NAME_MAX) {failure=EOVERFLOW;break;}
        size_t at;
        struct listed_entry *next;
        if (count>PAGE_ENTRIES) {
            at=PAGE_ENTRIES;
            next=rows[at];
        } else {
            at=count;
            next=&storage[count++];
        }
        while (at>0 && strcmp(name,rows[at-1]->name)<0) {
            rows[at]=rows[at-1];
            at--;
        }
        memcpy(next->name,name,length+1);
        next->st=st;
        rows[at]=next;
    }
    closedir(dir);
    if (failure || fstat(d->fd,&after) || !leaf_same_stat(&before,&after) || !at_location(d->fd,d->path)) {
        free(storage);return issue("目录变化或读取失败，请重新选择。");
    }
    char hex[2*NAME_MAX+1], identity[192];
    for (size_t i=0;i<count && i<PAGE_ENTRIES;i++) {
        const struct stat *st=&rows[i]->st;
        encode_name(rows[i]->name,hex);
        if (!stamp(identity,sizeof(identity),st) ||
            printf("%c %s %jd %s\n",S_ISDIR(st->st_mode)?'D':'F',hex,(intmax_t)st->st_size,identity)<0) {
            free(storage);return issue("未能输出完整的目录清单。");
        }
    }
    if (count>PAGE_ENTRIES && printf("N %s_%s\n",hex,id)<0) failure=1;
    free(storage);
    return failure || fflush(stdout)!=0 ? issue("未能输出完整的目录清单。") : 0;
}

static int evict_file(struct folder *d,const char *hex,const char *identity) {
    char name[NAME_MAX+1],expected[PATH_MAX];
    if (!decode(hex,strlen(hex),name)) return issue("选中的文件名无效。");
    if (snprintf(expected,sizeof(expected),"%s/%s",d->path,name)>=(int)sizeof(expected))
        return issue("目录过深。");
    int pathfd=openat(d->fd,name,O_PATH|O_CLOEXEC|O_NOFOLLOW);
    if (pathfd<0) return issue("文件已经移动或不可访问。");
    struct stat before,after;char id[192],fdlink[64];
    if (fstat(pathfd,&before) || !S_ISREG(before.st_mode) || before.st_nlink!=1 || before.st_dev!=d->device
        || mount_id(pathfd)!=d->mount || !at_location(pathfd,expected)
        || !stamp(id,sizeof(id),&before) || strcmp(identity,id)) {
        close(pathfd);return issue("文件身份、链接数或内容已变化，请重新选择。");
    }
    snprintf(fdlink,sizeof(fdlink),"/proc/self/fd/%d",pathfd);
    int fd=open(fdlink,O_RDONLY|O_CLOEXEC|O_NONBLOCK|O_NOCTTY);
    close(pathfd);
    if (fd<0) return issue("无法以只读方式打开已核实的文件。");
    long page=sysconf(_SC_PAGESIZE);
    if (page<=0 || page>(1<<20) || before.st_size<0) {close(fd);return issue("无法核对文件大小或页大小。");}
    snprintf(fdlink,sizeof(fdlink),"/proc/self/fd/%d",fd);
    size_t span=before.st_size<LEAF_SAMPLE_LIMIT?(size_t)before.st_size:(size_t)LEAF_SAMPLE_LIMIT;
    int64_t resident=leaf_sample(fd,span,(size_t)page);
    if (resident<0 || (before.st_uid!=geteuid() && access(fdlink,W_OK)!=0)) {
        close(fd);return issue("无法核实驻留统计，已跳过。");
    }
    if (fstat(fd,&after) || !leaf_same_stat(&before,&after) || after.st_nlink!=1 || !at_location(fd,expected)) {
        close(fd);return issue("执行前的文件身份或属性发生变化。");
    }
    int rc=before.st_size?posix_fadvise(fd,0,0,POSIX_FADV_DONTNEED):0;
    if (rc) {close(fd);return issue("文件系统拒绝逐出建议。");}
    int64_t later=leaf_sample(fd,span,(size_t)page);
    if (fstat(fd,&after) || !leaf_same_stat(&before,&after) || after.st_nlink!=1
        || !at_location(fd,expected) || (after.st_uid!=geteuid() && access(fdlink,W_OK)!=0)) later=-1;
    printf("DONE %jd %ld %zu %" PRId64 " %" PRId64 "\n",(intmax_t)before.st_size,page,span,resident,later);
    close(fd);
    return 0;
}

int main(int argc,char **argv) {
    if (geteuid()!=0) return issue("需要 Root 授权。");
    int listing=argc==5 && !strcmp(argv[1],"list"),evict=argc==6 && !strcmp(argv[1],"evict");
    if (!listing && !evict) return issue("未知的后端文件请求。");
    struct folder d={.fd=-1};
    int ok=open_folder(&d,argv[2],argv[3]);
    int rc=ok?(listing?list_folder(&d,argv[4]):evict_file(&d,argv[4],argv[5]))
             :issue("当前用户下载目录或子目录无法安全打开。");
    if (d.fd>=0) close(d.fd);
    return rc;
}
