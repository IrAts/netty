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

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.buffer.PoolChunk.isSubpage;
import static java.lang.Math.max;

/**
 * <pre>
 * Netty 分配内存的逻辑是和 jemalloc3 大致相同：
 *     1.首先尝试从本地缓存中分配，分配成功则返回。
 *     2.分配失败则委托 PoolArena 分配内存，PoolArena 最终还是委托 PoolChunk 进行内存分配。
 *     3.PoolChunk 根据内存规格采取不同的分配策略。
 *     4.内存回收时也是先通过本地线程缓存回收，如果实在回收不了或超出阈值，会交给关联的 PoolChunk 进行内存块回收。
 * </pre>
 *
 * 首先我们要知道 PooledByteBufAllocator 是线程安全的类，我们可以通过 PooledByteBufAllocator.DEFAULT 获
 * 得一个 io.netty.buffer.PooledByteBufAllocator 池化分配器，这也是 Netty 推荐的做法之一。我们也了解到，
 * PooledByteBufAllocator 会初始两个重要的数组，分别是 heapArenas 和 directArenas，所有的与内存分配相关的
 * 操作都会委托给 heapArenas 或 directArenas 处理，数组长度一般是通过 2*CPU_CORE 计算得到。这里体现 Netty
 * （准确地说应该是 jemalloc 算法思想） 内存分配设计理念，通过增加多个 Arenas 减少内存竞争，提高在多线程环境下
 * 分配内存的速度以及效率。数组 arenas 是由 PoolArena 对象构成，它是内存分配的中心枢纽，一位大管家。包括管理
 * PoolChunk 对象、管理 PoolSubpage 对象、分配内存对象的核心逻辑、管理本地对象缓存池、内存池销毁等等，它的侧
 * 重点在于管理已分配的内存对象。而 PoolChunk 是 jemalloc 算法思想的化身，它知道如何有效分配内存，你只需要调
 * 用对应方法就能获取想要大小的内存块，它只专注管理物理内存这件事情，至于分配后的事情，它一概不知，也一概不管，
 * 反正 PoolArena 这个大管家会操心的。
 *
 */
abstract class PoolArena<T> extends SizeClasses implements PoolArenaMetric {
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    enum SizeClass {
        Small,
        Normal
    }

    final PooledByteBufAllocator parent;

    /**
     * smallSubpagePools 数组的长度
     */
    final int numSmallSubpagePools;
    /**
     * 指示分配直接内存时需要的对齐数。
     * 如果是0则表示不要求地址对齐。
     */
    final int directMemoryCacheAlignment;

    /**
     * 默认长度为39。这是因为被认为是 small 级别的size有 39 个。{@link SizeClasses 为什么是39?}
     * 数组中的每个元素对应于每个不同大小级别的 PoolSubpage 链表（PoolSubpage 将一片连续的内存分为多个等大的块）。
     * 如 smallSubpagePools[0] 表示 16B 大小级别的 PoolSubpage 链表。
     * 而 smallSubpagePools[38] 表示 28KB 大小界别的 PoolSubpage 链表。
     */
    private final PoolSubpage<T>[] smallSubpagePools;

