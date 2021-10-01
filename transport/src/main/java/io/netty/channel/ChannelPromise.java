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
package io.netty.channel;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

/**
 * Special {@link ChannelFuture} which is writable.
 *
 * {@link ChannelFuture}对于持有方而言是一个票据，一个不可变对象,
 * 也即当{@link ChannelFuture}一旦完成，那么结果将不会改变。
 *
 * 所以在设计{@link ChannelFuture}时，就应该设计为不可变对象。
 * 但事实上{@link ChannelFuture}创建出来的时候并非完整态，其需要
 * 等待实际的异步操作完成并设置相关的值，只有当{@link ChannelFuture}
 * 完成后才应该是不可变对象。
 *
 * 为了给持有方保留{@link ChannelFuture}为不可变对象的视觉，
 * {@link ChannelFuture}的接口被设计为不可变。
 * 而为了让一部操作能够设置{@link ChannelFuture}的状态，派生
 * 出子类{@link ChannelPromise}来进行状态设置，所以实际上在
 * Netty 内部传递的时候使用的是{@link ChannelPromise}，因为
 * Netty 内部需要修改{@link ChannelFuture}的状态。
 *
 * 到此，我们不难理解{@link ChannelPromise}为何增添以下方法：
 *  {@link ChannelPromise#setSuccess()}
 *  {@link ChannelPromise#trySuccess()}
 *  {@link ChannelPromise#unvoid()}
 *
 */
public interface ChannelPromise extends ChannelFuture, Promise<Void> {

    @Override
    Channel channel();

    @Override
    ChannelPromise setSuccess(Void result);

    ChannelPromise setSuccess();

    boolean trySuccess();

    @Override
    ChannelPromise setFailure(Throwable cause);

    @Override
    ChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener);

    @Override
    ChannelPromise addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners);

    @Override
    ChannelPromise removeListener(GenericFutureListener<? extends Future<? super Void>> listener);

    @Override
    ChannelPromise removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners);

    @Override
    ChannelPromise sync() throws InterruptedException;

    @Override
    ChannelPromise syncUninterruptibly();

    @Override
    ChannelPromise await() throws InterruptedException;

    @Override
    ChannelPromise awaitUninterruptibly();

    /**
     * Returns a new {@link ChannelPromise} if {@link #isVoid()} returns {@code true} otherwise itself.
     */
    ChannelPromise unvoid();
}
