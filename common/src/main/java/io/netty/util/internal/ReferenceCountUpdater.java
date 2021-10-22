/*
 * Copyright 2019 The Netty Project
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
package io.netty.util.internal;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCounted;

/**
 * Common logic for {@link ReferenceCounted} implementations
 *
 * ReferenceCountUpdater 对实现 ReferenceCounted 接口的 ByteBuf 进行引用计数相关的操作。
 * 底层通过魔法类 java.util.concurrent.atomic.AtomicIntegerFieldUpdater 来完成对该值的增/减操作。
 *     - 每一个刚刚出生的 ByteBuf 对象，其 refCnt 的值为 2 。因此，每当引用计数逻辑 +1，
 *       则对应的 refCnt 物理 +2，每当引用计数逻辑 -1，则对应 refCnt 物理 -2。因此，只
 *       要存在引用，内部引用计数值就是偶数。则可以通过 refCnt&1 == 0?  来判断是否持有引用。
 *     - 除了这样判断之外，还有很多地方也可以通过位运算提高性能。虽然不多，但也是极致优化的体现了。
 *     - 公式 realCount = value>>>1 得到计数引用逻辑值。
 *
 */
public abstract class ReferenceCountUpdater<T extends ReferenceCounted> {
    /*
     * Implementation notes:
     *
     * For the updated int field:
     *   Even => "real" refcount is (refCnt >>> 1)
     *   Odd  => "real" refcount is 0
     *
     * (x & y) appears to be surprisingly expensive relative to (x == y). Thus this class uses
     * a fast-path in some places for most common low values when checking for live (even) refcounts,
     * for example: if (rawCnt == 2 || rawCnt == 4 || (rawCnt & 1) == 0) { ...
     */

    protected ReferenceCountUpdater() { }

    public static long getUnsafeOffset(Class<? extends ReferenceCounted> clz, String fieldName) {
        try {
            if (PlatformDependent.hasUnsafe()) {
                return PlatformDependent.objectFieldOffset(clz.getDeclaredField(fieldName));
            }
        } catch (Throwable ignore) {
            // fall-back
        }
        return -1;
    }

    protected abstract AtomicIntegerFieldUpdater<T> updater();

    protected abstract long unsafeOffset();

    public final int initialValue() {
        return 2;
    }

    // 获取真实计数
    private static int realRefCnt(int rawCnt) {
        // (rawCnt & 1) != 0 判断是否为偶数，偶数才会有引用存在
        return rawCnt != 2 && rawCnt != 4 && (rawCnt & 1) != 0 ? 0 : rawCnt >>> 1;
    }

    /**
     * Like {@link #realRefCnt(int)} but throws if refCnt == 0
     */
    private static int toLiveRealRefCnt(int rawCnt, int decrement) {
        if (rawCnt == 2 || rawCnt == 4 || (rawCnt & 1) == 0) {
            return rawCnt >>> 1;
        }
        // odd rawCnt => already deallocated
        throw new IllegalReferenceCountException(0, -decrement);
    }

    private int nonVolatileRawCnt(T instance) {
        // TODO: Once we compile against later versions of Java we can replace the Unsafe usage here by varhandles.
        final long offset = unsafeOffset();
        return offset != -1 ? PlatformDependent.getInt(instance, offset) : updater().get(instance);
    }

    public final int refCnt(T instance) {
        return realRefCnt(updater().get(instance));
    }

    public final boolean isLiveNonVolatile(T instance) {
        final long offset = unsafeOffset();
        final int rawCnt = offset != -1 ? PlatformDependent.getInt(instance, offset) : updater().get(instance);

        // The "real" ref count is > 0 if the rawCnt is even.
        return rawCnt == 2 || rawCnt == 4 || rawCnt == 6 || rawCnt == 8 || (rawCnt & 1) == 0;
    }

    /**
     * An unsafe operation that sets the reference count directly
     */
    public final void setRefCnt(T instance, int refCnt) {
        updater().set(instance, refCnt > 0 ? refCnt << 1 : 1); // overflow OK here
    }

    /**
     * Resets the reference count to 1
     */
    public final void resetRefCnt(T instance) {
        updater().set(instance, initialValue());
    }

