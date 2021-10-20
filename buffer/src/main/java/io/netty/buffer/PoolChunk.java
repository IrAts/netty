/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.PriorityQueue;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > run   - a run is a collection of pages
 * > chunk - a chunk is a collection of runs
 * > in this code chunkSize = maxPages * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 * <p>
 * 在开始时，我们预先分配一个长度为 chunkSize 的字节数组。当要申请一个指定长度的 ByteBUf 时，我们
 * 在预先分配的字节数组中寻找第一个具有足够空间以容纳请求大小的位置，并返回编码此偏移量信息的(长)句柄。
 * 这个句柄指向的内存段因此会被标记为 reserved(已被预定).
 * <p>
 * For simplicity all sizes are normalized according to {@link PoolArena#size2SizeIdx(int)} method.
 * This ensures that when we request for memory segments of size > pageSize the normalizedCapacity
 * equals the next nearest size in {@link SizeClasses}.
 * <p>
 * 为了简单起见，所有大小都按照{@link PoolArena#size2SizeIdx(int)}方法进行规范化。
 * 这确保当我们请求的内存段大于 pageSize 时，normalizedCapacity 等于{@link SizeClasses}中最近的大小。
 *
 *
 *  A chunk has the following layout:
 *  一个chunk的布局如下：
 *
 *     /-----------------\
 *     | run             |
 *     |                 |
 *     |                 |
 *     |-----------------|
 *     | run             |
 *     |                 |
 *     |-----------------|
 *     | unalloctated    |
 *     | (freed)         |
 *     |                 |
 *     |-----------------|
 *     | subpage         |
 *     |-----------------|
 *     | unallocated     |
 *     | (freed)         |
 *     | ...             |
 *     | ...             |
 *     | ...             |
 *     |                 |
 *     |                 |
 *     |                 |
 *     \-----------------/
 *
 *
 * handle:
 * -------
 * a handle is a long number, the bit layout of a run looks like:
 * 句柄是一个长数字，运行的位布局看起来像:
 *
 * oooooooo ooooooos ssssssss ssssssue bbbbbbbb bbbbbbbb bbbbbbbb bbbbbbbb
 *
 * o: runOffset (page offset in the chunk), 15bit - 15位作run的偏移量
 * s: size (number of pages) of this run, 15bit - 15位作run中的page数
 * u: isUsed?, 1bit - 1位标记是否被使用
 * e: isSubpage?, 1bit - 1位标记是否有subpage
 * b: bitmapIdx of subpage, zero if it's not subpage, 32bit - 32位指示subpage的bitmapIdx，如没有subpage则为0
 *
 * runsAvailMap:
 * ------
 * a map which manages all runs (used and not in used).
 * For each run, the first runOffset and last runOffset are stored in runsAvailMap.
 * 用于管理所有 run 的 map。
 * key: runOffset
 * value: handle
 *
 * runsAvail:
 * ----------
 * an array of {@link PriorityQueue}.
 * Each queue manages same size of runs.
 * Runs are sorted by offset, so that we always allocate runs with smaller offset.
 * 一个{@link PriorityQueue}数组，每个queue管理相同大小的run，
 * 这些 run 根据 offset 排序。所以我们总是用更小的偏移量来分配 run。
 *
 * Algorithm:
 * ----------
 *
 *   As we allocate runs, we update values stored in runsAvailMap
 *   and runsAvail so that the property is maintained.
 *   随着我们分配 run，我们会更新存储在runsAvailMap和runsAvail中的值，以便维护属性。
 *
 * Initialization -
 *  In the beginning we store the initial run which is the whole chunk.
 *  在运行初期，我们存储初始的run，也就是整个chunk。
 *  The initial run:
 *  runOffset = 0
 *  size = chunkSize
 *  isUsed = no
 *  isSubpage = no
 *  bitmapIdx = 0
 *
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) find the first avail run using in runsAvails according to size
 * 2) if pages of run is larger than request pages then split it, and save the tailing run
 *    for later using
 * 1) 根据大小在 runsAvails 中查找第一个效用的 run。
 * 2) 如果A的页面大于请求页面，则拆分它，并保存尾A以便以后使用
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) find a not full subpage according to size.
 *    if it already exists just return, otherwise allocate a new PoolSubpage and call init()
 *    note that this subpage object is added to subpagesPool in the PoolArena when we init() it
 * 2) call subpage.allocate()
 *
 * Algorithm: [free(handle, length, nioBuffer)]
 * ----------
 * 1) if it is a subpage, return the slab back into this subpage
 * 2) if the subpage is not used or it is a run, then start free this run
 * 3) merge continuous avail runs
 * 4) save the merged run
 *
 *
 * <h2>中文</h2>
 * PoolChunk 对象中有两个重要的变量用来替换 jemalloc3 的树的结构，分别是 LongPriorityQueue[] runsAvail 和 LongLongHashMap runsAvailMap。
 *
 * <h3>run</h3>
 * run 并没有对应实体类，它是一个逻辑上的概念，指代由若干个连续的 page 组成的内存块，
 * 可以被 long 型的 handle 表示。随着内存块的分配和回收，PoolChunk 会管理着若干个不连续的 run。
 *
 * <h3>LongLongHashMap runsAvailMap</h3>
 * 这个是特殊的存储 long 原型的 HashMap，底层采用线性探测法。Netty 使用 LongLongHashMap 存储某个 run 的首页偏
 * 移量和句柄值的映射关系、最后一页偏移量和句柄值的映射关系。至于为什么这么存储，这是为了在向前、向后合并的过程中能
 * 通过 pageOffset 偏移量获取句柄值，进而判断是否可以进行向前合并操作。
 *
 * <h3>LongPriorityQueue[] runsAvail</h3>
 * LongPriorityQueue 属于小顶堆，存储 long （非 Long）型的句柄值，通过 LongPriorityQueue#poll() 方法每次都
 * 能获取小顶堆内部的最小的 handle 值。这表示我们每次申请内存都是从最低位地址开始分配。而在 PoolChunk 内部有一个
 * LongPriorityQueue[] 数组，所有存储在 LongPriorityQueue 对象的 handle 都表示一个可用的 run，它的默认长度为
 * 40(这个40来源于{@link SizeClasses}表中 isMultiPageSize=1 的项数)。
 * runsAvail 存放着空闲的 run，这些 run 等待着被分配出去。为了方便管理不同大小的 run，runsAvail 被定义为一个数组。
 * 假设一个空闲 run 的包含的 page 数量为 count，这个 run 由如下规则指定存放到 runsAvail 的哪个元素中：
 *    count <= 7, run 放在 runsAvail[count-1]；
 *    count = 8~9,run 放在 runsAvail[7]；
 *    count = 10~11,run 放在 runsAvail[8]；
 *    count = 12~13,run 放在 runsAvail[9]；
 *    ...... （可从{@link SizeClasses}注释中的SizeClasses#pageIdx2sizeTab小节找到对应关系）
 *    count = 1290~1535, run 放在 runsAvail[36]；
 *    count = 1536~1791, run 放在 runsAvail[37]；
 *    count = 1290~1535, run 放在 runsAvail[38]；
 *    count = 2048,      run 放在 runsAvail[39]；
 *
 * <h3>句柄handle</h3>
 * 一个句柄的位图如下：
 *     oooooooo ooooooos ssssssss ssssssue bbbbbbbb bbbbbbbb bbbbbbbb bbbbbbbb
 *    o: 15位的 pageOffset，指示这个run首页的偏移量
 *    s: 15位的 pageCount，指示这个run重包含的page的数量。
 *    u: isUsed?, 1bit - 1位标记是否被使用
 *    e: isSubpage?, 1bit - 1位标记是否有subpage
 *    b: bitmapIdx of subpage, zero if it's not subpage, 32bit - 32位指示subpage的bitmapIdx，如没有subpage则为0
 *
 * PoolChunk 默认向 JVM 申请个 16MB 的大的内存块，并拆分成 2048 个 page。可以想象为每个 page 进行标号，从 0 开始一
 * 直到 2047。通过 pageOffset + pageCount 就能定位某一个 run 区域，它是由 pageCount 个 page 组成的，起始的 page
 * 为 pageOffset。对 ByteBuffer 对象而言，内部会有一个 long 型的 memoryAddress 绝对地址，因此可以通过绝对地址 + 偏
 * 移量定位任意 page 的实际地址。run 表示的是由若干个 page 组成的内存块。而 isUsed 则是标记当前 run 的使用状态。
 * isSubpage 表示当前 run 是否用于 Small 级别内存分配。后 32 位表示 bitmap 的索引信息，与 jemalloc3 表示的含义一样。
 *
 * <h3>内存分配思路</h3>
 * 首先尝试从本地缓存中分配，分配成功则返回，分配失败则委托 PoolArena 进行内存分配，PoolArena 最终还是委托 PoolChunk
 * 进行内存分配，而 PoolChunk 就是 jemalloc4 算法的核心体现。Netty 在 jemalloc4 算法中取消了 Tiny，因此，只会有三种
 * 规格，分别是 Small、Normal 以及 Huge。先总述一下 jemalloc4（Netty 实现）的算法分配逻辑:
 *    1. Netty 默认一次性向 JVM 申请 16MB 大小的内存块，也是划分为 2048 个page，每个 page 大小为 8192（8KB）
 *    2. 首先，根据用户申请的内存大小在 SizeClasses 查表得到确定的 index 索引值。
 *    3. 通过判断 index 大小就可以知道采用什么策略。当 index<=38（对应 size<=28KB）时，表示当前分配 Small 级别大小的
 *       内存块，采用 Small 级别分配策略。对于 38<index<nSize(75)（对应 size取值范围为 (28KB, 16MB]）表示当前分配
 *       Normal 级别大小的内存块，采用 Normal 级别分配策略。对于 index 的其他值，则对应 Huge 级别。
 *    4. 先讲 Normal 级别的内存分配，它有一个特点就是所需要的内存大小是 pageSize 的整数倍，PoolChunk 会从能满足当前分
 *       配的 run（由 long 型的句柄表示，从 LongPriorityQueue[] 数组中搜索第一个最合适的 run） 中得到若干个 page。
 *       当某一个 run 分配若干个 page 之后，可会还会有剩余空闲的 page，那么它根据剩余的空闲 pages 数量会从
 *       LongPriorityQueue[] 数组选取一个合适的 LongPriorityQueue 存放全新的 run（handle 表示）。
 *    5. 对于 Small 级别的内存分配，经过 SizeClass 规格化后得到规格值 size，然后求得 size 和 pageSize 最小公倍数 j，
 *       j 一定是 pageSize 的整数倍。然后再按照 Normal 级别的内存分配方式从第一个适合的 run 中分配 (j/pageSize) 数量
 *       的 page。接着将 page 所组成的内存块拆分成等分的 subpage，并使用 long[] 记录每份 subpage 的使用状态。
 *
 * <h3>run的回收</h3>
 * 在回收某一个 run 之前，先尝试向前搜索并合并相邻的空闲的 run，得到一个全新的 handle。然后再向后搜索并合并相邻的空闲的
 * run，得到一个全新的 handle。再把 handle 写回 LongPriorityQueue 和 LongLongHashMap 中，以待下次分配时使用。
 *
 * <h3>分配Small级别内存块</h3>
 *
 *
 *
 *
 */
