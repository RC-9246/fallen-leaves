# Executed by KernelScopes after it sets scope, apply and hardware page size.
# No input from a text field is interpolated. Probe paths only read the kernel.
info() { printf 'INFO\n%s\n' "$*"; exit 0; }
ready() { if [ "$apply" = 0 ]; then printf 'READY\n%s\n' "$*"; exit 0; fi; }
partial_result() { printf 'PARTIAL\n%s\n' "$*"; exit 0; }
done_result() { printf 'DONE\n%s\n' "$*"; exit 0; }
fail() { printf '%s\n' "$*" >&2; exit 1; }
number() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
# Android mksh compares integers in a signed 32-bit domain. Clamp kernel
# counters before shell comparisons so large values cannot wrap past a limit.
cap_count() { awk -v n="$1" -v cap="$2" 'BEGIN {if(n !~ /^[0-9]+$/) exit 1; print (n+0>cap+0 ? cap : n+0)}'; }
kernel_at_least() {
  uname -r | awk -F. -v major="$1" -v minor="$2" '$1~/^[0-9]+$/ && $2~/^[0-9]+$/ &&
    ($1+0>major || ($1+0==major && $2+0>=minor)) {ok=1} END{exit !ok}'
}
numfile() {
  # SLUB can annotate its first (total) field with per-node counts.
  awk 'NR==1 && $1 ~ /^[0-9]+$/ {print $1; ok=1} END {if(!ok) exit 1}' "$1" 2>/dev/null
}
readn() { numfile "$1" || echo '?'; }
control() { [ -f "$1" ] && [ ! -L "$1" ] && [ -w "$1" ]; }
write_value() { printf '%s\n' "$2" > "$1" || fail '驱动未接受请求。'; }
debug_mount() {
  # Only act on an existing debugfs. Never mount it or substitute another filesystem.
  awk '$3=="debugfs" && ($2=="/sys/kernel/debug" || $2=="/d") {print $2; exit}' /proc/mounts
}
zphys() { awk 'NR==1 && $3~/^[0-9]+$/ {print $3; ok=1} END{if(!ok) exit 1}' mm_stat 2>/dev/null || echo '?'; }

slub() {
  fs=''
  case "$scope" in
    DENTRY) cache=dentry;;
    INODE) cache=inode_cache;;
    F2FS_INODE) fs=f2fs; cache=f2fs_inode_cache;;
    EXT4_INODE) fs=ext4; cache=ext4_inode_cache;;
  esac
  if [ -n "$fs" ]; then
    awk -v fs="$fs" '$3==fs {found=1; exit} END{exit !found}' /proc/mounts ||
      info "当前未见已挂载的 $fs 文件系统。"
  fi
  cd -P "/sys/kernel/slab/$cache" 2>/dev/null || info "内核未导出 $cache 的 sysfs 目录。"
  control shrink || info "$cache 没有可写的 shrink 接口。"
  before=$(readn slabs); partial=$(readn partial); aliases=$(readn aliases)
  [ "$aliases" = 0 ] || info "$cache 的别名数为 ${aliases}，无法确认独立收缩范围。"
  ready "$cache 空 slab 收缩接口可写。slab 总数 ${before}，部分占用 slab ${partial}，缓存别名数 ${aliases}。"
  write_value shrink 1
  after=$(readn slabs); remaining=$(readn partial)
  done_result "$cache 已提交收缩。slab 总数 $before → ${after}，部分占用 slab $partial → ${remaining}。部分占用计数并非可释放页数，单次物理收益无法独立核验。"
}

