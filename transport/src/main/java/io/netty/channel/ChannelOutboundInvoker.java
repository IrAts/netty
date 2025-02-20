/*
 * Copyright 2016 The Netty Project
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

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FutureListener;

import java.net.ConnectException;
import java.net.SocketAddress;

public interface ChannelOutboundInvoker {

    /**
     * Request to bind to the given {@link SocketAddress} and notify the {@link ChannelFuture} once the operation
     * completes, either because the operation was successful or because of an error.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#bind(ChannelHandlerContext, SocketAddress, ChannelPromise)} method
     * called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p>
     * 将 Channel 绑定到一个本地地址，这将调用 ChannelPipeline 中的下一个
     * ChannelOutboundHandler 的 bind(ChannelHandlerContext, SocketAddress, ChannelPromise)方法。
     */
    ChannelFuture bind(SocketAddress localAddress);

    /**
     * Request to connect to the given {@link SocketAddress} and notify the {@link ChannelFuture} once the operation
     * completes, either because the operation was successful or because of an error.
     * <p>
     * If the connection fails because of a connection timeout, the {@link ChannelFuture} will get failed with
     * a {@link ConnectTimeoutException}. If it fails because of connection refused a {@link ConnectException}
     * will be used.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p>
     * 将 Channel 连接到一个远程地址，这将调用 ChannelPipeline 中的下一个
     * ChannelOutboundHandler 的 connect(ChannelHandlerContext, SocketAddress, ChannelPromise)方法。
     */
    ChannelFuture connect(SocketAddress remoteAddress);

    /**
     * Request to connect to the given {@link SocketAddress} while bind to the localAddress and notify the
     * {@link ChannelFuture} once the operation completes, either because the operation was successful or because of
     * an error.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p>
     * 将 Channel 连接到一个远程地址，这将调用 ChannelPipeline 中的下一个
     * ChannelOutboundHandler 的 connect(ChannelHandlerContext, SocketAddress, ChannelPromise)方法。
     */
    ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress);

    /**
     * Request to disconnect from the remote peer and notify the {@link ChannelFuture} once the operation completes,
     * either because the operation was successful or because of an error.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#disconnect(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p>
     * 将Channel 断开连接。这将调用ChannelPipeline 中的下一个
     * ChannelOutboundHandler 的 disconnect(ChannelHandlerContext, Channel Promise)方法
     */
    ChannelFuture disconnect();

    /**
     * Request to close the {@link Channel} and notify the {@link ChannelFuture} once the operation completes,
     * either because the operation was successful or because of
     * an error.
     *
     * After it is closed it is not possible to reuse it again.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#close(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p>
     * 将 Channel 关闭。这将调用 ChannelPipeline 中的下一个
     * ChannelOutboundHandler 的 close(ChannelHandlerContext, ChannelPromise)方法。
     */
    ChannelFuture close();

    /**
     * Request to deregister from the previous assigned {@link EventExecutor} and notify the
     * {@link ChannelFuture} once the operation completes, either because the operation was successful or because of
     * an error.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#deregister(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p>
     * 将 Channel 从它先前所分配的 EventExecutor（即 EventLoop）中注销。这将调
     * 用 ChannelPipeline 中的下一个 ChannelOutboundHandler 的 deregister
     * (ChannelHandlerContext, ChannelPromise)方法。
     */
    ChannelFuture deregister();

    /**
     * Request to bind to the given {@link SocketAddress} and notify the {@link ChannelFuture} once the operation
     * completes, either because the operation was successful or because of an error.
     *
     * The given {@link ChannelPromise} will be notified.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#bind(ChannelHandlerContext, SocketAddress, ChannelPromise)} method
     * called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     */
    ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise);

    /**
     * Request to connect to the given {@link SocketAddress} and notify the {@link ChannelFuture} once the operation
     * completes, either because the operation was successful or because of an error.
     *
     * The given {@link ChannelFuture} will be notified.
     *
     * <p>
     * If the connection fails because of a connection timeout, the {@link ChannelFuture} will get failed with
     * a {@link ConnectTimeoutException}. If it fails because of connection refused a {@link ConnectException}
     * will be used.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p>
     * 将 Channel 连接到一个远程地址，这将调用 ChannelPipeline 中的下一个
     * ChannelOutboundHandler 的 connect(ChannelHandlerContext, SocketAddress, ChannelPromise)方法。
     */
    ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise);

    /**
     * Request to connect to the given {@link SocketAddress} while bind to the localAddress and notify the
     * {@link ChannelFuture} once the operation completes, either because the operation was successful or because of
     * an error.
     *
     * The given {@link ChannelPromise} will be notified and also returned.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p>
     * 将 Channel 连接到一个远程地址，这将调用 ChannelPipeline 中的下一个
     * ChannelOutboundHandler 的 connect(ChannelHandlerContext, SocketAddress, ChannelPromise)方法。
     */
    ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise);

    /**
     * Request to disconnect from the remote peer and notify the {@link ChannelFuture} once the operation completes,
     * either because the operation was successful or because of an error.
     *
     * The given {@link ChannelPromise} will be notified.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#disconnect(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p>
     * 将Channel 断开连接。这将调用ChannelPipeline 中的下一个
     * ChannelOutboundHandler 的 disconnect(ChannelHandlerContext, Channel Promise)方法
     */
    ChannelFuture disconnect(ChannelPromise promise);

    /**
     * Request to close the {@link Channel} and notify the {@link ChannelFuture} once the operation completes,
     * either because the operation was successful or because of
     * an error.
     *
     * After it is closed it is not possible to reuse it again.
     * The given {@link ChannelPromise} will be notified.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#close(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p>
     * 将 Channel 关闭。这将调用 ChannelPipeline 中的下一个
     * ChannelOutboundHandler 的 close(ChannelHandlerContext, ChannelPromise)方法。
     */
    ChannelFuture close(ChannelPromise promise);

    /**
     * Request to deregister from the previous assigned {@link EventExecutor} and notify the
     * {@link ChannelFuture} once the operation completes, either because the operation was successful or because of
     * an error.
     *
     * The given {@link ChannelPromise} will be notified.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#deregister(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p>
     * 将 Channel 从它先前所分配的 EventExecutor（即 EventLoop）中注销。这将调
     * 用 ChannelPipeline 中的下一个 ChannelOutboundHandler 的 deregister
     * (ChannelHandlerContext, ChannelPromise)方法。
     */
    ChannelFuture deregister(ChannelPromise promise);

    /**
     * Request to Read data from the {@link Channel} into the first inbound buffer, triggers an
     * {@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)} event if data was
     * read, and triggers a
     * {@link ChannelInboundHandler#channelReadComplete(ChannelHandlerContext) channelReadComplete} event so the
     * handler can decide to continue reading.  If there's a pending read operation already, this method does nothing.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#read(ChannelHandlerContext)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p>
     * 请求从{@link Channel}读取数据到第一个入站缓冲区，如果读取到数据则触发一个
     * {@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)}事件，
     * 并触发一个{@link ChannelInboundHandler#channelReadComplete(ChannelHandlerContext) channelReadComplete}事件，
     * 以便处理程序可以决定继续读取。如果调用该方法时已经有一个挂起的读操作，这个方法什么也不做。
     * <p>
     * 需要注意，对于{@link ChannelHandlerContext#read()}的实现而言，会从 pipeline 中排在当前 handler
     * 的后一个 inboundHandler 中读取数据，而不是从 pipeline 的头端开始读。
     * 而对于{@link Channel#read()}的实现，则会直接从 pipeline 的头部开始读取数据，让其一直流动到最后一个 inboundHandler。
     */
    ChannelOutboundInvoker read();

    /**
     * Request to write a message via this {@link ChannelHandlerContext} through the {@link ChannelPipeline}.
     * This method will not request to actual flush, so be sure to call {@link #flush()}
     * once you want to request to flush all pending data to the actual transport.
     * <p>
     * 将消息写入 Channel。这将调用 ChannelPipeline 中的下一个
     * ChannelOutboundHandler的write(ChannelHandlerContext, Object msg, ChannelPromise)方法。
     * 注意：这并不会将消息写入底层的 Socket，而只会将它放入队列中。
     * 要将它写入 Socket，需要调用 flush()或者 writeAndFlush()方法。
     * <p>
     * 需要注意，对于{@link ChannelHandlerContext#write(Object)}的实现而言，会将消息直接写到当前正在执行的handler的前一个
     * outboundHandler，而不是从 pipeline 的尾端开始向前流动消息。
     * 而对于{@link Channel#write(Object)}的实现，则会将消息直接写到 pipeline 的尾端，让其一直流动到第一个 outboundHandler。
     * <p>
     * 对于一个write操作，会将msg写入到对应的Channel，而每个Channel都由一个EventLoop负责处理IO事件。
     * 当执行write操作时，会判断调用write操作的线程是否为该Channel对应的EventLoop的支撑线程(每个EventLoop永久关联一个线程)。
     * 如果调用write操作的线程不是该Channel对应的EventLoop的支撑线程，就会将msg包装为一个写事件的Task提交给EventLoop执行。
     * 否则直接让当前线程执行。
     */
    ChannelFuture write(Object msg);

    /**
     * Request to write a message via this {@link ChannelHandlerContext} through the {@link ChannelPipeline}.
     * This method will not request to actual flush, so be sure to call {@link #flush()}
     * once you want to request to flush all pending data to the actual transport.
     * <p>
     * 将消息写入 Channel。这将调用 ChannelPipeline 中的下一个
     * ChannelOutboundHandler的write(ChannelHandlerContext, Object msg, ChannelPromise)方法。
     * 注意：这并不会将消息写入底层的 Socket，而只会将它放入队列中。
     * 要将它写入 Socket，需要调用 flush()或者 writeAndFlush()方法
     * <p>
     * 需要注意，对于{@link ChannelHandlerContext#write(Object)}的实现而言，会将消息直接写到当前正在执行的handler的前一个
     * outboundHandler，而不是从 pipeline 的尾端开始向前流动消息。
     * 而对于{@link Channel#write(Object)}的实现，则会将消息直接写到 pipeline 的尾端，让其一直流动到第一个 outboundHandler。
     * <p>
     * 对于一个write操作，会将msg写入到对应的Channel，而每个Channel都由一个EventLoop负责处理IO事件。
     * 当执行write操作时，会判断调用write操作的线程是否为该Channel对应的EventLoop的支撑线程(每个EventLoop永久关联一个线程)。
     * 如果调用write操作的线程不是该Channel对应的EventLoop的支撑线程，就会将msg包装为一个写事件的Task提交给EventLoop执行。
     * 否则直接让当前线程执行。
     */
    ChannelFuture write(Object msg, ChannelPromise promise);

    /**
     * Request to flush all pending messages via this ChannelOutboundInvoker.
     * <p>
     * 冲刷Channel所有挂起的写入。这将调用ChannelPipeline中的下一个
     * ChannelOutboundHandler 的 flush(ChannelHandlerContext)方法。
     */
    ChannelOutboundInvoker flush();

    /**
     * Shortcut for call {@link #write(Object, ChannelPromise)} and {@link #flush()}.
     * <p>
     * 这是一个先调用 write()方法再接着调用 flush()方法的便利方法。
     * <p>
     * 需要注意，对于{@link ChannelHandlerContext#write(Object)}的实现而言，会将消息直接写到当前正在执行的handler的前一个
     * outboundHandler，而不是从 pipeline 的尾端开始向前流动消息。
     * 而对于{@link Channel#write(Object)}的实现，则会将消息直接写到 pipeline 的尾端，让其一直流动到第一个 outboundHandler。
     * <p>
     * 对于一个write操作，会将msg写入到对应的Channel，而每个Channel都由一个EventLoop负责处理IO事件。
     * 当执行write操作时，会判断调用write操作的线程是否为该Channel对应的EventLoop的支撑线程(每个EventLoop永久关联一个线程)。
     * 如果调用write操作的线程不是该Channel对应的EventLoop的支撑线程，就会将msg包装为一个写事件的Task提交给EventLoop执行。
     * 否则直接让当前线程执行。
     */
    ChannelFuture writeAndFlush(Object msg, ChannelPromise promise);

    /**
     * Shortcut for call {@link #write(Object)} and {@link #flush()}.
     * <p>
     * 这是一个先调用 write()方法再接着调用 flush()方法的便利方法。
     * <p>
     * 需要注意，对于{@link ChannelHandlerContext#write(Object)}的实现而言，会将消息直接写到当前正在执行的handler的前一个
     * outboundHandler，而不是从 pipeline 的尾端开始向前流动消息。
     * 而对于{@link Channel#write(Object)}的实现，则会将消息直接写到 pipeline 的尾端，让其一直流动到第一个 outboundHandler。
     * <p>
     * 对于一个write操作，会将msg写入到对应的Channel，而每个Channel都由一个EventLoop负责处理IO事件。
     * 当执行write操作时，会判断调用write操作的线程是否为该Channel对应的EventLoop的支撑线程(每个EventLoop永久关联一个线程)。
     * 如果调用write操作的线程不是该Channel对应的EventLoop的支撑线程，就会将msg包装为一个写事件的Task提交给EventLoop执行。
     * 否则直接让当前线程执行。
     */
    ChannelFuture writeAndFlush(Object msg);

    /**
     * Return a new {@link ChannelPromise}.
     */
    ChannelPromise newPromise();

    /**
     * Return an new {@link ChannelProgressivePromise}
     */
    ChannelProgressivePromise newProgressivePromise();

    /**
     * Create a new {@link ChannelFuture} which is marked as succeeded already. So {@link ChannelFuture#isSuccess()}
     * will return {@code true}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     */
    ChannelFuture newSucceededFuture();

    /**
     * Create a new {@link ChannelFuture} which is marked as failed already. So {@link ChannelFuture#isSuccess()}
     * will return {@code false}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     */
    ChannelFuture newFailedFuture(Throwable cause);

    /**
     * Return a special ChannelPromise which can be reused for different operations.
     * <p>
     * It's only supported to use
     * it for {@link ChannelOutboundInvoker#write(Object, ChannelPromise)}.
     * </p>
     * <p>
     * Be aware that the returned {@link ChannelPromise} will not support most operations and should only be used
     * if you want to save an object allocation for every write operation. You will not be able to detect if the
     * operation  was complete, only if it failed as the implementation will call
     * {@link ChannelPipeline#fireExceptionCaught(Throwable)} in this case.
     * </p>
     * <strong>Be aware this is an expert feature and should be used with care!</strong>
     */
    ChannelPromise voidPromise();
}