final class PoolChunk<T> implements PoolChunkMetric {
    private static final int SIZE_BIT_LENGTH = 15;
    private static final int INUSED_BIT_LENGTH = 1;
    private static final int SUBPAGE_BIT_LENGTH = 1;
    private static final int BITMAP_IDX_BIT_LENGTH = 32;

    static final int IS_SUBPAGE_SHIFT = BITMAP_IDX_BIT_LENGTH;
    static final int IS_USED_SHIFT = SUBPAGE_BIT_LENGTH + IS_SUBPAGE_SHIFT;
    static final int SIZE_SHIFT = INUSED_BIT_LENGTH + IS_USED_SHIFT;
    static final int RUN_OFFSET_SHIFT = SIZE_BIT_LENGTH + SIZE_SHIFT;

    /**
     * netty内存池总的数据结构。
     */
    final PoolArena<T> arena;
    final Object base;
    /**
     * 当前申请的内存块。默认大小是16M。
     * 对于堆内存，T就是一个byte数组，对于直接内存，T就是ByteBuffer。
     */
    final T memory;
    /**
     * 指定当前是否使用内存池的方式进行管理。
     */
    final boolean unpooled;

    /**
     * store the first page and last page of each avail run
     * 这个是特殊的存储 long 原型的 HashMap，底层采用线性探测法。Netty 使用 LongLongHashMap 存储某个 run 的首页偏
     * 移量和句柄值的映射关系、最后一页偏移量和句柄值的映射关系。至于为什么这么存储，这是为了在向前、向后合并的过程中能
     * 通过 pageOffset 偏移量获取句柄值，进而判断是否可以进行向前合并操作。
     */
    private final LongLongHashMap runsAvailMap;