driver_pool() {
  root=$(debug_mount)
  case "$scope" in
    ION_POOL)
      pages=$(awk '$1=="nr_ion_heap_pool" && $2~/^[0-9]+$/ {print $2}' /proc/vmstat)
      if number "$pages"; then bytes=$(awk -v n="$pages" -v size="$page" 'BEGIN{printf "%.0f", n*size}'); stats="全机 ION 空闲池 $pages 页，共 $bytes B。"
      elif k=$(numfile /sys/kernel/ion/total_pools_kb); then stats="ION 空闲池 $k KiB。"
      elif k=$(numfile /sys/kernel/dma_heap/total_pools_kb); then stats="DMA 堆空闲池 $k KiB。"
      else stats='空闲池统计不可用。'; fi
      # Legacy driver: zero means unlimited scan, so never write a zero count.
      if [ -n "$root" ] && cd -P "$root/ion/heaps" 2>/dev/null && control system_shrink; then
        n=$(numfile system_shrink) || info "$stats 不能核对普通系统堆的对象计数。"
        ready "$stats 普通系统堆公开定向入口，目前可扫描 $n 个驱动对象。"
        n=$(cap_count "$n" 256) || fail '无法核对扫描数量。'
        [ "$n" != 0 ] || done_result '普通系统堆实际可扫描对象为 0，本次未扫描。'
        write_value system_shrink "$n"
        done_result "普通系统堆请求扫描 $n 个对象。操作后可扫描对象 $(readn system_shrink)。全机同期活动影响计数。"
      fi
      info "$stats 未见普通 ION 系统堆的专用收缩入口。不会调用 secure heap drain。";;
    BINDER_POOL) matcher='^android-binder(-alloc)?-[0-9]+$|^binder(-alloc|_alloc)?-[0-9]+$'; stats='Binder 驱动空闲对象';;
    KGSL_POOL) matcher='^kgsl[-_]pool[-][0-9]+$|^kgsl[-_]page[-_]pool-[0-9]+$'; stats="GPU 驱动已分配 $(readn /sys/class/kgsl/kgsl/page_alloc) B，空闲池另计";;
  esac
  [ -n "$root" ] || info "${stats}。没有已挂载的具名驱动回收接口。"
  for path in "$root"/shrinker/*; do
    [ -d "$path" ] || continue
    name=${path##*/}; printf '%s\n' "$name" | grep -Eq "$matcher" || continue
    cd -P "$path" && control scan && [ -r count ] || continue
    # Only global (id 0) objects on node 0 of this non-memcg-specific driver.
    n=$(awk '$1==0 && $2~/^[0-9]+$/ {print $2; exit}' count) || info "$name 的计数无法读取。"
    case "$n" in '') n=0;; esac
    ready "${stats}。驱动 $name 在全局节点 0 有 $n 个可扫描对象。"
    n=$(cap_count "$n" 256) || fail '无法核对扫描数量。'
    [ "$n" != 0 ] || done_result "$name 全局节点 0 可扫描对象为 0。"
    write_value scan "0 0 $n"
    remaining=$(awk '$1==0 && $2~/^[0-9]+$/ {print $2; seen=1;exit} END{if(!seen)print 0}' count) || remaining='?'
    done_result "已请求 $name 扫描 $n 个对象；该节点现有 $remaining 个可扫描对象。对象数不能直接换算为释放字节。"
  done
  info "${stats}。未发现已识别的逐池可写接口。"
}

gpu_process() {
  if [ "$apply" = 0 ]; then
    have=0; have_reclaim=0
    for dir in /sys/class/kgsl/kgsl/proc/[0-9]*; do
      [ ! -r "$dir/gpumem_mapped" ] || have=1
      [ ! -r "$dir/gpumem_reclaimed" ] || { have=1; have_reclaim=1; }
    done
    if [ "$have" = 1 ]; then
      case "$have_reclaim" in
        1) status='至少一个进程提供驱动换出计数。';;
        *) status='活跃进程未提供专用换出计数。';;
      esac
      ready "$status 选择一个缓存应用读取驱动快照。此操作只读。"
    fi
    info '未见活跃 KGSL 进程提供可核验的映射或换出计数。'
  fi
  rows=''; found=0
  for p in $pids; do
    process "$p" check || stale
    dir="/sys/class/kgsl/kgsl/proc/$p"
    if [ ! -r "$dir/gpumem_mapped" ] && [ ! -r "$dir/gpumem_reclaimed" ]; then
      rows="$rows
PID ${p}：没有 KGSL 统计"; continue
    fi
    mapped=$(readn "$dir/gpumem_mapped"); unmapped=$(readn "$dir/gpumem_unmapped"); reclaimed=$(readn "$dir/gpumem_reclaimed")
    process "$p" check || stale
    if [ "$mapped$unmapped$reclaimed" != '???' ]; then found=1; fi
    rows="$rows
