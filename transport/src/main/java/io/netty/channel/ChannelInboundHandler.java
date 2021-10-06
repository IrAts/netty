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

/**
 * {@link ChannelHandler} which adds callbacks for state changes. This allows the user
 * to hook in to state changes easily.
 */
public interface ChannelInboundHandler extends ChannelHandler {

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} was registered with its {@link EventLoop}
     * 当 Channel 已经注册到它的 EventLoop 并且能够处理 I/O 时被调用。
     */
    void channelRegistered(ChannelHandlerContext ctx) throws Exception;

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} was unregistered from its
     * 当{@link Channel}从它的{@link EventLoop}注销并且无法处理任何 I/O 时被调用。
     */
    void channelUnregistered(ChannelHandlerContext ctx) throws Exception;

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} is now active.
     * 当 Channel 处于活动状态时被调用； Channel 已经连接/绑定并且已经就绪。
     */
    void channelActive(ChannelHandlerContext ctx) throws Exception;

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} was registered is now inactive and reached its
     * end of lifetime.
     * 当 Channel 离开活动状态并且不再连接它的远程节点时被调用。
     */
    void channelInactive(ChannelHandlerContext ctx) throws Exception;

    /**
     * Invoked when the current {@link Channel} has read a message from the peer.
     * 当{@link Channel}有消息到达时调用。
     */
    void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception;

    /**
     * Invoked when the last message read by the current read operation has been consumed by
     * {@link #channelRead(ChannelHandlerContext, Object)}. If {@link ChannelOption#AUTO_READ}
     * is off, no further、attempt to read an inbound data from the current {@link Channel}
     * will be made until {@link ChannelHandlerContext#read()} is called.
     * <p>
     * 当当前这条消息被{@link #channelRead(ChannelHandlerContext, Object)}读取完成时调用本方法。
     * 当前消息被读取完成？？？？在脱离应用协议的情景下，我Netty怎么知道来自TCP中的数据流是否读取完成？
     * 这个注释写得有问题，模糊不清的。
     * <br/>
     * 实际上，当前消息被读取完成指的是该次对传输层的read操作无法获取到任何数据，也就是以下两种情况：
     *      1.对传输层的read()操作返回0
     *      2.对传输层的read()操作返回了一个缓冲区，这个缓冲区里读取到的数据的长度>0并且<1024。(这里的1024应该是ByteBuf的默认最大长度)
     * <br/>
     * 换句话说，就是每次读事件发生时，Netty会持续读取传输层的数据，直到传输层的数据被读取完，那么就会调用本方法。
     * 这样看来，他的注释好像也没问题。。。。
     * 由于Netty在从传输层读取数据的时候，发送方可能在一直发送数据，又由于接收方的接受速率和发送方发送速率是不确定的。
     * 所以也就是说，这个方法的调用时机也是不确定的，除非应用层协议加以严格限制发送方和接收方的交互。
     */
    void channelReadComplete(ChannelHandlerContext ctx) throws Exception;

    /**
     * Gets called if an user event was triggered.
     * 当 ChannelnboundHandler.fireUserEventTriggered()方法被调
     * 用时被调用，因为一个 POJO 被传经了 ChannelPipeline
     */
    void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception;

    /**
     * Gets called once the writable state of a {@link Channel} changed. You can check the state with
     * {@link Channel#isWritable()}.
     * 当 Channel 的可写状态发生改变时被调用。用户可以确保写操作不会完成
     * 得太快（以避免发生 OutOfMemoryError）或者可以在 Channel 变为再
     * 次可写时恢复写入。可以通过调用 Channel 的 isWritable()方法来检测
     * Channel 的可写性。与可写性相关的阈值可以通过 Channel.config().setWriteHighWaterMark()
     * 和Channel.config().setWriteLowWaterMark()方法来设置。
     */
    void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception;

    /**
     * Gets called if a {@link Throwable} was thrown.
     */
    @Override
    @SuppressWarnings("deprecation")
    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception;
}
