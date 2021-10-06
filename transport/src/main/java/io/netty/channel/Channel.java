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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.AttributeMap;

import java.net.InetSocketAddress;
import java.net.SocketAddress;


/**
 * A nexus to a network socket or a component which is capable of I/O
 * operations such as read, write, connect, and bind.
 * 连接到网络套接字或能够进行读、写、连接和绑定等I/O操作的组件。
 * <p>
 * A channel provides a user:
 * <ul>
 * <li>the current state of the channel (e.g. is it open? is it connected?),</li>
 * <li>the {@linkplain ChannelConfig configuration parameters} of the channel (e.g. receive buffer size),</li>
 * <li>the I/O operations that the channel supports (e.g. read, write, connect, and bind), and</li>
 * <li>the {@link ChannelPipeline} which handles all I/O events and requests
 *     associated with the channel.</li>
 * </ul>
 *
 * <h3>All I/O operations are asynchronous.</h3>
 * <p>
 * All I/O operations in Netty are asynchronous.  It means any I/O calls will
 * return immediately with no guarantee that the requested I/O operation has
 * been completed at the end of the call.  Instead, you will be returned with
 * a {@link ChannelFuture} instance which will notify you when the requested I/O
 * operation has succeeded, failed, or canceled.
 * <p>
 * 所有的IO操作都是异步的。这意味着任何IO调用将立即返回，而不保证请求的IO操作已在调用结束时完成。
 * 相反，你将返回一个{@link ChannelFuture}实例，当请求的IO操作成功、失败或取消时，该实例将通知你。
 *
 * <h3>Channels are hierarchical</h3>
 * <p>
 * A {@link Channel} can have a {@linkplain #parent() parent} depending on
 * how it was created.  For instance, a {@link SocketChannel}, that was accepted
 * by {@link ServerSocketChannel}, will return the {@link ServerSocketChannel}
 * as its parent on {@link #parent()}.
 * <p>
 * The semantics of the hierarchical structure depends on the transport
 * implementation where the {@link Channel} belongs to.  For example, you could
 * write a new {@link Channel} implementation that creates the sub-channels that
 * share one socket connection, as <a href="http://beepcore.org/">BEEP</a> and
 * <a href="https://en.wikipedia.org/wiki/Secure_Shell">SSH</a> do.
 *
 * <h3>Downcast to access transport-specific operations</h3>
 * <p>
 * Some transports exposes additional operations that is specific to the
 * transport.  Down-cast the {@link Channel} to sub-type to invoke such
 * operations.  For example, with the old I/O datagram transport, multicast
 * join / leave operations are provided by {@link DatagramChannel}.
 *
 * <h3>Release resources</h3>
 * <p>
 * It is important to call {@link #close()} or {@link #close(ChannelPromise)} to release all
 * resources once you are done with the {@link Channel}. This ensures all resources are
 * released in a proper way, i.e. filehandles.
 *
 * Channel，EventLoop和ChannelFuture可以被认为是Netty网络抽象的代表。
 *      Channel -> Socket
 *      EventLop -> 控制流，多线程处理，并发
 *      ChannelFuture -> 异步通知
 *
 * Channel 被创建时，会生成一个专属于自己的 ChannelPipeLine。TODO 具体是在什么时候为 Channel 生成 ChannelPipeLine 呢?
 *      ChannelInitializer的实现被注册到了ServerBootstrap中。
 *      当 ChannelInitializer.initChannel()方法被调用时，ChannelInitializer 将在 ChannelPipeline 中安装一组自定义的 ChannelHandler。
 *      接着 ChannelInitializer 将它自己从 ChannelPipeline 中移除。
 *
 * 每个Channel都是唯一的，为了保证顺序而继承{@link Comparable}接口。若两个不同的 Channel 返回了相同的散列码，则该实现的{@link Comparable#compareTo(Object)}就会报错。
 *
 * 一个Channel的普通生命周期状态：
 *      {@link ChannelHandlerMask#MASK_CHANNEL_UNREGISTERED} Channel 已经被创建，但还未注册到 EventLoop
 *      {@link ChannelHandlerMask#MASK_CHANNEL_REGISTERED}   Channel 已经被注册到了 EventLoop
 *      {@link ChannelHandlerMask#MASK_CHANNEL_ACTIVE}       Channel 已经连接到它的远程节点，现在可以收发数据
 *      {@link ChannelHandlerMask#MASK_CHANNEL_INACTIVE}     Channel 没有连接到远程节点
 *
 * 注意：
 * Channel并非是与Socket一样的存在，Socket是只用于应用层与传输层传输数据的，如下图：
 *      传  ->  +--------+  ->  应
 *      输      | Socket |      用
 *      层  <-  +--------+  <-  层
 *
 * 对于应用层来说，从Socket中读取数据就等同于直接从传输层读取数据，如对于使用TCP传输时，应用层从Socket拿到
 * 的数据就是一个字节流，应用层负责将这些字节解析成消息并进行相应的处理。即在此等抽象下，对从Socket读取的数据
 * 进行解析并执行业务处理是应用层的工作。
 * 在Netty中，进行数据解析和业务处理的是由多个 InboundHandler/OutboundHandler 组成的 Pipeline，即
 * Pipeline 就是业务层，将上图完善就得：
 *      传  ->  +--------+  ->  +-------------------------+
 *      输      | Socket |      |        pipeline         |
 *      层  <-  +--------+  <-  +-------------------------+
 *
 * 到现在，终于可以揭晓Channel的抽象到底处于哪个位置，看下图：
 *      传  ->  +--------+  ->  +-------------------------+
 *      输      | Socket |      |        pipeline         |
 *      层  <-  +--------+  <-  +-------------------------+
 *             |<--------------- Channel ---------------->|
 *
 * 很诡异，Channel竟然包括了Socket与应用层。。怎么得出这个结论的呢？这得从Channel的方法实现说起
 * 对于方法{@link Channel#write(Object)}，写入数据的位置是pipeline的尾部。数据会从最后一个
 * OutboundHandler一直往前流动，如下图：
 *      传  ->  +--------+  ->  +-------------------------+
 *      输      | Socket |      |        pipeline         |  <-- {@link Channel#write(Object)}
 *      层  <-  +--------+  <-  +-------------------------+
 *             |<--------------- Channel ---------------->|
 *
 * 对于方法{@link Channel#read()}，则会调用 pipeline 头部的 read() 方法，让 pipeline 从
 * Socket 抽象中获取数据，即实际上是通过 Socket 从传输层获取数据。如下图：
 *            +-------------------------------{@link Channel#read()}
 *            ↓
 *      传  ->  +--------+  ->  +-------------------------+
 *      输      | Socket |      |        pipeline         |
 *      层  <-  +--------+  <-  +-------------------------+
 *             |<--------------- Channel ---------------->|
 *
 * 看了上面的两个方法的实现，也就能够理解为什么 Channel 抽象所处的位置是这样的了。
 * 在我看来，Channel的抽象层设计有问题。我觉得更好的设计应该是让 Channel 和 Pipeline 独立开。
 * 也就是让 Channel 在抽象上与 Socket 同等。这样的话{@link Channel#write(Object)}方法
 * 会让写入的消息直接写道Socket，接着传递给传输层。而{@link Channel#read()}方法就是从传输层
 * 读取数据。然后实现上将 Pipeline 和 Channel 包装为一个处理单元，EventLoop 注册这个处理单元。
 *
 */