PID ${p}：已映射 $mapped B，驱动未映射 $unmapped B，驱动已换出 $reclaimed B。"
  done
  same_members && cached_uid || stale
  if [ "$found" = 0 ]; then info "所选缓存应用当前没有可读的 GPU 内存计数。$rows"; fi
  done_result "KGSL 驱动快照（? 表示未提供）。$rows
这些计数不能作为可释放量；进程前后台标志由系统协调。"

}

mglru() {
  root=$(debug_mount); file="$root/lru_gen"
  [ -n "$root" ] && control "$file" && [ -r "$file" ] || info '未见已挂载、可读写的多代 LRU 操作入口。'
  bits=$(cat /sys/kernel/mm/lru_gen/enabled 2>/dev/null)
  case "$bits" in 0x[0-9a-fA-F]*|[0-9]*) ;; *) info '无法确认多代 LRU 已启用。';; esac
  [ "$((bits & 1))" = 1 ] || info '多代 LRU 当前未开启。'
  # The procfs-like listing is a moving snapshot. Restrict scope to the root
  # memcg and node 0, and skip the two newest generations as required by the ABI.
  row=$(awk '
    $1=="memcg" {root=($2~/^[0-9]+$/ && ($3=="/" || (NF==2 && $2=="0"))); id=$2; node=0; next}
    $1=="node" {node=(root && $2=="0"); next}
    node && $1~/^[0-9]+$/ && $4~/^[0-9]+$/ {
      if(!seen) {first=$1; pages=$4; which=id; seen=1}
      if(which==id && $1+0>max) max=$1+0
    }
    END {if(seen && max>=first+2 && pages>0) print which, first, pages}
  ' "$file")
  [ -n "$row" ] || info '目前没有核验到根内存组节点 0 中可回收的最老代文件页。'
  set -- $row; group=$1; gen=$2; n=$3
  n=$(cap_count "$n" "$((4194304/page))") || info '无法核对 MGLRU 文件页数量。'
  ready "根内存组 ${group}、节点 0、最老代 $gen 估计有 $3 个文件页；本次回收目标 $n 页。"
  write_value "$file" "- $group 0 $gen 0 $n"
  done_result "已请求在节点 0 从代 $gen 回收 $n 个文件页。内核分批扫描，实际回收可能高于或低于目标值。"
}

# In timed modes, retain all system quotas/settings and never stop an already
# running system service. Restore the original disabled value on interruption.
trial() {
  if [ "$scope" = DAMON ]; then
    cd -P /sys/module/damon_reclaim/parameters 2>/dev/null || info '内核未导出 DAMON_RECLAIM。'
    flag=enabled; on=Y; off=N; counter=bytes_reclaimed_regions
    quota=$(numfile quota_sz) && timeq=$(numfile quota_ms) && interval=$(numfile quota_reset_interval_ms) ||
      info '缺少 DAMON 配额，不能核对本次试运行的影响。'
    [ "$quota" -gt 0 ] && [ "$timeq" -gt 0 ] && [ "$interval" -gt 0 ] || info '当前 DAMON 时间或容量配额未启用。'
    meta="现有配额 $quota B、$timeq ms / $interval ms；计数为已回收区域字节"
  else
    cd -P /sys/kernel/mm/ksm 2>/dev/null || info '内核未导出 KSM。'
    flag=run; on=1; off=0; counter=pages_sharing
    meta="每批扫描 $(readn pages_to_scan) 页；计数为净额估算前的共享节省页数"
  fi
  [ -r "$flag" ] && control "$flag" || info '无法核验和恢复试运行开关。'
  old=$(cat "$flag") || info '无法取得原开关值。'
  [ "$old" = "$off" ] || [ "$old" = "$on" ] || info "系统处于其他状态（${old}），不会覆盖。"
  before=$(readn "$counter")
  ready "${meta}。原开关 ${old}，当前计数 ${before}。运行中只采样；关闭时短暂开启并恢复。"
  if [ "$old" = "$off" ]; then
    restore() {
      current=$(cat "$flag") || return 1
      [ "$current" != "$on" ] || printf '%s\n' "$off" > "$flag" || return 1
      [ "$(cat "$flag")" = "$off" ]
    }
    # The private token cancels the independent fallback when restoration
    # succeeds. A killed trial shell leaves the token for the fallback.
    lease=$(mktemp -d /data/local/tmp/fallen-scan.XXXXXXXX) || fail '无法准备试运行恢复。'
    (trap '' HUP INT TERM; /system/bin/sleep 6
      if rmdir "$lease" 2>/dev/null; then restore; fi
    ) </dev/null >/dev/null 2>&1 &
    trap 'if restore; then rmdir "$lease" 2>/dev/null || :; else echo "请核对内核试运行开关" >&2; fi' EXIT
    trap 'exit 1' HUP INT TERM
    write_value "$flag" "$on"
  fi
  sleep 3
  if [ "$old" = "$off" ]; then
    restore || fail '试运行已提交，但未能核验开关已恢复，请检查系统状态。'
    rmdir "$lease" 2>/dev/null || :
    trap - EXIT HUP INT TERM
  fi
  after=$(readn "$counter")
  done_result "三秒窗口。${meta}。计数 $before → ${after}；当前开关 $(cat "$flag")。共享、访问或水位条件可能使变化为零。"
}