    public final T retain(T instance) {
        return retain0(instance, 1, 2);
    }

    public final T retain(T instance, int increment) {
        // all changes to the raw count are 2x the "real" change - overflow is OK
        int rawIncrement = checkPositive(increment, "increment") << 1;
        return retain0(instance, increment, rawIncrement);
    }

    // rawIncrement == increment << 1
    private T retain0(T instance, final int increment, final int rawIncrement) {
        int oldRef = updater().getAndAdd(instance, rawIncrement);
        if (oldRef != 2 && oldRef != 4 && (oldRef & 1) != 0) {
            throw new IllegalReferenceCountException(0, increment);
        }
        // don't pass 0!
        if ((oldRef <= 0 && oldRef + rawIncrement >= 0)
                || (oldRef >= 0 && oldRef + rawIncrement < oldRef)) {
            // overflow case
            updater().getAndAdd(instance, -rawIncrement);
            throw new IllegalReferenceCountException(realRefCnt(oldRef), increment);
        }
        return instance;
    }

    /**
     * 释放ByteBuf，refCnt-2
     *
     * @param instance
     * @return
     */
    public final boolean release(T instance) {
        // #1 通过 Unsafe 非原子性获取当前对象的变量 refCnt 的值
        int rawCnt = nonVolatileRawCnt(instance);
        // #2 rawCnt==2():直接将refCnt置为1
        //      tryFinalRelease0: 只尝试一次，通过 CAS 设置refCnt值为1
        //      尝试失败，则 retryRelease0，则在for(;;) 中更新计数引用的值，直到成功为止
        //    rawCnt != 2，表示此次释放并非是彻底释放，
        return rawCnt == 2 ? tryFinalRelease0(instance, 2) || retryRelease0(instance, 1)
                : nonFinalRelease0(instance, 1, rawCnt, toLiveRealRefCnt(rawCnt, 1));
    }

    public final boolean release(T instance, int decrement) {
        int rawCnt = nonVolatileRawCnt(instance);
        int realCnt = toLiveRealRefCnt(rawCnt, checkPositive(decrement, "decrement"));
        return decrement == realCnt ? tryFinalRelease0(instance, rawCnt) || retryRelease0(instance, decrement)
                : nonFinalRelease0(instance, decrement, rawCnt, realCnt);
    }

    private boolean tryFinalRelease0(T instance, int expectRawCnt) {
        // 将refCnt的值从期望值expectRawCnt变成1
        return updater().compareAndSet(instance, expectRawCnt, 1); // any odd number will work
    }

    private boolean nonFinalRelease0(T instance, int decrement, int rawCnt, int realCnt) {
        if (decrement < realCnt
                // all changes to the raw count are 2x the "real" change - overflow is OK
                && updater().compareAndSet(instance, rawCnt, rawCnt - (decrement << 1))) {
            return false;
        }
        return retryRelease0(instance, decrement);
    }

    /**
     * 尝试释放: 将对象 instance 的refCnt值逻辑-1，物理-2
     */
    private boolean retryRelease0(T instance, int decrement) {
        for (;;) {
            // #1 获取refCnt物理值
            // 获取实际的引用数，如果为奇数，则抛出异常，
            // 因为当前 ByteBuf 不存在引用，也就不存在释放这一说法
            int rawCnt = updater().get(instance);
            // #2 获取refCnt逻辑值
            int realCnt = toLiveRealRefCnt(rawCnt, decrement);
            // #3 如果减数==realCnt，表示该ByteBuf需要释放，即refCnt=1
            if (decrement == realCnt) {
                if (tryFinalRelease0(instance, rawCnt)) {
                    return true;
                }
            } else if (decrement < realCnt) {
                // 如果减数小于实际值，则更新 rawCnt-2
                if (updater().compareAndSet(instance, rawCnt, rawCnt - (decrement << 1))) {
                    return false;
                }
            } else {
                // 否则抛出异常
                throw new IllegalReferenceCountException(realCnt, -decrement);
            }
            // 在高并发情况下，这有助于提高吞吐量
            // 线程让步: 担心当前线程对CPU资源占用过多，所以主要让自己从执行状态变为就绪状态，和其他线程竞争上岗
            Thread.yield(); // this benefits throughput under high contention
        }
    }
}