public interface Channel extends AttributeMap, ChannelOutboundInvoker, Comparable<Channel> {

    /**
     * Returns the globally unique identifier of this {@link Channel}.
     */
    ChannelId id();

    /**
     * Return the {@link EventLoop} this {@link Channel} was registered to.
     */
    EventLoop eventLoop();

    /**
     * Returns the parent of this channel.
     *
     * @return the parent channel.
     *         {@code null} if this channel does not have a parent channel.
     */
    Channel parent();

    /**
     * Returns the configuration of this channel.
     */
    ChannelConfig config();

    /**
     * Returns {@code true} if the {@link Channel} is open and may get active later
     */
    boolean isOpen();

    /**
     * Returns {@code true} if the {@link Channel} is registered with an {@link EventLoop}.
     */
    boolean isRegistered();

    /**
     * Return {@code true} if the {@link Channel} is active and so connected.
     * 如果 Channel 是活动的，则返回 true 。
     *
     * 活动的意义可能依赖于底层的传输。例如，一个 Socket 传输一旦连接到
     * 了远程节点便是活动的，而一个 Datagram 传输一旦被打开便是活动的。
     */
    boolean isActive();

    /**
     * Return the {@link ChannelMetadata} of the {@link Channel} which describe the nature of the {@link Channel}.
     */
    ChannelMetadata metadata();