# Operate on one active zRAM swap device per invocation. Never establish new
# swap, reset a device or grant extra writeback credit. Only the separate
# ZRAM_MARK_IDLE action establishes a new observation window for the whole pool.
active_swap_device() (
  set -f
  IFS=: read -r major minor < dev || exit 1
  number "$major" && number "$minor" && [ "$major" != 0 ] || exit 1
  expected=$(printf '%x:%x' "$major" "$minor") || exit 1
  paths=$(awk 'NR>1 && $2=="partition" && $1 ~ /^\// {print $1}' /proc/swaps) || exit 1
  for path in $paths; do
    # A basename may belong to a regular swap file; aliases can also point
    # to the actual zRAM device. Check rdev and the S_IFBLK mode bits.
    actual=$(stat -L -c '%t:%T:%f' "$path" 2>/dev/null) || continue
    case "$actual" in "$expected":6???) exit 0;; esac
  done
  exit 1
)

zram_scope() {
  reasons=''
  if [ "$scope" != ZRAM_DEDUP ]; then
    service=$(getprop init.svc.mmd 2>/dev/null); configured=$(getprop mmd.zram.enabled 2>/dev/null)
    case "$service:$configured" in
      running:*|restarting:*|starting:*|stopping:*|*:true|*:1|*:yes|*:on)
        info 'mmd 服务活跃或已配置托管。空闲标记、二次压缩及写回由系统维护，采用其时序和写入预算。';;
    esac
  fi
  for dir in /sys/block/zram[0-9]*; do
    id=${dir##*/}; number "${id#zram}" && [ -d "$dir" ] || continue
    cd -P "$dir" && active_swap_device || continue
    if [ "$scope" = ZRAM_DEDUP ]; then
      same=$(awk 'NF>=6{print $6}' mm_stat 2>/dev/null)
      reasons="$reasons ${id}：去重设置 $(readn use_dedup)，同值页 ${same:-?}。"
      continue
    fi
    size=$(numfile disksize) && [ "$size" != 0 ] || continue
    before=$(zphys)
    complete=1
    if [ "$scope" = ZRAM_MARK_IDLE ]; then
      if ! control idle; then reasons="$reasons $id 未开放 idle 标记接口。"; continue; fi
      ready "$id 当前物理占用 $before B。操作将标记该设备所有已存页，建立新的空闲观察起点；标记本身释放 0 B。"
      write_value idle all
      done_result "$id 已提交全设备空闲标记。此次只标记、不执行压缩或写回；后续访问会改变空闲状态。"
    fi
    if [ "$scope" = ZRAM_RECOMPRESS ]; then
      algorithm=$(awk '$1=="#1:" {for(i=2;i<=NF;i++) if($i~/^\[[A-Za-z0-9_-]+\]$/) {
          algo=substr($i,2,length($i)-2); n++}} END{if(n!=1)exit 1; print algo}' recomp_algorithm 2>/dev/null)
      if ! control recompress || [ -z "$algorithm" ]; then
        reasons="$reasons $id 缺少可写入口或优先级 1 的次级算法。"; continue
      fi
      # Older zRAM silently ignores unknown keys, including max_pages.
      # Use a bounded operation only on upstream ABI generations that parse it.
      if kernel_at_least 6 12; then selector=priority=1
      elif kernel_at_least 6 10; then selector="algo=$algorithm"
      else reasons="$reasons $id 提供二次压缩，但未能核验该内核会限制尝试压缩的页数。"; continue; fi
      ready "$id 当前物理占用 $before B。使用已有优先级 1 算法 $algorithm，最多尝试压缩 256 个难压且已标冷的页，筛选时可能遍历设备。"
      (printf '%s\n' "type=huge_idle $selector max_pages=256" > recompress) 2>/dev/null || complete=0
      note='已请求二次压缩。'
    else
      backing=$(cat backing_dev 2>/dev/null); credit=$(numfile writeback_limit)
      if ! control writeback || [ -z "$backing" ] || [ "$backing" = none ] ||
          [ "$(readn writeback_limit_enable)" != 1 ] || ! number "$credit" ||
          ! awk -v c="$credit" -v p="$page" 'BEGIN{exit !(p>=4096 && p%4096==0 && c>=1 && c<=2048 && c%(p/4096)==0)}'; then
        reasons="$reasons $id 需要已配置后备设备及不超过 8 MiB、按硬件页对齐的非零剩余写入额度。"; continue
      fi
      if kernel_at_least 6 16; then mode=type=huge_idle
      elif kernel_at_least 5 19; then mode=huge_idle
      else reasons="$reasons $id 未能核验驱动支持仅写回同时难压且已标冷的页。"; continue; fi
      writes=$(awk 'NF>=3{print $3}' bd_stat 2>/dev/null)
      ready "$id 已配置后备设备，既有剩余额度 $((credit * 4096)) B。只写回难压且已标冷页，设备原来已写 ${writes:-?} 个 4 KiB 单位。"
      # Older drivers can fail after successfully writing some pages, including
      # when the existing quota reaches zero before the end of the scan.
      (printf '%s\n' "$mode" > writeback) 2>/dev/null || complete=0
      later=$(awk 'NF>=3{print $3}' bd_stat 2>/dev/null)
      note="已请求写回。设备写入计数（4 KiB 单位）${writes:-?} → ${later:-?}。"
    fi
    [ "$(readn disksize)" = "$size" ] || fail '已提交但设备容量变化，结果不可比较。'
    report="$id $note 物理分配 $before → $(zphys) B；整机并发活动也会改变它。"
    [ "$complete" = 1 ] || partial_result "内核未确认全部请求，部分页可能已处理。$report"
    done_result "$report"
  done
  info "${reasons:-未找到合适的活跃 zRAM 交换设备。}"
}

