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


import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import io.netty.buffer.PoolArena.SizeClass;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.ObjectPool.Handle;
import io.netty.util.internal.ObjectPool.ObjectCreator;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Acts a Thread cache for allocations. This implementation is moduled after
 * <a href="https://people.freebsd.org/~jasone/jemalloc/bsdcan2006/jemalloc.pdf">jemalloc</a> and the descripted
 * technics of
 * <a href="https://www.facebook.com/notes/facebook-engineering/scalable-memory-allocation-using-jemalloc/480222803919">
 * Scalable memory allocation using jemalloc</a>.
 */
final class PoolThreadCache {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PoolThreadCache.class);
    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    /**
     * heapArena 和 directArena 是在 PoolThreadLocalCache#initialValue() 初始化的
     */
    final PoolArena<ByteBuffer> directArena;
    /**
     * heapArena 和 directArena 是在 PoolThreadLocalCache#initialValue() 初始化的
     */
    final PoolArena<byte[]> heapArena;

    // Hold the caches for the different size classes, which are tiny, small and normal.
    /**
     * 堆内存的 small 级别内存缓存
     */
    private final MemoryRegionCache<byte[]>[] smallSubPageHeapCaches;
    /**
     * 直接内存的 small 级别内存缓存
     */
    private final MemoryRegionCache<ByteBuffer>[] smallSubPageDirectCaches;
    /**
     * 堆内存的 normal 级别内存缓存
     */
    private final MemoryRegionCache<byte[]>[] normalHeapCaches;
    /**
     * 直接内存的 normal 级别内存缓存
     */
    private final MemoryRegionCache<ByteBuffer>[] normalDirectCaches;

    // 触发释放部分内存块阈值
    private final int freeSweepAllocationThreshold;
    // 当前「PoolThreadCache」是否需要被释放
    private final AtomicBoolean freed = new AtomicBoolean();

    // 从本地线程缓存中分配的次数
    // 当超过freeSweepAllocationThreshold时会重置为0
    private int allocations;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolThreadCache(PoolArena<byte[]> heapArena, PoolArena<ByteBuffer> directArena,
                    int smallCacheSize, int normalCacheSize, int maxCachedBufferCapacity,
                    int freeSweepAllocationThreshold) {
        checkPositiveOrZero(maxCachedBufferCapacity, "maxCachedBufferCapacity");
        this.freeSweepAllocationThreshold = freeSweepAllocationThreshold;
        this.heapArena = heapArena;
        this.directArena = directArena;
        if (directArena != null) {
            smallSubPageDirectCaches = createSubPageCaches(
                    smallCacheSize, directArena.numSmallSubpagePools);

            normalDirectCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, directArena);

            directArena.numThreadCaches.getAndIncrement();
        } else {
            // No directArea is configured so just null out all caches
            smallSubPageDirectCaches = null;
            normalDirectCaches = null;
        }
        if (heapArena != null) {
            // Create the caches for the heap allocations
            smallSubPageHeapCaches = createSubPageCaches(
                    smallCacheSize, heapArena.numSmallSubpagePools);

            normalHeapCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, heapArena);

            heapArena.numThreadCaches.getAndIncrement();
        } else {
            // No heapArea is configured so just null out all caches
            smallSubPageHeapCaches = null;
            normalHeapCaches = null;
        }

        // Only check if there are caches in use.
        if ((smallSubPageDirectCaches != null || normalDirectCaches != null
                || smallSubPageHeapCaches != null || normalHeapCaches != null)
                && freeSweepAllocationThreshold < 1) {
            throw new IllegalArgumentException("freeSweepAllocationThreshold: "
                    + freeSweepAllocationThreshold + " (expected: > 0)");
        }
    }

    private static <T> MemoryRegionCache<T>[] createSubPageCaches(
            int cacheSize, int numCaches) {
        if (cacheSize > 0 && numCaches > 0) {
            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[numCaches];
            for (int i = 0; i < cache.length; i++) {
                // TODO: maybe use cacheSize / cache.length
                cache[i] = new SubPageMemoryRegionCache<T>(cacheSize);
            }
            return cache;
        } else {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> MemoryRegionCache<T>[] createNormalCaches(
            int cacheSize, int maxCachedBufferCapacity, PoolArena<T> area) {
        if (cacheSize > 0 && maxCachedBufferCapacity > 0) {
            int max = Math.min(area.chunkSize, maxCachedBufferCapacity);
            // Create as many normal caches as we support based on how many sizeIdx we have and what the upper
            // bound is that we want to cache in general.
            List<MemoryRegionCache<T>> cache = new ArrayList<MemoryRegionCache<T>>() ;
            for (int idx = area.numSmallSubpagePools; idx < area.nSizes && area.sizeIdx2size(idx) <= max ; idx++) {
                cache.add(new NormalMemoryRegionCache<T>(cacheSize));
            }
            return cache.toArray(new MemoryRegionCache[0]);
        } else {
            return null;
        }
    }

    // val > 0
    static int log2(int val) {
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateSmall(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int sizeIdx) {
        return allocate(cacheForSmall(area, sizeIdx), buf, reqCapacity);
    }

    /**
     * Try to allocate a normal buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateNormal(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int sizeIdx) {
        return allocate(cacheForNormal(area, sizeIdx), buf, reqCapacity);
    }

    /**
     * 这个方法比较简单，就是委托 MemoryRegionCache 对象分配内存。然后再判断当前分配总
     * 数是否超过阈值，如果超过了需要对所有的 MemoryRegionCache 数组进行清理工作。
     *
     * @param cache
     * @param buf
     * @param reqCapacity
     * @return
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private boolean allocate(MemoryRegionCache<?> cache, PooledByteBuf buf, int reqCapacity) {
        if (cache == null) {
            // no cache found so just return false here
            return false;
        }
        // 委托「MemoryRegionCache」分配内存
        boolean allocated = cache.allocate(buf, reqCapacity, this);
        // 如果分配次数超过阈值，需要清理「部分」已缓存的内存信息
        if (++ allocations >= freeSweepAllocationThreshold) {
            allocations = 0;
            // 清理
            trim();
        }
        return allocated;
    }

    /**
     * Add {@link PoolChunk} and {@code handle} to the cache if there is enough room.
     * Returns {@code true} if it fit into the cache {@code false} otherwise.
     * 尝试本地缓存回收内存块。
     * PoolThreadCache 是本地缓存缓存变量，属于线程私有。方法 add() 尝试回收内存块，
     * 因为可能回收失败（比如容量超出），这个方法并没有做太多事情，就是根据规格值和规
     * 格类型确定 MemoryRegionCache 对象，如果匹配失败，可能容量超出不允许回收，这
     * 种类型的内存块只能通过 PoolChunk 回收了。
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    boolean add(PoolArena<?> area, PoolChunk chunk, ByteBuffer nioBuffer,
                long handle, int normCapacity, SizeClass sizeClass) {
        int sizeIdx = area.size2SizeIdx(normCapacity);
        // #1 根据规格类型和规格值获取「MemoryRegionCache」对象
        MemoryRegionCache<?> cache = cache(area, sizeIdx, sizeClass);
        // #2 没有适配的「MemoryRegionCache」，无法进行本地缓存回收，直接返回
        if (cache == null) {
            return false;
        }
        // #3 回收缓存
        //    委托 MemoryRegionCache 把内存块信息添加到内部的 Queue 队列中。
        return cache.add(chunk, nioBuffer, handle, normCapacity);
    }

    /**
     * 根据规格类型和规格值获取「MemoryRegionCache」对象
     *
     * @param area
     * @param sizeIdx
     * @param sizeClass
     * @return
     */
    private MemoryRegionCache<?> cache(PoolArena<?> area, int sizeIdx, SizeClass sizeClass) {
        switch (sizeClass) {
        case Normal:
            return cacheForNormal(area, sizeIdx);
        case Small:
            return cacheForSmall(area, sizeIdx);
        default:
            throw new Error();
        }
    }

    /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            free(true);
        }
    }

    /**
     *  Should be called if the Thread that uses this cache is about to exist to release resources out of the cache
     */
    void free(boolean finalizer) {
        // As free() may be called either by the finalizer or by FastThreadLocal.onRemoval(...) we need to ensure
        // we only call this one time.
        if (freed.compareAndSet(false, true)) {
            int numFreed = free(smallSubPageDirectCaches, finalizer) +
                    free(normalDirectCaches, finalizer) +
                    free(smallSubPageHeapCaches, finalizer) +
                    free(normalHeapCaches, finalizer);

            if (numFreed > 0 && logger.isDebugEnabled()) {
                logger.debug("Freed {} thread-local buffer(s) from thread: {}", numFreed,
                        Thread.currentThread().getName());
            }

            if (directArena != null) {
                directArena.numThreadCaches.getAndDecrement();
            }

            if (heapArena != null) {
                heapArena.numThreadCaches.getAndDecrement();
            }
        }
    }

    private static int free(MemoryRegionCache<?>[] caches, boolean finalizer) {
        if (caches == null) {
            return 0;
        }

        int numFreed = 0;
        for (MemoryRegionCache<?> c: caches) {
            numFreed += free(c, finalizer);
        }
        return numFreed;
    }

    private static int free(MemoryRegionCache<?> cache, boolean finalizer) {
        if (cache == null) {
            return 0;
        }
        return cache.free(finalizer);
    }

    /**
     * 释放所有「MemoryRegionCache」数组的部分内存
     *
     * 正所谓有借有还，在借不难嘛，我们应该在适当的时候归还部分内存块，
     * 不能被一个线程独占，毕竟还有其他线程需要内存块嘛。触发释放缓存
     * 中的内存块的时机前面已经提到过，就是当分配次数大于释放阈值（默
     * 认值: 8192）时就进行释放操作。
     */
    void trim() {
        trim(smallSubPageDirectCaches);
        trim(normalDirectCaches);
        trim(smallSubPageHeapCaches);
        trim(normalHeapCaches);
    }

    private static void trim(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return;
        }
        // 遍历数组，挨个清除
        for (MemoryRegionCache<?> c: caches) {
            trim(c);
        }
    }

    private static void trim(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return;
        }
        // 调用MemoryRegionCache完成清理
        cache.trim();
    }

    private MemoryRegionCache<?> cacheForSmall(PoolArena<?> area, int sizeIdx) {
        if (area.isDirect()) {
            return cache(smallSubPageDirectCaches, sizeIdx);
        }
        return cache(smallSubPageHeapCaches, sizeIdx);
    }

    private MemoryRegionCache<?> cacheForNormal(PoolArena<?> area, int sizeIdx) {
        // We need to substract area.numSmallSubpagePools as sizeIdx is the overall index for all sizes.
        int idx = sizeIdx - area.numSmallSubpagePools;
        if (area.isDirect()) {
            return cache(normalDirectCaches, idx);
        }
        return cache(normalHeapCaches, idx);
    }

    private static <T> MemoryRegionCache<T> cache(MemoryRegionCache<T>[] cache, int sizeIdx) {
        if (cache == null || sizeIdx > cache.length - 1) {
            return null;
        }
        return cache[sizeIdx];
    }

    /**
     * Cache used for buffers which are backed by TINY or SMALL size.
     */
    private static final class SubPageMemoryRegionCache<T> extends MemoryRegionCache<T> {
        SubPageMemoryRegionCache(int size) {
            super(size, SizeClass.Small);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity,
                PoolThreadCache threadCache) {
            chunk.initBufWithSubpage(buf, nioBuffer, handle, reqCapacity, threadCache);
        }
    }

    /**
     * Cache used for buffers which are backed by NORMAL size.
     */
    private static final class NormalMemoryRegionCache<T> extends MemoryRegionCache<T> {
        NormalMemoryRegionCache(int size) {
            super(size, SizeClass.Normal);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity,
                PoolThreadCache threadCache) {
            chunk.initBuf(buf, nioBuffer, handle, reqCapacity, threadCache);
        }
    }

    /**
     * MemoryRegionCache 是记录缓存内存信息的核心类。
     *
     * @param <T>
     */
    private abstract static class MemoryRegionCache<T> {
        // 缓存数量
        private final int size;
        // 存放内存信息（利用Entry对象包装）队列。
        // 这个队列有意思，是多生产单消费者队列。
        private final Queue<Entry<T>> queue;
        // 「MemoryRegionCache」管理的内存规格
        private final SizeClass sizeClass;
        // 「MemoryRegionCache」成功分配次数，这个和「PoolThreadCache」是有区别的
        private int allocations;

        MemoryRegionCache(int size, SizeClass sizeClass) {
            this.size = MathUtil.safeFindNextPositivePowerOfTwo(size);
            queue = PlatformDependent.newFixedMpscQueue(this.size);
            this.sizeClass = sizeClass;
        }

        /**
         * Init the {@link PooledByteBuf} using the provided chunk and handle with the capacity restrictions.
         */
        protected abstract void initBuf(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle,
                                        PooledByteBuf<T> buf, int reqCapacity, PoolThreadCache threadCache);

        /**
         * Add to cache if not already full.
         * 把内存块信息添加到内部的 Queue 队列中。添加过程也是十分简洁，使用内
         * 部类 Entry 封装内存块信息，然后入队就完事了。当 Queue#offer() 方
         * 法添加失败时，需要立即回收 Entry 对象，可能会造成内存泄漏。Entry 对
         * 象使用对象池化技术。
         */
        @SuppressWarnings("unchecked")
        public final boolean add(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity) {
            // 创建 Entry 对象，包装内存详情
            Entry<T> entry = newEntry(chunk, nioBuffer, handle, normCapacity);
            // 入队
            boolean queued = queue.offer(entry);
            if (!queued) {
                // 入队失败，立即回收Entry对象，否则会造成内存泄漏。
                entry.recycle();
            }
            // 返回缓存结果
            return queued;
        }

        /**
         * Allocate something out of the cache if possible and remove the entry from the cache.
         * 真正从缓存中进行内存分配的地方，逻辑也十分清楚，从 Queue 弹出一个 Entry 对象，
         * 里面包含了我们需要的内存信息，如果弹出对象为空，那此次分配失败，返回false，如
         * 果有，那就通过 init() 方法初始化 ByteBuf 对象，并把 Entry 对象回收。
         */
        public final boolean allocate(PooledByteBuf<T> buf, int reqCapacity, PoolThreadCache threadCache) {
            // #1 从队列中获取「Entry」对象
            Entry<T> entry = queue.poll();
            // #2 既然没有，那只能返回了
            if (entry == null) {
                return false;
            }
            // #3 还真有缓存，捡到宝了，那就把内存信息写入ByteBuf对象吧
            initBuf(entry.chunk, entry.nioBuffer, entry.handle, buf, reqCapacity, threadCache);
            // #4 把用了的Entry对象也回收吧，放入对象池中
            entry.recycle();

            // allocations is not thread-safe which is fine as this is only called from the same thread all time.
            // #5 分配次数+1，当分配次数超过阈值时就需要清理了
            ++ allocations;
            // #6 分配成功
            return true;
        }

        /**
         * Clear out this cache and free up all previous cached {@link PoolChunk}s and {@code handle}s.
         */
        public final int free(boolean finalizer) {
            return free(Integer.MAX_VALUE, finalizer);
        }

        /**
         * 释放内存，最多可能会回收 max 个对象。
         *
         * @param max
         * @param finalizer
         * @return
         */
        private int free(int max, boolean finalizer) {
            int numFreed = 0;
            // 循环Queue内存
            for (; numFreed < max; numFreed++) {
                // 弹出
                Entry<T> entry = queue.poll();
                if (entry != null) {
                    // 回收
                    freeEntry(entry, finalizer);
                } else {
                    // all cleared
                    return numFreed;
                }
            }
            return numFreed;
        }

        /**
         * Free up cached {@link PoolChunk}s if not allocated frequently enough.
         *
         * trim() 这个方法会在这样的时机被调用:
         * ① 定时任务trimTask，默认不开启，可以通过设置 io.netty.allocation.cacheTrimIntervalMillis开启，时间单位:TimeUnit.MILLISECONDS
         * ② 在分配次数>=freeSweepAllocationThreshold（默认值:8192）时才会触发回收
         *
         */
        public final void trim() {
            // #1 这个free可以这个理解，分配次数少，回收多，分配次数多，回收就少
            int free = size - allocations;
            allocations = 0;

            // We not even allocated all the number that are
            // 当free>0表明队列中存在数据，需要回收free个数的内存
            // 这里并非全部释放队列的中所有内存信息，它有一个最大值free限制
            if (free > 0) {
                free(free, false);
            }
        }

        /**
         * 这个方法会根据是否从 Object#finalizer() 调用来判断是否需要对 Entry 对象回收。
         * 如果为 true，表明此时进行的时线程销毁动作，调用 PoolThreadCache#finalize()
         * 方法会回收所有只与此线程相关的数据，比如 Entry、ObjectPool 等对象，线程销毁这
         * 些对象就会自动销毁了。但是平常的释放动作不同，虽然调用 entry.crecycle() 对象，
         * 假设此时 PoolChunk 对象只有 Entry 这么一个引用指向它，如果不调用这个方法就会
         * 造成 PoolChunk 一直被强引用，无法被回收，从而造成内存泄漏
         *
         * @param entry
         * @param finalizer
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        private  void freeEntry(Entry entry, boolean finalizer) {
            // #1 内存信息
            PoolChunk chunk = entry.chunk;
            long handle = entry.handle;
            ByteBuffer nioBuffer = entry.nioBuffer;

            if (!finalizer) {
                // recycle now so PoolChunk can be GC'ed. This will only be done if this is not freed because of
                // a finalizer.
                // 回收Entry对象，以便后面的PoolChunk对象可以GC。
                // 这不会在Object#finalize()方法中进行这一步操作
                entry.recycle();
            }

            chunk.arena.freeChunk(chunk, handle, entry.normCapacity, sizeClass, nioBuffer, finalizer);
        }

        static final class Entry<T> {
            final Handle<Entry<?>> recyclerHandle;
            PoolChunk<T> chunk;
            ByteBuffer nioBuffer;
            long handle = -1;
            int normCapacity;

            Entry(Handle<Entry<?>> recyclerHandle) {
                this.recyclerHandle = recyclerHandle;
            }

            void recycle() {
                chunk = null;
                nioBuffer = null;
                handle = -1;
                recyclerHandle.recycle(this);
            }
        }

        /**
         * 构造 Entry 对象。
         *
         * @param chunk
         * @param nioBuffer
         * @param handle
         * @param normCapacity
         * @return
         */
        @SuppressWarnings("rawtypes")
        private static Entry newEntry(PoolChunk<?> chunk, ByteBuffer nioBuffer, long handle, int normCapacity) {
            // 从对象缓存池中获取Entry对象
            Entry entry = RECYCLER.get();
            // 封装内存信息
            entry.chunk = chunk;
            entry.nioBuffer = nioBuffer;
            entry.handle = handle;
            entry.normCapacity = normCapacity;
            return entry;
        }

        /**
         * 创建一个对象缓存池
         */
        @SuppressWarnings("rawtypes")
        private static final ObjectPool<Entry> RECYCLER = ObjectPool.newPool(new ObjectCreator<Entry>() {
            @SuppressWarnings("unchecked")
            @Override
            public Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        });
    }
}