    /**
     * manage all avail runs
     * LongPriorityQueue 属于小顶堆，存储 long （非 Long）型的句柄值，通过 LongPriorityQueue#poll() 方法每次都
     * 能获取小顶堆内部的最小的 handle 值。这表示我们每次申请内存都是从最低位地址开始分配。而在 PoolChunk 内部有一个
     * LongPriorityQueue[] 数组，所有存储在 LongPriorityQueue 对象的 handle 都表示一个可用的 run，它的默认长度为
     * 40，为什么是 40 会在源码讲解时解释。
     */
    private final LongPriorityQueue[] runsAvail;

    /**
     * manage all subpages in this chunk.
     * 这里每一个PoolSubPage代表了二叉树的一个叶节点，也就是说，当二
     * 叉树叶节点内存被分配之后，其会使用一个PoolSubPage对其进行封装。
     */
    private final PoolSubpage<T>[] subpages;

    /**
     * 记录了每个叶节点内存的大小，默认为8192，即8KB
     */
    private final int pageSize;
    /**
     * 值为13。
     * 该值可用于计算一个 run 中有多少个 page ，或者计算n个 page 的大小是多少字节。
     * 我们知道一个 run 包含多个 page，即 runSize 必然为 pageSize 的整数倍。
     * 又由于一个 pageSize 的默认为8192(8Kib)，是2的幂次方，pageSize对应的位图为：
     *     0000 0000 0000 0000 0010 0000 0000 0000 == 1 << 13
     * 故通过 page 个数计算 runSize 或者通过 runSize 计算 page 个数时可使用位操作。
     *     通过 page 个数计算 runSize ：runSize = n << pageShifts
     *     通过 runSize 计算 page 个数：n = runSize >> pageShifts
     */
    private final int pageShifts;
    /**
     * 记录了当前整个PoolChunk申请的内存大小，默认为16M
     */
    private final int chunkSize;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    /**
     * 对创建的ByteBuffer进行缓存的一个队列
     */
    private final Deque<ByteBuffer> cachedNioBuffers;