memcg() {
  mount=$(awk '$4=="/" {for(i=7;i<NF;i++) if($i=="-" && $(i+1)=="cgroup2"){print $5;exit}}' /proc/self/mountinfo)
  case "$mount" in /sys/fs/cgroup|/dev/*|/sys/fs/cgroup/*) ;; *) info '未识别到可核验的 cgroup v2 挂载点。';; esac
  grep -qw memory "$mount/cgroup.controllers" || info 'cgroup v2 未提供 memory 控制器。'
  ready '内核已提供 memory 控制器。选择应用后核验其独占叶组和 memory.reclaim。'
  set +f
  set -- $pids; first=$1
  cg=$(awk -F: '$1==0 && $2=="" {print $3}' "/proc/$first/cgroup")
  # Only well-known Android leaf naming patterns are eligible; never reclaim
  # the mount root, an ancestor, a user-specified path or any mixed process group.
  case "$cg" in */uid_"$uid"|*/uid_"$uid"/pid_"$first") ;; *) info '未找到符合所选 UID 的 Android 专属叶组。';; esac
  case "$cg" in *[!A-Za-z0-9/_-]*|*//*|/) info '分组路径不符合允许的格式。';; esac
  cd -P "$mount$cg" || info '分组已变化。'
  control memory.reclaim || info '该叶组未开放 memory.reclaim。'
  is_leaf_group() {
    grep -Eq '^nr_descendants[[:space:]]+0$' cgroup.stat || return 1
    # The cgroup ABI permits duplicate PIDs during membership churn. Row count
    # alone could mistake a duplicate for another process that moved away.
    awk -v expected="$pids" 'BEGIN {
        n=split(expected, members, " "); for(i=1;i<=n;i++) wanted[members[i]]=1
      }
      NF!=1 || $1 !~ /^[0-9]+$/ || !($1 in wanted) || seen[$1]++ {bad=1; exit}
      END {exit (bad || NR != n)}' cgroup.procs || return 1
    same_members && cached_uid || return 1
    for p in $pids; do
      [ "$(awk -F: '$1=="0" && $2=="" {print $3}' "/proc/$p/cgroup")" = "$cg" ] &&
        process "$p" check || return 1
    done
  }
  before=$(readn memory.current); swap0=$(readn memory.swap.current)
  is_leaf_group || info '目标叶组或缓存进程身份发生变化。'
  if (printf '%s\n' "$budget" > memory.reclaim) 2>/dev/null; then disposition='内核已受理'; complete=1
  else disposition='内核未完整受理请求，部分页可能已回收'; complete=0; fi
  after=$(readn memory.current); swap1=$(readn memory.swap.current)
  is_leaf_group || partial_result "$disposition $budget B。之后组成员或状态变化，结果不可比较。"
  report="$disposition $budget B。组计费内存 $before → $after B，组交换 $swap0 → $swap1 B。"
  [ "$complete" = 1 ] || partial_result "$report"
  done_result "$report"
}