    // 任意 PoolChunkList 都有内存使用率的上下限: minUsage、maxUsage。如果使用率超过 maxUsage，那么
    // PoolChunk 会从当前 PoolChunkList 移除，并移动到下一个PoolChunkList 。同理，如果使用率小于
    // minUsage，那么 PoolChunk 会从当前 PoolChunkList 移除，并移动到前一个PoolChunkList。 每个
    // PoolChunkList 的上下限都有交叉重叠的部分，因为 PoolChunk 需要在 PoolChunkList 不断移动，如果临界
    // 值恰好衔接的，则会导致 PoolChunk 在两个 PoolChunkList 不断移动，造成性能损耗。 PoolChunkList 适用
    // 于 Chunk 场景下的内存分配，PoolArena 初始化 6 个 PoolChunkList 并按上图首尾相连，形成双向链表，唯
    // 独 q000 这个 PoolChunkList 是没有前向节点，是因为当其余 PoolChunkList 没有合适的 PoolChunk 可以分
    // 配内存时，会创建一个新的 PoolChunk 放入 pInit 中，然后根据用户申请内存大小分配内存。而在 p000 中的
    // PoolChunk ，如果因为内存归还的原因，使用率下降到 0%，则不需要放入 pInit，直接执行销毁方法，将整个
    // 内存块的内存释放掉。这样，内存池中的内存就有生成/销毁等完成生命周期流程，避免了在没有使用情况下还占用内存。
    /**
     * 0~25%
     */
    private final PoolChunkList<T> qInit;
    /**
     * 1~50%
     */
    private final PoolChunkList<T> q000;
    /**
     * 25~75%
     */
    private final PoolChunkList<T> q025;
    /**
     * 50~100%
     */
    private final PoolChunkList<T> q050;
    /**
     * 75~100%
     */
    private final PoolChunkList<T> q075;
    /**
     * 100%
     */
    private final PoolChunkList<T> q100;

    /**
     * 对上头6个 PoolChunkList 进行监控的工具列表。
     */
    private final List<PoolChunkListMetric> chunkListMetrics;

    /**
     * Metrics for allocations and deallocations.
     * PoolArena 成功分配 Normal 级别内存的次数（不包含本地缓存分配的次数）
     */
    private long allocationsNormal;
    /**
     * PoolArena 成功分配 Small 级别内存的次数（不包含本地缓存分配的次数）
     */
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    /**
     * PoolArena 成功分配 Huge 级别内存的次数（Huge 级别的内存不能进行本地缓存）
     */
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    /**
     * PoolArena 成功分配 Huge 级别内存的总大小（Huge 级别的内存不能进行本地缓存）
     */
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    /**
     * 已经回收 Small 级别内存的次数
     */
    private long deallocationsSmall;
    /**
     * 已经回收 Normal 级别内存的次数
     */
    private long deallocationsNormal;
    /**
     * We need to use the LongCounter here as this is not guarded via synchronized block.
     * 已经回收 Huge 级别内存的次数。
     */
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    /**
     * Number of thread caches backed by this arena.
     * 用来存储存在多少个线程使用当前对象进行内存分配。
     */
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * <ol>
     * <li> 初始化计数值、辅助值
     * <li> 初始化{@link #smallSubpagePools}
     * <li> 初始化 PoolChunkList：{@link #q100}、{@link #q075}、{@link #q050}、{@link #q025}、{@link #q000}、{@link #qInit}
     * <li> 将第三步初始化好的 PoolChunkList 进行连接，形成一个双向链表
     * <li> 将第三步初始化好的 PoolChunkList 放入到监控列表进行性能监测
     * </ol>
     * @param parent 当前的 PoolArena 归属的 PooledByteBufAllocator。
     * @param pageSize page 的大小，默认8KB
     * @param pageShifts page 大小的偏移量，默认值为13 (1 << 13 == 8KB)
     * @param chunkSize chunk 的大小
     * @param cacheAlignment 内存块的对齐量
     */
    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
          int pageShifts, int chunkSize, int cacheAlignment) {
        super(pageSize, pageShifts, chunkSize, cacheAlignment);
        this.parent = parent;
        directMemoryCacheAlignment = cacheAlignment;

        numSmallSubpagePools = nSubpages;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead();
        }

        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    /**
     * 创建 PoolSubpage 链表的头结点head，head.next = head.prev = head;
     * 这个方法会在 PoolArena 构造的时候调用，对 {@link #smallSubpagePools} 初始化头节点。
     */
    private PoolSubpage<T> newSubpagePoolHead() {
        PoolSubpage<T> head = new PoolSubpage<T>();
        head.prev = head;
        head.next = head;
        return head;
    }

    /**
     * 创建 size 大小的 PoolSubpage 数组。
     * 这个方法会在 PoolArena 构建的时候调用，以生成 {@link #smallSubpagePools} 数组。
     * @param size
     * @return
     */
    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    /**
     * 分配内存，分配 huge 级别以下的内存都会先尝试从线程本地缓存中分配，
     * 当无法从线程本地中分配到内存时就到到 chunk 中分配。
     *
     * @param cache
     * @param reqCapacity
     * @param maxCapacity
     * @return
     */
    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        // 创建 PooledByteBuf，此时创建出来的 PooledByteBuf 是没有指向任何内存地址的，只是简单地设置了容量。
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        // 根据请求将足量的内存分配给 PooledByteBuf。
        // 这个方法首先会尝试从本地线程缓存中分配内存，无法分配到内存时才到 chunk 中分配。
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    /**
     * 按不同规格类型采用不同的内存分配策略.
     * 除了huge级别的内存，都先尝试从线程本地缓存 PoolThreadCache 中分配，分配失败再进行实际的分配。
     *
     * @param cache			当前线程的本地线程缓存
     * @param buf			ByteBuf对象，是byte[]或ByteBuffer的承载对象
     * @param reqCapacity	申请内存容量大小
     */
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        // 根据申请容量大小查表确定对应数组下标序号。
        // 具体操作就是先确定 reqCapacity 在第几组，然后在组内的哪个位置。两者相加就是最后的值了
        final int sizeIdx = size2SizeIdx(reqCapacity);

        // 根据下标序号就可以得到对应的规格值
        if (sizeIdx <= smallMaxSizeIdx) { // size <= 28KB
            // 下标序号<=「smallMaxSizeIdx」，表示申请容量大小<=pageSize，属于「Small」级别内存分配
            tcacheAllocateSmall(cache, buf, reqCapacity, sizeIdx);
        } else if (sizeIdx < nSizes) { // 28KB < size <= 16MB
            // 下标序号<「nSizes」，表示申请容量大小介于pageSize和chunkSize之间，属于「Normal」级别内存分配
            tcacheAllocateNormal(cache, buf, reqCapacity, sizeIdx);
        } else {
            // 超出「ChunkSize」，属于「Huge」级别内存分配
            int normCapacity = directMemoryCacheAlignment > 0
                    ? normalizeSize(reqCapacity) : reqCapacity;
            // Huge allocations are never served via the cache so just call allocateHuge
            // 分配的 Huge 内存不会进行线程级别的缓存，所以直接申请就行了。
            allocateHuge(buf, normCapacity);
        }
    }