    /**
     * 记录了当前PoolChunk中还剩余的可申请的字节数
     */
    int freeBytes;
    /**
     * 记录已经被分配出去的字节数
     */
    int pinnedBytes;

    /**
     * 在Netty的内存池中，所有的PoolChunk都是由当前PoolChunkList进行组织的，
     */
    PoolChunkList<T> parent;
    /**
     * 在PoolChunkList中当前PoolChunk的前置节点
     */
    PoolChunk<T> prev;
    /**
     * 在PoolChunkList中当前PoolChunk的后置节点
     */
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    @SuppressWarnings("unchecked")
    PoolChunk(PoolArena<T> arena, Object base, T memory, int pageSize, int pageShifts, int chunkSize, int maxPageIdx) {
        unpooled = false;
        this.arena = arena;
        this.base = base;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        freeBytes = chunkSize;

        runsAvail = newRunsAvailqueueArray(maxPageIdx);
        runsAvailMap = new LongLongHashMap(-1);
        subpages = new PoolSubpage[chunkSize >> pageShifts];

        //insert initial run, offset = 0, pages = chunkSize / pageSize
        int pages = chunkSize >> pageShifts;
        long initHandle = (long) pages << SIZE_SHIFT;
        insertAvailRun(0, pages, initHandle);

        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, Object base, T memory, int size) {
        unpooled = true;
        this.arena = arena;
        this.base = base;
        this.memory = memory;
        pageSize = 0;
        pageShifts = 0;
        runsAvailMap = null;
        runsAvail = null;
        subpages = null;
        chunkSize = size;
        cachedNioBuffers = null;
    }