case "$scope" in
  DENTRY|INODE|F2FS_INODE|EXT4_INODE) slub;;
  ION_POOL|BINDER_POOL|KGSL_POOL) driver_pool;;
  GPU_PROCESS) gpu_process;;
  MEMCG) memcg;;
  MGLRU) mglru;;
  DAMON|KSM) trial;;
  ZRAM_MARK_IDLE|ZRAM_RECOMPRESS|ZRAM_WRITEBACK|ZRAM_DEDUP) zram_scope;;
  MMD_POLICY)
    service=$(getprop init.svc.mmd 2>/dev/null)
    sdk=$(getprop ro.build.version.sdk 2>/dev/null)
    configured=$(getprop mmd.zram.enabled 2>/dev/null)
    detail="Android API ${sdk:-?}，mmd 服务状态 ${service:-未报告}，zRAM 托管覆盖值 ${configured:-未显式配置}。"
    if [ "$service" = running ]; then
      budget=$(getprop mmd.zram.writeback.max_bytes_per_day 2>/dev/null)
      recompress=$(getprop mmd.zram.recompression.enabled 2>/dev/null)
      detail="$detail 系统写回日预算覆盖值 ${budget:-未显式配置}；二次压缩覆盖值 ${recompress:-未显式配置}。进程写回及切回预取由系统协调。"
    fi
    info "$detail";;
  VM_COMPACT)
    file=/proc/sys/vm/compact_memory
    control "$file" || info '系统未导出可写的物理页规整接口。'
    metrics() { awk '$1=="compact_stall" || $1=="compact_success" || $1=="compact_fail" || $1=="compact_migrate_scanned" {printf "%s=%s ",$1,$2}' /proc/vmstat; }
    a=$(metrics); ready "内核可执行全机规整。当前全机累计计数 ${a:-不可读}。"
    write_value "$file" 1; b=$(metrics)
    done_result "已请求全机物理页规整。之前 ${a:-不可读}；之后 ${b:-不可读}。这些是整机计数，直接规整等指标未必反映这次手动操作。";;
  LOCKED_CMA)
    out=$(awk '$1=="Mlocked:" || $1=="Unevictable:" || $1=="CmaTotal:" || $1=="CmaFree:" || $1=="KernelStack:" || $1=="PageTables:" {print $1,$2,$3}' /proc/meminfo)
    info "${out:-内核未提供相应统计。}
锁定及设备固定的页应由所有者归还，CMA 空闲页可能已计入可用内存。";;
  *) fail '未知操作。';;
esac
