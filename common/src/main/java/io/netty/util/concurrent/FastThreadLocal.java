/*
 * Copyright 2014 The Netty Project
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
package io.netty.util.concurrent;

import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A special variant of {@link ThreadLocal} that yields higher access performance when accessed from a
 * {@link FastThreadLocalThread}.
 * <p>
 * {@link ThreadLocal}的一个特殊变体，当从{@link FastThreadLocalThread}访问时，可以获得更高的访问性能。
 * <p>
 * Internally, a {@link FastThreadLocal} uses a constant index in an array, instead of using hash code and hash table,
 * to look for a variable.  Although seemingly very subtle, it yields slight performance advantage over using a hash
 * table, and it is useful when accessed frequently.
 * <p>
 * 在内部，{@link FastThreadLocal}在数组中使用常量索引，而不是使用哈希码和哈希表来查找一个变量。
 * 尽管看起来非常微妙，但它比使用哈希表有略微的性能优势，尤其在经常访问时很有用。
 * <p>
 * To take advantage of this thread-local variable, your thread must be a {@link FastThreadLocalThread} or its subtype.
 * By default, all threads created by {@link DefaultThreadFactory} are {@link FastThreadLocalThread} due to this reason.
 * <p>
 * 要利用这个线程局部变量，你的线程必须是{@link FastThreadLocalThread}或它的子类型。
 * 由于这个原因，默认情况下，由{@link DefaultThreadFactory}创建的所有线程都是{@link FastThreadLocalThread}。
 * <p>
 * Note that the fast path is only possible on threads that extend {@link FastThreadLocalThread}, because it requires
 * a special field to store the necessary state.  An access by any other kind of thread falls back to a regular
 * {@link ThreadLocal}.
 * <p>
 * 注意，快速路径只可能在扩展{@link FastThreadLocalThread}的线程上。因为它需要
 * 存储必要状态的特殊字段。任何其他类型的线程访问都将返回到常规线程{@link ThreadLocal}。
 *
 * <h2>概述</h2>
 * 我们都知道，Java 的 ThreadLocal 用在多线程环境下，提供一种访问某个变量的特殊方式。由于 ThreadLocal 是在空间和时间之间
 * 寻求平衡，较好兼顾时间和性能。但是，Netty 通过理解 ThreadLocal 使用场景，觉得时间至上，最后利用空间换时间的思想重新设置
 * 了新的 FastThreadLocal，并配套实现了 FastThreadLocalThread 和 InternalThreadLocalMap 两个重要的类。我们下面要讲的
 * FastThreadLocal、FastThreadLocalThread、InternalThreadLocalMap 是和 ThreadLocal、Thread、ThreadLocalMap 是对
 * 等的，我们可以按照 ThreadLocal 的逻辑理解它们，区别是底层的实现不同。
 *
 * <h2>FastThreadLocal</h2>
 * 是 ThreadLocal 特殊的变体，在内部，FastThreadLocal 使用数组中的索引值查找变量，而非通过哈希表查找。这使
 * 它比使用哈希表查找具有轻微的性能优势，而且在频繁访问时非常有用。需要和 FastThreadLocalThread 配合使用才
 * 能发挥最高性能，Netty 提供 DefaultThreadFactory 工厂类创建 FastThreadLocalThread 线程。
 *
 * 需要注意的是，在使用 FastThreadLocal 的时候请务必配合 FastThreadLocalThread 使用。如果在普通线程中使用
 * FastThreadLocal，会导致该线程的 InternalThreadLocalMap 存储到 (JDK)ThreadLocalMap 中。当该线程想要通
 * 过 FastThreadLocal 获取值时，会先从 (JDK)ThreadLocalMap 拿到 InternalThreadLocalMap，接着再使用获取
 * 到的 InternalThreadLocalMap 获取 FastThreadLocal 对应的值。比直接通过 (JDK)ThreadLocalMap 还要慢。
 * @param <V> the type of the thread-local variable
 * @see ThreadLocal
 */
public class FastThreadLocal<V> {