    private static LongPriorityQueue[] newRunsAvailqueueArray(int size) {
        LongPriorityQueue[] queueArray = new LongPriorityQueue[size];
        for (int i = 0; i < queueArray.length; i++) {
            queueArray[i] = new LongPriorityQueue();
        }
        return queueArray;
    }

    /**
     * 从 runsAvail 数组中选择合适的 LongPriorityQueue 并写入。
     *
     * @param runOffset run首页偏移量
     * @param pages 页数量
     * @param handle 句柄值
     */
    private void insertAvailRun(int runOffset, int pages, long handle) {
        // #1 将句柄信息写入对应的小顶堆
        // 根据页数量向下取整，获得「pageIdxFloor」，这个值即将写入对应runsAvail数组索引的值
        int pageIdxFloor = arena.pages2pageIdxFloor(pages);
        LongPriorityQueue queue = runsAvail[pageIdxFloor];
        queue.offer(handle);

        // #2 将首页和末页的偏移量和句柄值记录在runsAvailMap对象，待合并run时使用
        insertAvailRun0(runOffset, handle);
        if (pages > 1) {
            // 当页数量超过1时才会记录末页的偏移量和句柄值
            insertAvailRun0(lastPage(runOffset, pages), handle);
        }
    }

    private void insertAvailRun0(int runOffset, long handle) {
        long pre = runsAvailMap.put(runOffset, handle);
        assert pre == -1;
    }

    private void removeAvailRun(long handle) {
        int pageIdxFloor = arena.pages2pageIdxFloor(runPages(handle));
        LongPriorityQueue queue = runsAvail[pageIdxFloor];
        removeAvailRun(queue, handle);
    }

    private void removeAvailRun(LongPriorityQueue queue, long handle) {
        queue.remove(handle);

        int runOffset = runOffset(handle);
        int pages = runPages(handle);
        //remove first page of run
        runsAvailMap.remove(runOffset);
        if (pages > 1) {
            //remove last page of run
            runsAvailMap.remove(lastPage(runOffset, pages));
        }
    }

    private static int lastPage(int runOffset, int pages) {
        return runOffset + pages - 1;
    }

