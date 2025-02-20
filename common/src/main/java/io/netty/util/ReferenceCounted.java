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

/**
 * A reference-counted object that requires explicit deallocation.
 * <p>
 * When a new {@link ReferenceCounted} is instantiated, it starts with the reference count of {@code 1}.
 * {@link #retain()} increases the reference count, and {@link #release()} decreases the reference count.
 * If the reference count is decreased to {@code 0}, the object will be deallocated explicitly, and accessing
 * the deallocated object will usually result in an access violation.
 * </p>
 * <p>
 * If an object that implements {@link ReferenceCounted} is a container of other objects that implement
 * {@link ReferenceCounted}, the contained objects will also be released via {@link #release()} when the container's
 * reference count becomes 0.
 * </p>
 * Netty 在第 4 版中为 ByteBuf 和 ByteBufHolder 引入了用计数技术，它们都实现了 interface ReferenceCounted。
 * 一个特定的{@link ReferenceCounted}实现类，可以用它自己的独特方式来定义它
 * 的引用计数规则。例如，我们可以设想一个类，其 release()方法的实现总是将引用计数设为
 * 零， 而不用关心它的当前值，从而一次性地使所有的活动引用都失效。
 */
public interface ReferenceCounted {
    /**
     * 获取引用次数。
     */
    int refCnt();

    /**
     * retain：维持的意思
     * 增加一次引用次数。
     */
    ReferenceCounted retain();

    /**
     * 将引用次数+increment
     */
    ReferenceCounted retain(int increment);

    /**
     * Records the current access location of this object for debugging purposes.
     * If this object is determined to be leaked, the information recorded by this operation will be provided to you
     * via {@link ResourceLeakDetector}.  This method is a shortcut to {@link #touch(Object) touch(null)}.
     * 用于调试，记录此对象的当前访问的位置。
     * 如果确定此对象存在内存泄漏无问题，则会将此操作通过 ResourceLeakDetector 报告给用户。
     * 相当于 touch(null)。
     */
    ReferenceCounted touch();

    /**
     * Records the current access location of this object with an additional arbitrary information for debugging
     * purposes.  If this object is determined to be leaked, the information recorded by this operation will be
     * provided to you via {@link ResourceLeakDetector}.
     * hint：线索
     * 和 touch() 一样，只不过添加了额外的 hint 信息。
     */
    ReferenceCounted touch(Object hint);

    /**
     * Decreases the reference count by {@code 1} and deallocates this object if the reference count reaches at
     * {@code 0}.
     * 引用计数-1。如果引用次数为0则该对象会被释放。
     *
     * @return {@code true} if and only if the reference count became {@code 0} and this object has been deallocated
     */
    boolean release();

    /**
     * Decreases the reference count by the specified {@code decrement} and deallocates this object if the reference
     * count reaches at {@code 0}.
     * 引用次数-decrement。
     *
     * @return {@code true} if and only if the reference count became {@code 0} and this object has been deallocated
     */
    boolean release(int decrement);
}