    /**
     * 这个值为0。因为 FastThreadLocal 是唯一一个使用了 InternalThreadLocalMap.nextVariableIndex()的类。
     * 而这里是类加载的时候就调用了，那就说明这个肯定为0。
     *
     * 在 InternalThreadLocalMap 用非静态的 Object[] 成员变量 indexedVariables 来存储各个 FastThreadLocal
     * 对应的值。但是唯独0位置用于存放一个set，该set用于记录当前线程所有的 FastThreadLocal ，方便清空该线程的
     * InternalThreadLocalMap 时更加迅速。
     *
     * 在 {@link FastThreadLocal#removeAll} 方法中，可以看到从 variablesToRemoveIndex 位置获取到当前线程的
     * Set<FastThreadLocal>，然后遍历该Set从 InternalThreadLocalMap 中清掉变量。
     */
    private static final int variablesToRemoveIndex = InternalThreadLocalMap.nextVariableIndex();

    /**
     * Removes all {@link FastThreadLocal} variables bound to the current thread.  This operation is useful when you
     * are in a container environment, and you don't want to leave the thread local variables in the threads you do not
     * manage.
     * 移除所有绑定当前线程的变量值。当处于容器环境中时，此操作非常有用，您不希望将线程本地变量留在您不管理的线程中。
     */
    public static void removeAll() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return;
        }

        try {
            // 获取Set数组。对应indexedVariables[0]
            Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
            if (v != null && v != InternalThreadLocalMap.UNSET) {
                @SuppressWarnings("unchecked")
                Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
                FastThreadLocal<?>[] variablesToRemoveArray =
                        variablesToRemove.toArray(new FastThreadLocal[0]);
                // 遍历Set数组，挨个删除
                for (FastThreadLocal<?> tlv: variablesToRemoveArray) {
                    tlv.remove(threadLocalMap);
                }
            }
        } finally {
            // 清除「InternalThreadLocalMap」对象
            InternalThreadLocalMap.remove();
        }
    }

    /**
     * Returns the number of thread local variables bound to the current thread.
     */
    public static int size() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return 0;
        } else {
            return threadLocalMap.size();
        }
    }

    /**
     * Destroys the data structure that keeps all {@link FastThreadLocal} variables accessed from
     * non-{@link FastThreadLocalThread}s.  This operation is useful when you are in a container environment, and you
     * do not want to leave the thread local variables in the threads you do not manage.  Call this method when your
     * application is being unloaded from the container.
     */
    public static void destroy() {
        InternalThreadLocalMap.destroy();
    }

    @SuppressWarnings("unchecked")
    private static void addToVariablesToRemove(InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
        Set<FastThreadLocal<?>> variablesToRemove;
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            variablesToRemove = Collections.newSetFromMap(new IdentityHashMap<FastThreadLocal<?>, Boolean>());
            threadLocalMap.setIndexedVariable(variablesToRemoveIndex, variablesToRemove);
        } else {
            variablesToRemove = (Set<FastThreadLocal<?>>) v;
        }

        variablesToRemove.add(variable);
    }

    private static void removeFromVariablesToRemove(
            InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {

        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);

        if (v == InternalThreadLocalMap.UNSET || v == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
        variablesToRemove.remove(variable);
    }

    // 这是一个非常重要的数组索引值，它决定这个FastThreadLocal对象在数组的索引位置
    // 初始化时从「InternalThreadLocalMap」内部维护一个「AtomicInteger」对象获取。
    private final int index;

    public FastThreadLocal() {
        index = InternalThreadLocalMap.nextVariableIndex();
    }

    /**
     * Returns the current value for the current thread
     */
    @SuppressWarnings("unchecked")
    public final V get() {
        // #1 获取「InternalThreadLocalMap」对象，底层会根据线程类型采取不同策略
        // ① 如果是「FastThreadLocalThread」，直接从「FastThreadLocal」对象内存获取即可
        // ② 如果是「Thread」，创建new ThreadLocal<InternalThreadLocalMap>()的对象，初始化后返回
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        // #2 根据索引值获取值
        Object v = threadLocalMap.indexedVariable(index);
        // #3 判断是否和初始值相等
        // InternalThreadLocalMap内部存储元素的数据初始值都等于InternalThreadLocalMap.UNSET
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }
        // #4 如果为初始化，需要将初始值改为NULL（当然你也可以重写initialValue()方法返回一个默认值）
        // 并且添加到Object[0]的set集合中
        return initialize(threadLocalMap);
    }

    /**
     * Returns the current value for the current thread if it exists, {@code null} otherwise.
     */
    @SuppressWarnings("unchecked")
    public final V getIfExists() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap != null) {
            Object v = threadLocalMap.indexedVariable(index);
            if (v != InternalThreadLocalMap.UNSET) {
                return (V) v;
            }
        }
        return null;
    }

    /**
     * Returns the current value for the specified thread local map.
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final V get(InternalThreadLocalMap threadLocalMap) {
        Object v = threadLocalMap.indexedVariable(index);
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        return initialize(threadLocalMap);
    }

    private V initialize(InternalThreadLocalMap threadLocalMap) {
        V v = null;
        try {
            v = initialValue();
        } catch (Exception e) {
            PlatformDependent.throwException(e);
        }

        threadLocalMap.setIndexedVariable(index, v);
        addToVariablesToRemove(threadLocalMap, this);
        return v;
    }

    /**
     * Set the value for the current thread.
     */
    public final void set(V value) {
        // #1 先判断是否为初始值
        if (value != InternalThreadLocalMap.UNSET) {
            // #2 非初始值，获取「InternalThreadLocalMap」对象
            InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
            // #3 如果存在旧值，则覆盖，如果是新值（即UNSET），在覆盖完之后需要添加到Object[0]的Set集合中
            setKnownNotUnset(threadLocalMap, value);
        } else {
            // #4 初始值，移除掉原值
            // 这个方法会将当前ThredLocal的值设置为初始值，之后的第一次get()调用会触发initialValue()方法。
            remove();
        }
    }

    /**
     * Set the value for the specified thread local map. The specified thread local map must be for the current thread.
     */
    public final void set(InternalThreadLocalMap threadLocalMap, V value) {
        if (value != InternalThreadLocalMap.UNSET) {
            setKnownNotUnset(threadLocalMap, value);
        } else {
            remove(threadLocalMap);
        }
    }

    /**
     * @see InternalThreadLocalMap#setIndexedVariable(int, Object).
     */
    private void setKnownNotUnset(InternalThreadLocalMap threadLocalMap, V value) {
        if (threadLocalMap.setIndexedVariable(index, value)) {
            addToVariablesToRemove(threadLocalMap, this);
        }
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     */
    public final boolean isSet() {
        return isSet(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     * The specified thread local map must be for the current thread.
     */
    public final boolean isSet(InternalThreadLocalMap threadLocalMap) {
        return threadLocalMap != null && threadLocalMap.isIndexedVariableSet(index);
    }
    /**
     * Sets the value to uninitialized for the specified thread local map.
     * After this, any subsequent call to get() will trigger a new call to initialValue().
     * 将指定的threadLocal映射的值设置为未初始化。
     * 在此之后，对get()的任何后续调用都将触发对initialValue()的新调用。
     */
    public final void remove() {
        remove(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Sets the value to uninitialized for the specified thread local map.
     * After this, any subsequent call to get() will trigger a new call to initialValue().
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final void remove(InternalThreadLocalMap threadLocalMap) {
        if (threadLocalMap == null) {
            return;
        }

        // #1 将索引「index」对象置为「UNSET」
        Object v = threadLocalMap.removeIndexedVariable(index);
        // #2 从Object[0]的Set集合中移除
        removeFromVariablesToRemove(threadLocalMap, this);

        // #3 提高回调方法，当「FastThreadLocal」被移除后会调用「onRemove()」方法
        if (v != InternalThreadLocalMap.UNSET) {
            try {
                onRemoval((V) v);
            } catch (Exception e) {
                PlatformDependent.throwException(e);
            }
        }
    }

    /**
     * Returns the initial value for this thread-local variable.
     */
    protected V initialValue() throws Exception {
        return null;
    }

    /**
     * Invoked when this thread local variable is removed by {@link #remove()}. Be aware that {@link #remove()}
     * is not guaranteed to be called when the `Thread` completes which means you can not depend on this for
     * cleanup of the resources in the case of `Thread` completion.
     */
    protected void onRemoval(@SuppressWarnings("UnusedParameters") V value) throws Exception { }
}