    /**
     * Returns the local address where this channel is bound to.  The returned
     * {@link SocketAddress} is supposed to be down-cast into more concrete
     * type such as {@link InetSocketAddress} to retrieve the detailed
     * information.
     *
     * @return the local address of this channel.
     *         {@code null} if this channel is not bound.
     */
    SocketAddress localAddress();

    /**
     * Returns the remote address where this channel is connected to.  The
     * returned {@link SocketAddress} is supposed to be down-cast into more
     * concrete type such as {@link InetSocketAddress} to retrieve the detailed
     * information.
     *
     * @return the remote address of this channel.
     *         {@code null} if this channel is not connected.
     *         If this channel is not connected but it can receive messages
     *         from arbitrary remote addresses (e.g. {@link DatagramChannel},
     *         use {@link DatagramPacket#recipient()} to determine
     *         the origination of the received message as this method will
     *         return {@code null}.
     */
    SocketAddress remoteAddress();

    /**
     * Returns the {@link ChannelFuture} which will be notified when this
     * channel is closed.  This method always returns the same future instance.
     */
    ChannelFuture closeFuture();

    /**
     * Returns {@code true} if and only if the I/O thread will perform the
     * requested write operation immediately.  Any write requests made when
     * this method returns {@code false} are queued until the I/O thread is
     * ready to process the queued write requests.
     */
    boolean isWritable();

    /**
     * Get how many bytes can be written until {@link #isWritable()} returns {@code false}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code false} then 0.
     */
    long bytesBeforeUnwritable();

    /**
     * Get how many bytes must be drained from underlying buffers until {@link #isWritable()} returns {@code true}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code true} then 0.
     */
    long bytesBeforeWritable();

    /**
     * Returns an <em>internal-use-only</em> object that provides unsafe operations.
     */
    Unsafe unsafe();

    /**
     * Return the assigned {@link ChannelPipeline}.
     */
    ChannelPipeline pipeline();

    /**
     * Return the assigned {@link ByteBufAllocator} which will be used to allocate {@link ByteBuf}s.
     */
    ByteBufAllocator alloc();

    @Override
    Channel read();

    @Override
    Channel flush();

    /**
     * <em>Unsafe</em> operations that should <em>never</em> be called from user-code. These methods
     * are only provided to implement the actual transport, and must be invoked from an I/O thread except for the
     * following methods:
     * <ul>
     *   <li>{@link #localAddress()}</li>
     *   <li>{@link #remoteAddress()}</li>
     *   <li>{@link #closeForcibly()}</li>
     *   <li>{@link #register(EventLoop, ChannelPromise)}</li>
     *   <li>{@link #deregister(ChannelPromise)}</li>
     *   <li>{@link #voidPromise()}</li>
     * </ul>
     *
     * <p>
     * <em>Unsafe</em>永远<em>不应该</em>被用户代码调用。这些方法只提供来实现实际的传输，并且必须从I/O线程调用，除了下面的方法：
     * <ul>
     *   <li>{@link #localAddress()}</li>
     *   <li>{@link #remoteAddress()}</li>
     *   <li>{@link #closeForcibly()}</li>
     *   <li>{@link #register(EventLoop, ChannelPromise)}</li>
     *   <li>{@link #deregister(ChannelPromise)}</li>
     *   <li>{@link #voidPromise()}</li>
     * </ul>
     *
     */
    interface Unsafe {