    private long getAvailRunByOffset(int runOffset) {
        return runsAvailMap.get(runOffset);
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    /**
     * 可以完成 Small&Normal 两种级别的内存分配，它是根据 sizeIdx 采用不同的分配策略。
     *
     * @param buf
     * @param reqCapacity
     * @param sizeIdx
     * @param cache
     * @return
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache cache) {
        final long handle;
        // arena.smallMaxSizeIdx 为38，可通过{@link SizeClasses}的注释查看
        // sizeIdx <= 38 的潜台词为：想要申请的内存块大小<=28KB
        if (sizeIdx <= arena.smallMaxSizeIdx) {
            // small
            // 分配Small规格内存块
            handle = allocateSubpage(sizeIdx);
            if (handle < 0) {
                return false;
            }
            assert isSubpage(handle);
        } else {
            // normal
            // runSize must be multiple of pageSize
            // 分配Normal级别内存块，runSize是pageSize的整数倍
            int runSize = arena.sizeIdx2size(sizeIdx);
            handle = allocateRun(runSize);
            // 如果返回的handle小于0，则表示要申请的内存大小超过了当前PoolChunk所能够申请的最大大小，也即16M，
            // 因而返回false，外部代码则会直接申请目标内存，而不由当前PoolChunk处理。
            if (handle < 0) {
                return false;
            }
        }

        // 尝试从cachedNioBuffers缓存中获取ByteBuffer对象并在ByteBuf对象初始化时使用
        ByteBuffer nioBuffer = cachedNioBuffers != null? cachedNioBuffers.pollLast() : null;
        // 通过申请到的内存数据对获取到的ByteBuf对象进行初始化，如果ByteBuf为null，则创建一个新的然后进行初始化
        // 初始化ByteBuf对象
        initBuf(buf, nioBuffer, handle, reqCapacity, cache);
        return true;
    }

    /**
     * 分配run(也就是根据 runSize 分配若干个 page)。
     * 首先会根据 runSize 计算出需要的 page 数量，然后根据 page 数获取到对应的 LongPriorityQueue。
     * 接着从 LongPriorityQueue 中获取一个空闲的run，这个获取出来的空闲的 run 的空间大小必然大于等于 runSize。
     * 最后根据空闲 run 的大小来决定是否需要对这个 run 进行切分。
     * 如果进行切分，则将切分后空余的部分放入到合适的 LongPriorityQueue中。
     *
     * @param runSize
     * @return
     */
    private long allocateRun(int runSize) {
        // 计算这个 runSize 对应的 page 数量。
        int pages = runSize >> pageShifts;
        // 根据 page 数量计算出 pageIdx
        int pageIdx = arena.pages2pageIdx(pages);

        // runsAvail 属于并发变量，需要加锁
        synchronized (runsAvail) {
            //find first queue which has at least one big enough run
            // 从「LongPriorityQueue[]」数组中找到最合适的run用于当前的内存分配请求。
            // 起始位置为「pageIdx」，并向后遍历直到数组的末尾或找到合适的run
            // 如果没有找到，返回-1
            int queueIdx = runFirstBestFit(pageIdx);
            if (queueIdx == -1) {
                return -1;
            }

            //get run with min offset in this queue
            // 获取「LongPriorityQueue」，该对象包含若干个可用的 run
            LongPriorityQueue queue = runsAvail[queueIdx];
            // 从「LongPriorityQueue」小顶堆中获取可用的 run（由handle表示）
            // 小顶堆能保证始终保持从低地址开始分配
            long handle = queue.poll();

            assert handle != LongPriorityQueue.NO_VALUE && !isUsed(handle) : "invalid handle: " + handle;

            // 先将「handle」从该小顶堆中移除，因为我们有可能需要对它进行修改
            removeAvailRun(queue, handle);

            if (handle != -1) {
                // 可能会把「run」拆分成两部分。为什么说可能呢?因为这个run可能刚好满足此次分配需求，所以不用拆分。
                // 也有可能这个run满足了本次分配需求的前提下还剩余了一些page，
                // 所以这个run需要被切分成两部分。一部分用于当前内存申请。另一部分则剩余空闲内存块。
                // 剩余空闲内存块则会放到合适的LongPriorityQueue数组中，待下次分配时使用。
                // 返回的 handle 表示当前内存申请的句柄信息
                handle = splitLargeRun(handle, pages);
            }

            int pinnedSize = runSize(pageShifts, handle);
            // 更新剩余空间值
            freeBytes -= pinnedSize;
            // 更新也被分配的空间值
            pinnedBytes += pinnedSize;
            return handle;
        }
    }

    private int calculateRunSize(int sizeIdx) {
        int maxElements = 1 << pageShifts - SizeClasses.LOG2_QUANTUM;
        int runSize = 0;
        int nElements;

        final int elemSize = arena.sizeIdx2size(sizeIdx);

        //find lowest common multiple of pageSize and elemSize
        do {
            runSize += pageSize;
            nElements = runSize / elemSize;
        } while (nElements < maxElements && runSize != nElements * elemSize);

        while (nElements > maxElements) {
            runSize -= pageSize;
            nElements = runSize / elemSize;
        }

        assert nElements > 0;
        assert runSize <= chunkSize;
        assert runSize >= elemSize;

        return runSize;
    }

    /**
     * pageIdx 与页面数成一定的正比例关系。
     * 此方法就是为了从 runsAvail 的 pageIdx 位置开始，
     * 向右找到第一个能够不为空的 LongPriorityQueue。
     *
     * @param pageIdx
     * @return
     */
    private int runFirstBestFit(int pageIdx) {
        if (freeBytes == chunkSize) {
            return arena.nPSizes - 1;
        }
        for (int i = pageIdx; i < arena.nPSizes; i++) {
            LongPriorityQueue queue = runsAvail[i];
            if (queue != null && !queue.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从 handle 代表的内存块中获取撕裂出前方的 needPages 数量的 page。
     * 剩余的 page 则作为新的 run 存放到合适的 LongPriorityQueue 中。
     *
     * @param handle
     * @param needPages
     * @return
     */
    private long splitLargeRun(long handle, int needPages) {
        assert needPages > 0;

        // #1 获取run管理的空闲的page数量
        int totalPages = runPages(handle);
        assert needPages <= totalPages;

        // #2 计算剩余数量（总数-需要数量）
        int remPages = totalPages - needPages;

        // #3 如果还有剩余，需要重新生成run（由handle具象化）并写入两个重要的数据结构中
        // 一个是 LongLongHashMap runsAvailMap，另一个是 LongPriorityQueue[] runsAvail;
        if (remPages > 0) {
            // #3-1 获取偏移量
            int runOffset = runOffset(handle);

            // #3-2 剩余空闲页偏移量=旧的偏移量+分配页数
            int availOffset = runOffset + needPages;
            // #3-3 根据偏移量、页数量以及isUsed状态生成新的句柄变量，这个变量表示一个全新未使用的run
            long availRun = toRunHandle(availOffset, remPages, 0);
            // #3-4 更新两个重要的数据结构
            insertAvailRun(availOffset, remPages, availRun);

            // #3-5 生成用于此次分配的句柄变量
            return toRunHandle(runOffset, needPages, 1);
        }

        // #4 恰好满足，只需把handle的isUsed标志位置为1
        handle |= 1L << IS_USED_SHIFT;
        // #5 大功告成，返回
        return handle;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity. Any PoolSubpage created / initialized here is added to
     * subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param sizeIdx sizeIdx of normalized size
     *
     * @return index in memoryMap
     */
    private long allocateSubpage(int sizeIdx) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        PoolSubpage<T> head = arena.findSubpagePoolHead(sizeIdx);
        synchronized (head) {
            //allocate a new run
            int runSize = calculateRunSize(sizeIdx);
            //runSize must be multiples of pageSize
            long runHandle = allocateRun(runSize);
            if (runHandle < 0) {
                return -1;
            }

            int runOffset = runOffset(runHandle);
            assert subpages[runOffset] == null;
            int elemSize = arena.sizeIdx2size(sizeIdx);

            PoolSubpage<T> subpage = new PoolSubpage<T>(head, this, pageShifts, runOffset,
                               runSize(pageShifts, runHandle), elemSize);

            subpages[runOffset] = subpage;
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages When a subpage is freed from PoolSubpage, it might be added back to subpage pool
     * of the owning PoolArena. If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize,
     * we can completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, int normCapacity, ByteBuffer nioBuffer) {
        int runSize = runSize(pageShifts, handle);
        pinnedBytes -= runSize;
        if (isSubpage(handle)) {
            int sizeIdx = arena.size2SizeIdx(normCapacity);
            PoolSubpage<T> head = arena.findSubpagePoolHead(sizeIdx);

            int sIdx = runOffset(handle);
            PoolSubpage<T> subpage = subpages[sIdx];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            synchronized (head) {
                if (subpage.free(head, bitmapIdx(handle))) {
                    //the subpage is still used, do not free it
                    return;
                }
                assert !subpage.doNotDestroy;
                // Null out slot in the array as it was freed and we should not use it anymore.
                subpages[sIdx] = null;
            }
        }

        //start free run
        synchronized (runsAvail) {
            // collapse continuous runs, successfully collapsed runs
            // will be removed from runsAvail and runsAvailMap
            long finalRun = collapseRuns(handle);

            //set run as not used
            finalRun &= ~(1L << IS_USED_SHIFT);
            //if it is a subpage, set it to run
            finalRun &= ~(1L << IS_SUBPAGE_SHIFT);

            insertAvailRun(runOffset(finalRun), runPages(finalRun), finalRun);
            freeBytes += runSize;
        }

        if (nioBuffer != null && cachedNioBuffers != null &&
            cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    private long collapseRuns(long handle) {
        return collapseNext(collapsePast(handle));
    }

    private long collapsePast(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            int runPages = runPages(handle);

            long pastRun = getAvailRunByOffset(runOffset - 1);
            if (pastRun == -1) {
                return handle;
            }

            int pastOffset = runOffset(pastRun);
            int pastPages = runPages(pastRun);

            //is continuous
            if (pastRun != handle && pastOffset + pastPages == runOffset) {
                //remove past run
                removeAvailRun(pastRun);
                handle = toRunHandle(pastOffset, pastPages + runPages, 0);
            } else {
                return handle;
            }
        }
    }

    private long collapseNext(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            int runPages = runPages(handle);

            long nextRun = getAvailRunByOffset(runOffset + runPages);
            if (nextRun == -1) {
                return handle;
            }

            int nextOffset = runOffset(nextRun);
            int nextPages = runPages(nextRun);

            //is continuous
            if (nextRun != handle && runOffset + runPages == nextOffset) {
                //remove next run
                removeAvailRun(nextRun);
                handle = toRunHandle(runOffset, runPages + nextPages, 0);
            } else {
                return handle;
            }
        }
    }

    private static long toRunHandle(int runOffset, int runPages, int inUsed) {
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) runPages << SIZE_SHIFT
               | (long) inUsed << IS_USED_SHIFT;
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                 PoolThreadCache threadCache) {
        if (isRun(handle)) {
            buf.init(this, nioBuffer, handle, runOffset(handle) << pageShifts,
                     reqCapacity, runSize(pageShifts, handle), arena.parent.threadCache());
        } else {
            initBufWithSubpage(buf, nioBuffer, handle, reqCapacity, threadCache);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                            PoolThreadCache threadCache) {
        int runOffset = runOffset(handle);
        int bitmapIdx = bitmapIdx(handle);

        PoolSubpage<T> s = subpages[runOffset];
        assert s.doNotDestroy;
        assert reqCapacity <= s.elemSize;

        int offset = (runOffset << pageShifts) + bitmapIdx * s.elemSize;
        buf.init(this, nioBuffer, handle, offset, reqCapacity, s.elemSize, threadCache);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    public int pinnedBytes() {
        synchronized (arena) {
            return pinnedBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }

    static int runOffset(long handle) {
        return (int) (handle >> RUN_OFFSET_SHIFT);
    }

    static int runSize(int pageShifts, long handle) {
        return runPages(handle) << pageShifts;
    }

    static int runPages(long handle) {
        return (int) (handle >> SIZE_SHIFT & 0x7fff);
    }

    static boolean isUsed(long handle) {
        return (handle >> IS_USED_SHIFT & 1) == 1L;
    }

    static boolean isRun(long handle) {
        return !isSubpage(handle);
    }

    static boolean isSubpage(long handle) {
        return (handle >> IS_SUBPAGE_SHIFT & 1) == 1L;
    }

    static int bitmapIdx(long handle) {
        return (int) handle;
    }
}
