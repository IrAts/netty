/*
 * Copyright 2013 The Netty Project
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

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * 在Stack中有一个WeakOrderQueue链表，这个链表的每一项与一个异线程相关联，用来存储由本线程生产的逃逸到异线程的待回收对象。
 *
 * A线程通过「Recycler」生产出了一个可回收对象 obj，obj 内部将持有 A 线程的「Stack」。
 * 如果 obj 从A线程逃逸到B线程，当B线程使用完了 obj 后，通过 {@link Recycler#recycle(Object, Handle)}
 * 来回收 obj 对象。根据谁生产谁回收的原则，obj 对象应该由A线程回收。那么当B线程调用
 * {@link Recycler#recycle(Object, Handle)} 方法时，该方法会获取到 obj 持有的A线程
 * 的「Stack」。然后将 obj push 到「Stack」中。push 方法会校验当前调用该方法的线程是否
 * 是A线程，如果是的话就执行 pushNow()，否则执行 pushLater()。pushLater() 方法会将该
 * obj 对象放入到该「Stack」中对应B线程的「WeakOrderQueue」。
 *
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    // 全局唯一ID，会有两个地方使用到，一个是每个 Recycler 初始化 OWN_THREAD_ID，另一个是每个WeakOrderQueue初始化id
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    // 全局ID生成器
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    // 每线程最大可缓存对象容量大小，默认值:4096
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    // 每线程最大可缓存对象大小，默认值:4096
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    // 初始容量，默认值:256
    private static final int INITIAL_CAPACITY;
    // 最大共享容量因子，默认值: 2
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    // 每线程最多延迟回收队列，默认值: 8
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    // Link用来存储异线程回收的对象，内部有一个数组，数组长度=LINK_CAPACITY，默认值: 16
    private static final int LINK_CAPACITY;
    // 异线程丢弃对象比例，默认值:8。表示在异线程中，每8个回收对象只回收其中一个，其余丢弃。
    private static final int RATIO;
    private static final int DELAYED_QUEUE_RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));
        DELAYED_QUEUE_RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.delayedQueue.ratio", RATIO));

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: {}", DELAYED_QUEUE_RATIO);
            }
        }
    }

    // 每个线程所持有的 State<T> 数据结构内的数组的最大长度
    private final int maxCapacityPerThread;
    // 共享容量因子。该值越大，在非本线程之外的待回收对象总数越小。
    // 因为非本线程之外的待回收对象总数 = maxCapacityPerThread/maxSharedCapacityFactor
    private final int maxSharedCapacityFactor;
    // 对于从未回收过的对象，Netty选择按一定比例（当）抛弃，避免池内缓存对象速度增长过快，从而影响主线程业务功能。默认值: 8
    private final int interval;
    // 每线程对象池最多可缓存多少实例对象
    private final int maxDelayedQueuesPerThread;
    private final int delayedQueueInterval;

    // 每个线程有对应的「Stack」
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    interval, maxDelayedQueuesPerThread, delayedQueueInterval);
        }

        // 移除后回调方法
        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, ratio, maxDelayedQueuesPerThread,
                DELAYED_QUEUE_RATIO);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread, int delayedQueueRatio) {
        interval = max(0, ratio);
        delayedQueueInterval = max(0, delayedQueueRatio);
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    /**
     * 从对象池获取池化对象
     */
    @SuppressWarnings("unchecked")
    public final T get() {
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        // #1 获取当前线程缓存的「Stack」对象，
        Stack<T> stack = threadLocal.get();
        // #2 从Stack中弹出一个DefaultHandle对象
        DefaultHandle<T> handle = stack.pop();
        // #3 如果弹出的对象为空，表明内部没有缓存好的对象，
        // 需要创建新的handle以及新的Object()
        // newObject(Handler): 这个方法是抽象方法，需要用户自定义实现
        if (handle == null) {
            handle = stack.newHandle();
            // Handle和对象之间互相持有对方的引用
            // 更重要的是Handle对象持有Stack引用，因此在进行回收时就可以把对象直接push到Stack里
            handle.value = newObject(handle);
        }
        // #5 返回
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> extends ObjectPool.Handle<T>  { }

    /**
     * 在图的右下角，它是 Recycler 对回收对象的包装类，Recycler 底层操作的对象是 DefaultHandle，
     * 而非直接的回收的对象。它实现 Handle 接口，里面包含 recycle(Object obj) 回收方法。
     *
     * DefaultHandler 包装待回收对象，同时也添加了部分信息。比如用于重复回收检测
     * 的 recycledId 和 lastRecycleId。lastRecycleId 用来存储最后一次回收对象
     * 的RecycleId，recycledId 用来存 Recycler ID，这个值是通过 Recycler 全局
     * 变量 ID_GENERATOR 创建，每当回收一个 DefaultHandle 对象（该对象内部包装
     * 我们真正的回收对象），都会从 Recycler 对象中获得唯一 ID 值，一开始 recycledId
     * 和 lastRecycledId 相等。后续通过判断两个值是否相等从而得出是否存在重复回收
     * 对象的现象并抛出异常。
     */
    @SuppressWarnings("unchecked")
    private static final class DefaultHandle<T> implements Handle<T> {
        private static final AtomicIntegerFieldUpdater<DefaultHandle<?>> LAST_RECYCLED_ID_UPDATER;
        static {
            AtomicIntegerFieldUpdater<?> updater = AtomicIntegerFieldUpdater.newUpdater(
                    DefaultHandle.class, "lastRecycledId");
            LAST_RECYCLED_ID_UPDATER = (AtomicIntegerFieldUpdater<DefaultHandle<?>>) updater;
        }

        // 上次回收此Handle的RecycleId
        // 当一个可回收对象被从Stack pop出去的时候，该值都会被设置为0。不管这个对象是重用的还是新创建的。
        // 当一个可回收对象被 push 到 Stack 的时候：
        // 如果进行该操作的线程是该可回收对象的拥有者线程，则该值会被设置为该线程的id（实质上并非id，而是与该线程关联的全局唯一值）。
        // 如果进行该操作的线程不是该可回收对象的拥有者线程（也就是异线程），则设置为异线程的id（实质上并非id，而是与该线程关联的全局唯一值）。
        volatile int lastRecycledId;
        // 创建此 Handle 的 RecycleId。和 lastRecycledId 配合使用进行重复回收检测
        // 当一个可回收对象被从 Stack pop 出去的时候，该值都会被设置为0。不管这个对象是重用的还是新创建的。
        // 当一个可回收对象被 push 到 Stack 的时候：
        // 如果进行该操作的线程是该可回收对象的拥有者线程，则该值会被设置为该线程的id（实质上并非id，而是与该线程关联的全局唯一值）。
        // 如果进行该操作的线程不是该可回收对象的拥有者线程（也就是异线程），则设置为异线程的id（实质上并非id，而是与该线程关联的全局唯一值）。
        int recycleId;
        // 该对象是否被回收过
        boolean hasBeenRecycled;
        // 创建「DefaultHandle」的Stack对象
        Stack<?> stack;
        // 待回收对象
        Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        /**
         * 回收此「Handle」所持有的对象「value」
         * 如果对象不相等，抛出异常
         */
        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            // 这里获取到的是创建这个「DefaultHandle」的线程的Stack。
            // 有可能是A线程生产了这个「DefaultHandle」，然后这个「DefaultHandle」在用户代码逃逸溢出到B线程。
            // B线程调用了这个recycle方法企图回收这和个A线程生产的「DefaultHandle」。
            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }
            // 将回收对象入栈，将自己会给Stack，剩下的交给Stack就好了
            stack.push(this);
        }

        public boolean compareAndSetLastRecycledId(int expectLastRecycledId, int updateLastRecycledId) {
            // Use "weak…" because we do not need synchronize-with ordering, only atomicity.
            // Also, spurious failures are fine, since no code should rely on recycling for correctness.
            return LAST_RECYCLED_ID_UPDATER.weakCompareAndSet(this, expectLastRecycledId, updateLastRecycledId);
        }
    }

    // 用于异线程回收，每个线程保存其他线程的「WeakOrderQueue」
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        // 为每个线程初始化一个「WeakHashMap」对象，保证在没有强引用的情况下能回收对象
        // key=>Stack，
        // value=>WeakOrderQueue，
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    /**
     * a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
     * but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
     *
     * WeakOrderQueue 用于存储异线程回收本线程所分配的对象。比如对象 A 是由线程 Thread_1 分配，但是在 Thread_2 回收，
     * 本着谁分配谁回收的原则，Thread_2 是不能回收对象的，所以 Thread_2 会把对象放入对应的 WeakOrderQueue 链表中，这
     * 个链表是由 Thread_2 创建，那怎么与 Thread_1 关联呢? 这里就有一个技巧了，异线程会维护一个 Map<Stack<?> WeakOrderQueue>
     * 本地线程缓存，Thread_2 会根据 Stack（因为内部是使用 DefaultHandler 包装回收对象，而这个 DefaultHandler 也持
     * 有创建它的 Stack 引用）获取对应的 WeakOrderQueue，如果没有，则新建并更新 Stack 的 Head 节点（加锁）。这样就建
     * 立了Thread_1 和 Thread_2 关于对象 A 之间的关联，后续 Thread_1 就可以从 WeakOrderQueue 中回收对象了。
     *
     * WeakOrderQueue 继承 WeakReference，当所属线程被回收时，相应的 WeakOrderQueue 也会被回收。
     * 内部通过 Link 对象构成链表结构，Link 内部维护一个 DefaultHandle[] 数组用来暂存异线程回收对
     * 象。添加时会判断是否会超出设置的阈值（默认值: 16），没有则添加成功，否则创建一个新的 Link 节
     * 点并添加回收对象，接着更新链表结构，让 tail 指针指向新建的 Link 对象。由于线程不止一个，所以
     * 对应的 WeakOrderQueue 也会有多个，WeakOrderQueue 之间则构成链表结构。变量 interval 作用
     * 是回收"限流"，它从一开始就限制回收速率，每经过8 个对象才会回收一个，其余则丢弃。
     *
     */
    private static final class WeakOrderQueue extends WeakReference<Thread> {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        /**
         * Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
         * LINK 节点继承「AtomicInteger」，内部还有一个「readIndex」指针
         */
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            int readIndex;
            Link next;
        }

        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        private static final class Head {
            private final AtomicInteger availableSharedCapacity;

            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /**
             * Reclaim all used space and also unlink the nodes to prevent GC nepotism.
             */
            void reclaimAllSpaceAndUnlink() {
                Link head = link;
                link = null;
                int reclaimSpace = 0;
                while (head != null) {
                    reclaimSpace += LINK_CAPACITY;
                    Link next = head.next;
                    // Unlink to help GC and guard against GC nepotism.
                    head.next = null;
                    head = next;
                }
                if (reclaimSpace > 0) {
                    reclaimSpace(reclaimSpace);
                }
            }

            private void reclaimSpace(int space) {
                availableSharedCapacity.addAndGet(space);
            }

            void relink(Link link) {
                reclaimSpace(LINK_CAPACITY);
                this.link = link;
            }

            /**
             * Creates a new {@link} and returns it if we can reserve enough space for it, otherwise it
             * returns {@code null}.
             */
            Link newLink() {
                return reserveSpaceForLink(availableSharedCapacity) ? new Link() : null;
            }

            static boolean reserveSpaceForLink(AtomicInteger availableSharedCapacity) {
                for (;;) {
                    int available = availableSharedCapacity.get();
                    if (available < LINK_CAPACITY) {
                        return false;
                    }
                    if (availableSharedCapacity.compareAndSet(available, available - LINK_CAPACITY)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        // Head节点管理「Link」对象的创建。内部next指向下一个「Link」节点，构成链表结构
        private final Head head;
        // 数据存储节点
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        // 指向其他异线程的 WorkOrderQueue 链表
        private WeakOrderQueue next;
        // 唯一ID
        private final int id = ID_GENERATOR.getAndIncrement();
        // 可以理解为对回收动作限流。默认值: 8
        // 并非到阻塞时才限流，而是一开始就这样做
        private final int interval;
        // 已丢弃回收对象数量
        private int handleRecycleCount;

        private WeakOrderQueue() {
            super(null);
            head = new Head(null);
            interval = 0;
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            super(thread);
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            interval = stack.delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            if (!Head.reserveSpaceForLink(stack.availableSharedCapacity)) {
                return null;
            }
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            stack.setHead(queue);

            return queue;
        }

        WeakOrderQueue getNext() {
            return next;
        }

        void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        void reclaimAllSpaceAndUnlink() {
            head.reclaimAllSpaceAndUnlink();
            next = null;
        }

        void add(DefaultHandle<?> handle) {
            if (!handle.compareAndSetLastRecycledId(0, id)) {
                // Separate threads could be racing to add the handle to each their own WeakOrderQueue.
                // We only add the handle to the queue if we win the race and observe that lastRecycledId is zero.
                return;
            }

            // While we also enforce the recycling ratio when we transfer objects from the WeakOrderQueue to the Stack
            // we better should enforce it as well early. Missing to do so may let the WeakOrderQueue grow very fast
            // without control
            if (!handle.hasBeenRecycled) {
                if (handleRecycleCount < interval) {
                    handleRecycleCount++;
                    // Drop the item to prevent from recycling too aggressively.
                    return;
                }
                handleRecycleCount = 0;
            }

            Link tail = this.tail;
            int writeIndex;
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                Link link = head.newLink();
                if (link == null) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = link;

                writeIndex = tail.get();
            }
            tail.elements[writeIndex] = handle;
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        /**
         * transfer as many items as we can from this queue to the stack, returning true if any were transferred.
         *
         * 把「WeakOrderQueue」内存缓存的待回收对象转移到「Stack」数组中。
         * 注意：调用这个方法的线程必然是Stack所归属的线程，所以不需要加锁
         * 一次性转移一个Link节点（长度: 16）
         */
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            // 获取链表的头结点
            Link head = this.head.link;
            // 头结点为空，表示没有数据，直接返回
            if (head == null) {
                return false;
            }

            // LINK_CAPACITY表示一个Link节点的容量大小，默认值: 16
            // 判断读指针「readIndex」是否到达边界了，如果到达边界且指向下一个节点为NULL则表示没有数据，直接返回
            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                // 更新头结点，指向next
                head = head.next;
                this.head.relink(head);
            }

            // 「Head」对象有两个指针变量，分别是「readIndex」和 「AtomicInteger」
            // srcStart: 表示可读起点
            final int srcStart = head.readIndex;
            // head:表示自从创建开始已经添加个数
            int srcEnd = head.get();
            // 两者相减，表示当前可回收对象数量
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            // 计算迁移回收对象后Stack容量的期望值
            final int dstSize = dst.size;
            final int expectedCapacity = dstSize + srcSize;

            // 超出「Stack」数组长度需要扩容
            if (expectedCapacity > dst.elements.length) {
                // 对「Stack」数组扩容，实际扩容规则按「Stack」规则计算的，所以扩容后的实际容量
                // 不一定与期望容量相等，有可能不扩容
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                // 根据「Stack」实际容量最终确定回收终点索引
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                // 循环遍历并复制
                final DefaultHandle[] srcElems = head.elements;
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle<?> element = srcElems[i];
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    // srcElems[i] 需要被迁移到本线程的Stack中，所以需要从异线程的「WeakOrderQueue」中断开引用
                    srcElems[i] = null;

                    // 本次回收需要按照一定比例丢弃对象，并不是全盘接收
                    if (dst.dropHandle(element)) {
                        // 丢弃
                        continue;
                    }
                    // 不需要丢弃，复制回收对象到Stack目标数组中
                    element.stack = dst;
                    dstElems[newDstSize ++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    // 当前的Link的对象已经跑了一遍回收，且所指向的下一个节点不为NULL
                    // 更新head指向下一个Link节点，当前的Link需要被GC
                    this.head.relink(head.next);
                }

                // 更新读节点指针
                head.readIndex = srcEnd;
                // 再次判断目标数组是否写入新数据
                if (dst.size == newDstSize) {
                    // 什么都没有回收，返回false
                    return false;
                }
                // 更新目标Stack的已使用容量大小的值
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }
    }

    /**
     * Stack 中持有用来存储数据的 DefaultHandler[] 数组。
     * 内部定义了与异线程回收相关的 WeakOrderQueue，还有一些安全变量和信息变量。
     * Stack 对象是从 Recycle  内部的 FastThreadLocal 对象中获得，因此每个线程拥有属于自己的 Stack 对象，
     * 创造了无锁的环境，并通过 weakOrderQueue 与其他线程建立沟通的桥梁。
     */
    private static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        // Stack是被哪个「Recycler」创建的
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        // 我们将线程存储在WeakReference中，否则我们可能是唯一一个在线程死后仍然
        // 持有对线程本身的强引用的线程，因为DefaultHandle将持有对Stack的引用。
        // 最大的问题是，如果我们不使用WeakReference，如果用户将DefaultHandle的引用存储在
        // 某个地方，并且从来没有清除这个引用(或没有及时清除它)，线程可能根本无法被收集。
        final WeakReference<Thread> threadRef;
        // 该「Stack」所对应其他线程剩余可缓存对象实例个数（就是其他线程此时可缓存对象数是多少）
        // 默认值: 2048
        final AtomicInteger availableSharedCapacity;
        // 一个线程可同时缓存多少个「Stack」对象。这个「Stack」可以理解为其他线程的「Stack」
        // 毕竟不可能缓存所有的「Stack」吧，所以需要做一点限制
        // 默认值: 8
        private final int maxDelayedQueues;
        // 数组最大容量。默认值: 4096
        private final int maxCapacity;
        // 可以理解为对回收动作限流。默认值: 8
        // 并非到阻塞时才限流，而是一开始就这样做
        private final int interval;
        private final int delayedQueueInterval;
        // 存储缓存数据的数组。默认值: 256
        DefaultHandle<?>[] elements;
        // 数组中非空元素数量。默认值: 0
        int size;
        // 跳过回收对象的数量。从0开始计数，每跳过一个回收对象+1。
        // 当 handleRecycleCount > interval 时重置 handleRecycleCount = 0
        // 默认值: 8。初始值和「interval」，以便第一个元素能被回收
        private int handleRecycleCount;
        // 与异线程回收的相关三个节点。
        // 这里的组成的「WeakOrderQueue」链表里是不会存放当前线程产生的可回收对象的。
        // 只会存放从其他线程获取过来的可回收对象（当然，只有当前线程觉得这个对象没用了的时候才放进去）。
        // 故 head 节点是直到第一次释放从其他线程获取过来的可回收对象是才会变为非 null。
        private WeakOrderQueue cursor, prev;
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int interval, int maxDelayedQueues, int delayedQueueInterval) {
            this.parent = parent;
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.interval = interval;
            this.delayedQueueInterval = delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        /**
         * 只有拥有这个Stack的线程(即当前Stack所归属的线程)才能调用这个方法。
         * 从栈中弹出一个「DefaultHandler」对象
         * ① 如果size>0，则弹出elements[]数组中的元素
         * ② 如果size=0，尝试从其他线程(异线程)缓存对象中窃取部分缓存对象
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            // 当前缓存缓存的对象数为 0
            // 尝试从异线程相关的「WeakOrderQueue」队列中获取可回收对象
            if (size == 0) {
                // scavenge 会到异线程(其他线程)中扫描是否有可回收对象
                // 有的话就移动到本线程的「WeakOrderQueue」队列中并返回true
                if (!scavenge()) {
                    // 异线程也没有可回收对象，返回null
                    return null;
                }
                size = this.size;
                if (size <= 0) {
                    // double check, avoid races
                    return null;
                }
            }
            // 栈中有缓存对象
            size --;
            // 内部使用数组模拟栈
            DefaultHandle ret = elements[size];
            elements[size] = null;
            // As we already set the element[size] to null we also need to store the updated size before we do
            // any validation. Otherwise we may see a null value when later try to pop again without a new element
            // added before.
            // 更新size
            this.size = size;

            // 重复回收校验，如果回收ID不相等，表示不是同一个
            if (ret.lastRecycledId != ret.recycleId) {
                // 如果上一次对象回收ID和这次的不一样，抛出异常
                throw new IllegalStateException("recycled multiple times");
            }
            // 重置「DefaultHandler」回收ID信息
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            return ret;
        }

        /**
         * 只有拥有这个Stack的线程(即当前Stack所归属的线程)才能调用这个方法。
         * scavenge有清扫的意思，这个方法用于从「WeakOrderQueue」中回收部分对象。
         * 会调用 Stack#scavengeSome() 尝试从异线程相关的 WeakOrderQueue 链表
         * 中回收部分对象，如果回收成功，则返回 true，回收失败，重置 curson 指针，
         * 重新回到头结点。
         */
        private boolean scavenge() {
            // continue an existing scavenge, if any
            // 尝试从「WeakOrderQueue」中获取部分待回收对象并转换到「Stack#DefaultHandler[]」数组中
            if (scavengeSome()) {
                // 转移成功
                return true;
            }

            // reset our scavenge cursor
            // 获取失败，重置prev、cursor指针
            prev = null;
            cursor = head;
            return false;
        }

        /**
         * 只有拥有这个Stack的线程(即当前Stack所归属的线程)才能调用这个方法。
         */
        private boolean scavengeSome() {
            WeakOrderQueue prev;
            // 当前cursor指针为空
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                prev = null;
                // 指向头结点
                cursor = head;
                if (cursor == null) {
                    // 头结点为空，表明没有任何逃逸到异线程的可回收对象被异线程调用回收方法。
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            // 存在异线程「WeakOrderQueue」，准备回收对象
            do {
                // 尝试从异线程「WeakOrderQueue」尽可能多地转移回收对象。但并不会更新指针
                // 当转移对象数量>0，就返回true
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                // 保存下一个「WeakOrderQueue」的引用，因为本次的「WeakOrderQueue」可能会被回收
                WeakOrderQueue next = cursor.getNext();
                // 如果与队列相关的线程终结了（cursor.get()==null），
                // 需要判断是否还有可回收对象，若有转移后解除它的连接
                // 我们永远不会断开第一个连接，因为不想在更新头部时进行同步
                if (cursor.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    if (cursor.hasFinalData()) {
                        // 按一定比例转移对象
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        // 确保在删除需要被GC的 WeakOrderQueue 之前收回所有空间
                        // Ensure we reclaim all space before dropping the WeakOrderQueue to be GC'ed.
                        cursor.reclaimAllSpaceAndUnlink();
                        // 更新节点信息，删除cursor节点，
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }
                // 更新cursor指针，指向「WeakOrderQueue」下一个对象
                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        /**
         * 根据调用该方法的线程选择不同回收策略。
         * 如果是 Stack.this 的拥有者线程直接回收即可，
         * 如果是异线程回收需要写入 WeakOrderQueue 链表。
         */
        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                // 本线程回收，直接写入内存池（也就是入栈）
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                // 异线程回收对象，推迟回收
                pushLater(item, currentThread);
            }
        }

        private void pushNow(DefaultHandle<?> item) {
            if (item.recycleId != 0 || !item.compareAndSetLastRecycledId(0, OWN_THREAD_ID)) {
                throw new IllegalStateException("recycled already");
            }
            item.recycleId = OWN_THREAD_ID;

            int size = this.size;
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            elements[size] = item;
            this.size = size + 1;
        }

        /**
         * 执行这个方法的线程必然不是当前 Stack.this 所归属的线程。
         * 当前执行环境中：
         *      this 是生产出 item 的线程的 Stack。
         *      thread 是企图回收 item 的线程。
         * 注意：这个 thread 一定不是生产出这个 item 的线程（这是因为这个方法本来就是处理异线程回收对象的）。
         *
         *
         * 将回收对象写入异线程关联的「WeakOrderQueue」链表中。
         * 在新建「WeakOrderQueue」的过程中有两道门槛会导致创建失败:
         * 	 ① WeakOrderQueue数量超出了maxDelayedQueues限制，创建失败
         * 	 ② 向目标对象「Stack」申请长度为16的容量时失败
         * 将新创建的对象放入Map数组以供下次使用，
         * 且将item写入「WeakOrderQueue」
         */
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            if (maxDelayedQueues == 0) {
                // We don't support recycling across threads and should just drop the item on the floor.
                // 不支持异线程回收，直接丢弃
                return;
            }

            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            // 使用「FastThreadLocal」本地线程缓存Stack与WeakOrderQueue的关系
            // 因为一个线程会对应多个Stack（即对应多个不同类型的对象池）
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {
                // 当前线程没有与之关联的「WeakOrderQueue」链表，需要新创建一个
                // 但是创建之前判断是否已经满额了
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    // 满额，添加一个占位对象，表示以后回收这类对象直接丢弃
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                // Stack对象可用的availableSharedCapacity < LINK_CAPACITY，表示连一个LINK节点空间不够了
                // 这种情况会导致返回NULL。
                // 如果对象不为NULl，表明创建成功，并更新「Stack」的head指针指向新的「WeakOrderQueue」
                // 新创建出来的 queue 都回去当头节点。设置头结点的操作室 synchronize 的。
                if ((queue = newWeakOrderQueue(thread)) == null) {
                    // 直接丢弃
                    return;
                }
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            // 将回收对象添加到 WeakOrderQueue 队列中
            queue.add(item);
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         * 创建一个全新的 WeakOrderQueue 并注册到对应的 Stack#head 头结点中。
         */
        private WeakOrderQueue newWeakOrderQueue(Thread thread) {
            return WeakOrderQueue.newQueue(this, thread);
        }

        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {
                if (handleRecycleCount < interval) {
                    handleRecycleCount++;
                    // Drop the object.
                    return true;
                }
                handleRecycleCount = 0;
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