    private void tcacheAllocateSmall(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity,
                                     final int sizeIdx) {

        if (cache.allocateSmall(this, buf, reqCapacity, sizeIdx)) {
            // was able to allocate out of the cache so move on
            return;
        }

        /*
         * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
         * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
         */
        final PoolSubpage<T> head = smallSubpagePools[sizeIdx];
        final boolean needsNormalAllocation;
        synchronized (head) {
            final PoolSubpage<T> s = head.next;
            needsNormalAllocation = s == head;
            if (!needsNormalAllocation) {
                assert s.doNotDestroy && s.elemSize == sizeIdx2size(sizeIdx);
                long handle = s.allocate();
                assert handle >= 0;
                s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity, cache);
            }
        }

        if (needsNormalAllocation) {
            synchronized (this) {
                allocateNormal(buf, reqCapacity, sizeIdx, cache);
            }
        }

        incSmallAllocation();
    }

    /**
     * 尝试先从本地线程缓存中分配内存，尝试失败，
     * 就会从不同使用率的「PoolChunkList」链表中寻找合适的内存空间并完成分配。
     * 如果这样还是不行，那就只能创建一个船新PoolChunk对象。
     *
     * @param cache 本地线程缓存，用来提高内存分配效率
     * @param buf ByteBuf承载对象
     * @param reqCapacity 用户申请的内存大小
     * @param sizeIdx 对应{@link SizeClasses}的索引值，可以通过该值从{@link SizeClasses}中获取相应的规格值
     */
    private void tcacheAllocateNormal(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity,
                                      final int sizeIdx) {
        // 首先尝试从「本地线程缓存(线程私有变量，不需要加锁)」分配内存
        if (cache.allocateNormal(this, buf, reqCapacity, sizeIdx)) {
            // 尝试成功，直接返回。本地线程会完成对「ByteBuf」对象的初始化工作
            // was able to allocate out of the cache so move on
            return;
        }
        // 因为对「PoolArena」对象来说，内部的PoolChunkList会存在线程竞争，需要加锁
        synchronized (this) {
            // 委托给「PoolChunk」对象完成内存分配
            allocateNormal(buf, reqCapacity, sizeIdx, cache);
            ++allocationsNormal;
        }
    }

    // Method must be called inside synchronized(this) { ... } block
    /**
     * 先从「PoolChunkList」链表中选取某一个「PoolChunk」进行内存分配，如果实在找不到合适的「PoolChunk」对象，
     * 那就只能新建一个船新的「PoolChunk」对象，在完成内存分配后需要添加到对应的PoolChunkList链表中。
     * 内部有多个「PoolChunkList」链表，q050、q025表示内部的「PoolChunk」最低的使用率。
     * Netty 会先从q050开始分配，并非从q000开始。
     * 这是因为如果从q000开始分配内存的话会导致有大部分的PoolChunk面临频繁的创建和销毁，造成内存分配的性能降低。
     *
     * @param buf         ByeBuf承载对象
     * @param reqCapacity 用户所需要真实的内存大小
     * @param sizeIdx     对应{@link SizeClasses}的索引值，可以通过该值从{@link SizeClasses}中获取相应的规格值
     * @param threadCache 本地线程缓存，这个缓存主要是为了初始化PooledByteBuf时填充对象内部的缓存变量
     */
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache threadCache) {
        // 尝试从「PoolChunkList」链表中分配（寻找现有的「PoolChunk」进行内存分配）
        if (q050.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q025.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q000.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            qInit.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q075.allocate(buf, reqCapacity, sizeIdx, threadCache)) {
            // 分配成功，直接返回
            return;
        }

        // 新建一个「PoolChunk」对象
        PoolChunk<T> c = newChunk(pageSize, nPSizes, pageShifts, chunkSize);
        // 使用新的「PoolChunk」完成内存分配
        boolean success = c.allocate(buf, reqCapacity, sizeIdx, threadCache);
        assert success;
        // 根据最低的添加到「PoolChunkList」节点中
        qInit.add(c);
    }

    private void incSmallAllocation() {
        allocationsSmall.increment();
    }

    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    /**
     * 当调用 ByteBuf#release() 会让引用计数 -1，当引用计数为 0 时就意味着该 ByteBuf
     * 对象需要被回收，ByteBuf 对象进入对象池，ByteBuf 对象所管理的内存块进行内存池。但
     * 是 PoolThreadCache 内存内存块进入内存池之前截胡了，把待回收内存块放入本地线程缓
     * 存中，待后续本线程申请时使用。
     *
     * @param chunk
     * @param nioBuffer
     * @param handle
     * @param normCapacity
     * @param cache
     */
    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        if (chunk.unpooled) {
            int size = chunk.chunkSize();
            destroyChunk(chunk);
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
            SizeClass sizeClass = sizeClass(handle);
            // 先让本地线程缓存尝试回收
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                // 如果被缓存截胡了就直接返回，不执行真正的释放。
                return;
            }

            freeChunk(chunk, handle, normCapacity, sizeClass, nioBuffer, false);
        }
    }

    private static SizeClass sizeClass(long handle) {
        return isSubpage(handle) ? SizeClass.Small : SizeClass.Normal;
    }

    void freeChunk(PoolChunk<T> chunk, long handle, int normCapacity, SizeClass sizeClass, ByteBuffer nioBuffer,
                   boolean finalizer) {
        final boolean destroyChunk;
        synchronized (this) {
            // We only call this if freeChunk is not called because of the PoolThreadCache finalizer as otherwise this
            // may fail due lazy class-loading in for example tomcat.
            if (!finalizer) {
                switch (sizeClass) {
                    case Normal:
                        ++deallocationsNormal;
                        break;
                    case Small:
                        ++deallocationsSmall;
                        break;
                    default:
                        throw new Error();
                }
            }
            destroyChunk = !chunk.parent.free(chunk, handle, normCapacity, nioBuffer);
        }
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    PoolSubpage<T> findSubpagePoolHead(int sizeIdx) {
        return smallSubpagePools[sizeIdx];
    }

    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        assert newCapacity >= 0 && newCapacity <= buf.maxCapacity();

        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        PoolChunk<T> oldChunk = buf.chunk;
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;

        // This does not touch buf's reader/writer indices
        allocate(parent.threadCache(), buf, newCapacity);
        int bytesToCopy;
        if (newCapacity > oldCapacity) {
            bytesToCopy = oldCapacity;
        } else {
            buf.trimIndicesToCapacity(newCapacity);
            bytesToCopy = newCapacity;
        }
        memoryCopy(oldMemory, oldOffset, buf, bytesToCopy);
        if (freeOldMemory) {
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return 0;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return Collections.emptyList();
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return 0;
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public long numTinyDeallocations() {
        return 0;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return 0;
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    /**
     * Return the number of bytes that are currently pinned to buffer instances, by the arena. The pinned memory is not
     * accessible for use by any other allocation, until the buffers using have all been released.
     */
    public long numPinnedBytes() {
        long val = activeBytesHuge.value(); // Huge chunks are exact-sized for the buffers they were allocated to.
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += ((PoolChunk<?>) m).pinnedBytes();
                }
            }
        }
        return max(0, val);
    }

    /**
     * 返回一个池化的 PoolChunk 对象
     * @param pageSize 页大小
     * @param maxPageIdx
     * @param pageShifts 看 {@link PoolChunk#pageShifts}
     * @param chunkSize PoolChunk 的大小
     * @return
     */
    protected abstract PoolChunk<T> newChunk(int pageSize, int maxPageIdx, int pageShifts, int chunkSize);
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, PooledByteBuf<T> dst, int length);
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (;;) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int pageShifts,
                  int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, pageShifts, chunkSize,
                  directMemoryCacheAlignment);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxPageIdx, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(
                    this, null, newByteArray(chunkSize), pageSize, pageShifts, chunkSize, maxPageIdx);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, null, newByteArray(capacity), capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, PooledByteBuf<byte[]> dst, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst.memory, dst.offset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int pageShifts,
                    int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, pageShifts, chunkSize,
                  directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxPageIdx,
            int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                ByteBuffer memory = allocateDirect(chunkSize);
                return new PoolChunk<ByteBuffer>(this, memory, memory, pageSize, pageShifts,
                        chunkSize, maxPageIdx);
            }

            final ByteBuffer base = allocateDirect(chunkSize + directMemoryCacheAlignment);
            final ByteBuffer memory = PlatformDependent.alignDirectBuffer(base, directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, base, memory, pageSize,
                    pageShifts, chunkSize, maxPageIdx);
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                ByteBuffer memory = allocateDirect(capacity);
                return new PoolChunk<ByteBuffer>(this, memory, memory, capacity);
            }

            final ByteBuffer base = allocateDirect(capacity + directMemoryCacheAlignment);
            final ByteBuffer memory = PlatformDependent.alignDirectBuffer(base, directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, base, memory, capacity);
        }

        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner((ByteBuffer) chunk.base);
            } else {
                PlatformDependent.freeDirectBuffer((ByteBuffer) chunk.base);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, PooledByteBuf<ByteBuffer> dstBuf, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dstBuf.memory) + dstBuf.offset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                ByteBuffer dst = dstBuf.internalNioBuffer();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstBuf.offset);
                dst.put(src);
            }
        }
    }
}