        /**
         * Return the assigned {@link RecvByteBufAllocator.Handle} which will be used to allocate {@link ByteBuf}'s when
         * receiving data.
         * <p>
         * 返回指定的{@link RecvByteBufAllocator.Handle}，用于当接收数据时分配{@link ByteBuf}。
         */
        RecvByteBufAllocator.Handle recvBufAllocHandle();

        /**
         * Return the {@link SocketAddress} to which is bound local or {@code null} if none.
         * <p>
         * 返回绑定到本地的{@link SocketAddress}，如果没有，返回{@code null}。
         */
        SocketAddress localAddress();

        /**
         * Return the {@link SocketAddress} to which is bound remote or {@code null} if none is bound yet.
         * <p>
         * 返回远端绑定的{@link SocketAddress}，如果还没有绑定，则返回{@code null}
         */
        SocketAddress remoteAddress();

        /**
         * Register the {@link Channel} of the {@link ChannelPromise} and notify
         * the {@link ChannelFuture} once the registration was complete.
         * <p>
         * 注册{@link ChannelPromise}的{@link Channel}，并在注册完成后通知{@link ChannelFuture}。
         */
        void register(EventLoop eventLoop, ChannelPromise promise);

        /**
         * Bind the {@link SocketAddress} to the {@link Channel} of the {@link ChannelPromise} and notify
         * it once its done.
         * <p>
         * 绑定{@link SocketAddress}到{@link ChannelPromise}的{@link Channel}，并在它完成后通知它。
         */
        void bind(SocketAddress localAddress, ChannelPromise promise);

        /**
         * Connect the {@link Channel} of the given {@link ChannelFuture} with the given remote {@link SocketAddress}.
         * If a specific local {@link SocketAddress} should be used it need to be given as argument. Otherwise just
         * pass {@code null} to it.
         *
         * The {@link ChannelPromise} will get notified once the connect operation was complete.
         */
        void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise);

        /**
         * Disconnect the {@link Channel} of the {@link ChannelFuture} and notify the {@link ChannelPromise} once the
         * operation was complete.
         */
        void disconnect(ChannelPromise promise);

        /**
         * Close the {@link Channel} of the {@link ChannelPromise} and notify the {@link ChannelPromise} once the
         * operation was complete.
         */
        void close(ChannelPromise promise);

        /**
         * Closes the {@link Channel} immediately without firing any events.  Probably only useful
         * when registration attempt failed.
         */
        void closeForcibly();

        /**
         * Deregister the {@link Channel} of the {@link ChannelPromise} from {@link EventLoop} and notify the
         * {@link ChannelPromise} once the operation was complete.
         */
        void deregister(ChannelPromise promise);

        /**
         * Schedules a read operation that fills the inbound buffer of the first {@link ChannelInboundHandler} in the
         * {@link ChannelPipeline}.  If there's already a pending read operation, this method does nothing.
         * <p>
         * 调度一个读操作，填充{@link ChannelPipeline}中第一个{@link ChannelInboundHandler}的入站缓冲区。
         * 如果已经有一个挂起的读操作，则此方法不执行任何操作。
         */
        void beginRead();

        /**
         * Schedules a write operation.
         */
        void write(Object msg, ChannelPromise promise);

        /**
         * Flush out all write operations scheduled via {@link #write(Object, ChannelPromise)}.
         */
        void flush();

        /**
         * Return a special ChannelPromise which can be reused and passed to the operations in {@link Unsafe}.
         * It will never be notified of a success or error and so is only a placeholder for operations
         * that take a {@link ChannelPromise} as argument but for which you not want to get notified.
         * <p>
         * 返回一个特殊的ChannelPromise，它可以被重用并传递给{@link Unsafe}中的操作。
         * 它永远不会收到成功或错误的通知，所以它只是一个占位符，用于接受{@link ChannelPromise}作为参数但你不想得到通知的操作。
         */
        ChannelPromise voidPromise();

        /**
         * Returns the {@link ChannelOutboundBuffer} of the {@link Channel} where the pending write requests are stored.
         * <p>
         * 返回存储挂起写请求的{@link Channel}的{@link ChannelOutboundBuffer}。
         */
        ChannelOutboundBuffer outboundBuffer();
    }
}