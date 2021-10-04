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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Integer.MAX_VALUE;

/**
 * {@link ChannelInboundHandlerAdapter} which decodes bytes in a stream-like fashion from one {@link ByteBuf} to an
 * other Message type.
 *
 * For example here is an implementation which reads all readable bytes from
 * the input {@link ByteBuf} and create a new {@link ByteBuf}.
 *
 * <pre>
 *     public class SquareDecoder extends {@link ByteToMessageDecoder} {
 *         {@code @Override}
 *         public void decode({@link ChannelHandlerContext} ctx, {@link ByteBuf} in, List&lt;Object&gt; out)
 *                 throws {@link Exception} {
 *             out.add(in.readBytes(in.readableBytes()));
 *         }
 *     }
 * </pre>
 *
 * <h3>Frame detection</h3>
 * <p>
 * Generally frame detection should be handled earlier in the pipeline by adding a
 * {@link DelimiterBasedFrameDecoder}, {@link FixedLengthFrameDecoder}, {@link LengthFieldBasedFrameDecoder},
 * or {@link LineBasedFrameDecoder}.
 * <p>
 * If a custom frame decoder is required, then one needs to be careful when implementing
 * one with {@link ByteToMessageDecoder}. Ensure there are enough bytes in the buffer for a
 * complete frame by checking {@link ByteBuf#readableBytes()}. If there are not enough bytes
 * for a complete frame, return without modifying the reader index to allow more bytes to arrive.
 * <p>
 * 如果需要自定义帧解码器，那么在使用{@link ByteToMessageDecoder}实现时需要小心。
 * 通过检查{@link ByteBuf#readableBytes()}，确保缓冲区中有足够的字节用于完整的帧。
 * 如果没有足够的字节作为一个完整的帧，返回时不修改读取器索引以允许更多的字节到达。
 * <p>
 * To check for complete frames without modifying the reader index, use methods like {@link ByteBuf#getInt(int)}.
 * One <strong>MUST</strong> use the reader index when using methods like {@link ByteBuf#getInt(int)}.
 * For example calling <tt>in.getInt(0)</tt> is assuming the frame starts at the beginning of the buffer, which
 * is not always the case. Use <tt>in.getInt(in.readerIndex())</tt> instead.
 * <p>
 * 要在不修改读取器索引的情况下检查完整的帧，可以使用{@link ByteBuf#getInt(int)}这样的方法。
 * 当使用像{@link ByteBuf#getInt(int)}这样的方法时，必须使用reader索引。
 * 例如，调用<tt>in.getInt(0)</tt>是假设帧从缓冲区的开始，但并不总是这样。使用in.getInt(in.readerIndex())来代替。
 *
 * <h3>Pitfalls</h3>
 * <p>
 * Be aware that sub-classes of {@link ByteToMessageDecoder} <strong>MUST NOT</strong>
 * annotated with {@link @Sharable}.
 * <p>
 * Some methods such as {@link ByteBuf#readBytes(int)} will cause a memory leak if the returned buffer
 * is not released or added to the <tt>out</tt> {@link List}. Use derived buffers like {@link ByteBuf#readSlice(int)}
 * to avoid leaking memory.
 *
 */
public abstract class ByteToMessageDecoder extends ChannelInboundHandlerAdapter {

    /**
     * Cumulate {@link ByteBuf}s by merge them into one {@link ByteBuf}'s, using memory copies.
     * <br/>
     * 在内存中将{@link ByteBuf}合并为一个{@link ByteBuf}用以累积。会发生内存只不过{@link ByteBuf}块的复制行为，适用于小数据量情况。
     * <br/>
     * 如果 {@link #cumulation} 是空的并且 (ByteBuf)in 是由单个内存区域保存的，则直接返回 in 的引用即可。(ByteBuf有可能是由符合缓冲区组成，即链表状的多个内存块)
     * 否则判断 cumulation 剩余空间能否将 in 写入，如果能的话就直接将 in 写入到 cumulation 然后返回 cumulation。
     * 如果 cumulation 剩余空间不能将 in 写入，那么调用并返回 expandCumulation(alloc, cumulation, in)。
     * 除了直接返回 (ByteBuf)in 外，其他情况都将会释放掉 (ByteBuf)in。
     */
    public static final Cumulator MERGE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            if (!cumulation.isReadable() && in.isContiguous()) {
                // If cumulation is empty and input buffer is contiguous, use it directly
                cumulation.release();
                return in;
            }
            try {
                final int required = in.readableBytes();
                if (required > cumulation.maxWritableBytes() ||
                        (required > cumulation.maxFastWritableBytes() && cumulation.refCnt() > 1) ||
                        cumulation.isReadOnly()) {
                    // Expand cumulation (by replacing it) under the following conditions:
                    // - cumulation cannot be resized to accommodate the additional data
                    // - cumulation can be expanded with a reallocation operation to accommodate but the buffer is
                    //   assumed to be shared (e.g. refCnt() > 1) and the reallocation may not be safe.
                    return expandCumulation(alloc, cumulation, in);
                }
                cumulation.writeBytes(in, in.readerIndex(), required);
                in.readerIndex(in.writerIndex());
                return cumulation;
            } finally {
                // We must release in in all cases as otherwise it may produce a leak if writeBytes(...) throw
                // for whatever release (for example because of OutOfMemoryError)
                in.release();
            }
        }
    };

    /**
     * Cumulate {@link ByteBuf}s by add them to a {@link CompositeByteBuf} and so do no memory copy whenever possible.
     * Be aware that {@link CompositeByteBuf} use a more complex indexing implementation so depending on your use-case
     * and the decoder implementation this may be slower then just use the {@link #MERGE_CUMULATOR}.
     * <br/>
     * 通过将{@link ByteBuf}添加到{@link CompositeByteBuf}来累积{@link ByteBuf}，因此尽可能不要复制内存（适用于数据量大的情况）。
     * 应注意：{@link CompositeByteBuf}使用了更复杂的索引实现，因此根据你的用例和解码器实现，这可能比使用{@link #MERGE_CUMULATOR}要慢。
     * <br/>
     * 由于该累积器会将 (ByteBuf)in 和 cumulation 通过使用索引拼接起来。所以in不会被释放(除了拼接异常)。
     */
    public static final Cumulator COMPOSITE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            if (!cumulation.isReadable()) {
                cumulation.release();
                return in;
            }
            CompositeByteBuf composite = null;
            try {
                if (cumulation instanceof CompositeByteBuf && cumulation.refCnt() == 1) {
                    composite = (CompositeByteBuf) cumulation;
                    // Writer index must equal capacity if we are going to "write"
                    // new components to the end
                    if (composite.writerIndex() != composite.capacity()) {
                        composite.capacity(composite.writerIndex());
                    }
                } else {
                    composite = alloc.compositeBuffer(Integer.MAX_VALUE).addFlattenedComponents(true, cumulation);
                }
                composite.addFlattenedComponents(true, in);
                in = null;
                return composite;
            } finally {
                if (in != null) {
                    // We must release if the ownership was not transferred as otherwise it may produce a leak
                    in.release();
                    // Also release any new buffer allocated if we're not returning it
                    if (composite != null && composite != cumulation) {
                        composite.release();
                    }
                }
            }
        }
    };

    /**
     * 当本handler处于正常工作状态时，处于本标志位。
     */
    private static final byte STATE_INIT = 0;
    /**
     * 当子类的{@link #decode(ChannelHandlerContext, ByteBuf, List)}被调用时，
     * 本handler的状态会设置为该标志。
     */
    private static final byte STATE_CALLING_CHILD_DECODE = 1;
    /**
     * 当本handler被移除出 ChannelHandlerContext 是，
     * 本handler的状态会设置为该标志。
     */
    private static final byte STATE_HANDLER_REMOVED_PENDING = 2;

    ByteBuf cumulation;
    private Cumulator cumulator = MERGE_CUMULATOR;
    /**
     * 当该标志位 true 时，每次解码都将只会解码出一条消息。(实质上一次读取的数据里可能会解码出多条数据，这种情况下也仅仅解码出第一条数据)
     */
    private boolean singleDecode;
    /**
     * 当前接收到的数据是否是第一条，也即当前接收导数据的时候，{@link #cumulation} 是否为null。(也就是还没有积累过任何数据)
     */
    private boolean first;

    /**
     * This flag is used to determine if we need to call {@link ChannelHandlerContext#read()} to consume more data
     * when {@link ChannelConfig#isAutoRead()} is {@code false}.
     * <br/>
     * 该标志用于确定当{@link ChannelHandlerContext#read()}为{@code false}时，是否需要调用{@link ChannelHandlerContext#read()}来消耗更多数据。
     */
    private boolean firedChannelRead;

    /**
     * A bitmask where the bits are defined as
     * <ul>
     *     <li>{@link #STATE_INIT}</li>
     *     <li>{@link #STATE_CALLING_CHILD_DECODE}</li>
     *     <li>{@link #STATE_HANDLER_REMOVED_PENDING}</li>
     * </ul>
     * 状态位
     */
    private byte decodeState = STATE_INIT;
    /**
     * 指定最多读取次数，当到达该值时当前消息还未读取完成，将调用 cumulation 的 {@link ByteBuf#discardSomeReadBytes()} 方法处理已经读取的数据。
     */
    private int discardAfterReads = 16;
    /**
     * 已读取的次数，与 discardAfterReads 配合使用。
     */
    private int numReads;

    protected ByteToMessageDecoder() {
        ensureNotSharable();
    }

    /**
     * If set then only one message is decoded on each {@link #channelRead(ChannelHandlerContext, Object)} call.
     * This may be useful if you need to do some protocol upgrade and want to make sure nothing is mixed up.
     * <br/>
     * 如果设置了，那么在每次{@link #channelRead(ChannelHandlerContext, Object)}调用中只有一条消息被解码。
     * 如果您需要进行一些协议升级，并希望确保没有混淆，那么这可能很有用。
     *
     * Default is {@code false} as this has performance impacts.
     */
    public void setSingleDecode(boolean singleDecode) {
        this.singleDecode = singleDecode;
    }

    /**
     * If {@code true} then only one message is decoded on each
     * {@link #channelRead(ChannelHandlerContext, Object)} call.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public boolean isSingleDecode() {
        return singleDecode;
    }

    /**
     * Set the {@link Cumulator} to use for cumulate the received {@link ByteBuf}s.
     */
    public void setCumulator(Cumulator cumulator) {
        this.cumulator = ObjectUtil.checkNotNull(cumulator, "cumulator");
    }

    /**
     * Set the number of reads after which {@link ByteBuf#discardSomeReadBytes()} are called and so free up memory.
     * The default is {@code 16}.
     */
    public void setDiscardAfterReads(int discardAfterReads) {
        checkPositive(discardAfterReads, "discardAfterReads");
        this.discardAfterReads = discardAfterReads;
    }

    /**
     * Returns the actual number of readable bytes in the internal cumulative
     * buffer of this decoder. You usually do not need to rely on this value
     * to write a decoder. Use it only when you must use it at your own risk.
     * This method is a shortcut to {@link #internalBuffer() internalBuffer().readableBytes()}.
     */
    protected int actualReadableBytes() {
        return internalBuffer().readableBytes();
    }

    /**
     * Returns the internal cumulative buffer of this decoder. You usually
     * do not need to access the internal buffer directly to write a decoder.
     * Use it only when you must use it at your own risk.
     * <br/>
     * 返回此解码器的内部累积缓冲区。你通常不需要直接访问内部缓冲区来编写解码器。
     * 请在必须自行承担风险的情况下使用。
     */
    protected ByteBuf internalBuffer() {
        if (cumulation != null) {
            return cumulation;
        } else {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    /**
     * 对于重写方法，会注释重写的逻辑。。。
     * 从实际上下文中删除{@link ChannelHandler}后调用，它不再处理事件。
     * <p>
     * 本方法被调用时，首先判断本handler是否在执行子类重写的解码逻辑方法{@link #decode(ChannelHandlerContext, ByteBuf, List)}。
     * <br/>
     * 如果是的话仅仅将本handler的状态标记为{@link #STATE_HANDLER_REMOVED_PENDING}则返回。
     * (因为本handler在执行完子类重写的解码逻辑方法后，会判断一次本handler的状态，如果已经被移除，则会调用本方法。)
     * <br/>
     * 如果不是则清除{@link #cumulation}中持有的数据。
     * 然后调用模板方法{@link #handlerRemoved0(ChannelHandlerContext)}执行子类的处理逻辑。
     *
     *
     * @param ctx
     * @throws Exception
     */
    @Override
    public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        if (decodeState == STATE_CALLING_CHILD_DECODE) {
            decodeState = STATE_HANDLER_REMOVED_PENDING;
            return;
        }
        ByteBuf buf = cumulation;
        if (buf != null) {
            // Directly set this to null so we are sure we not access it in any other method here anymore.
            cumulation = null;
            numReads = 0;
            int readable = buf.readableBytes();
            if (readable > 0) {
                ctx.fireChannelRead(buf);
                ctx.fireChannelReadComplete();
            } else {
                buf.release();
            }
        }
        handlerRemoved0(ctx);
    }

    /**
     * Gets called after the {@link ByteToMessageDecoder} was removed from the actual context and it doesn't handle
     * events anymore.
     * 模板方法，允许子类在本handler被移除时执行某些逻辑。
     */
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception { }

    /**
     * 在该实现中：
     * <ol>
     * <li>如果msg为ByteBuf类型的话，使用累加器{@link #cumulator}将msg积累到{@link #cumulation}中。</li>
     * <li>然后调用解码逻辑尝试从{@link #cumulation}中解码出消息并放入到(List)out中。</li>
     * <li>接着对{@link #cumulation}进行清理/整理工作。</li>
     * <li>最终根据(List)out中的消息条数调用相应次数的后续 InBoundHandler 的 fireChannelRead 方法。</li>
     * <li>也就是说如果(List)out中一条解码出来的消息都没有，那么就不会调用后续 InBoundHandler 的 fireChannelRead 方法。</li>
     * </ol>
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            CodecOutputList out = CodecOutputList.newInstance();
            try {
                first = cumulation == null;
                // 使用累加器{@link #cumulator}将msg积累到{@link #cumulation}中。
                cumulation = cumulator.cumulate(ctx.alloc(), first ? Unpooled.EMPTY_BUFFER : cumulation, (ByteBuf) msg);
                // 调用解码逻辑尝试从{@link #cumulation}中解码出消息并放入到(List)out中。
                callDecode(ctx, cumulation, out);
            } catch (DecoderException e) {
                throw e;
            } catch (Exception e) {
                throw new DecoderException(e);
            } finally {
                try {
                    // 对{@link #cumulation}进行清理/整理工作。
                    if (cumulation != null && !cumulation.isReadable()) {
                        numReads = 0;
                        cumulation.release();
                        cumulation = null;
                    } else if (++numReads >= discardAfterReads) {
                        // We did enough reads already try to discard some bytes so we not risk to see a OOME.
                        // See https://github.com/netty/netty/issues/4275
                        numReads = 0;
                        discardSomeReadBytes();
                    }

                    // 获取解码出来的消息条数
                    int size = out.size();
                    firedChannelRead |= out.insertSinceRecycled();
                    // 根据解码出来的消息条数来触发相应次数后续 InBoundHandler 的 fireChannelRead 方法。
                    fireChannelRead(ctx, out, size);
                } finally {
                    out.recycle();
                }
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Get {@code numElements} out of the {@link List} and forward these through the pipeline.
     * 从{@link List}中获取{@code numElements}条数据，并通过 Pipeline 转发它们到下一个 handler。
     */
    static void fireChannelRead(ChannelHandlerContext ctx, List<Object> msgs, int numElements) {
        if (msgs instanceof CodecOutputList) {
            fireChannelRead(ctx, (CodecOutputList) msgs, numElements);
        } else {
            for (int i = 0; i < numElements; i++) {
                ctx.fireChannelRead(msgs.get(i));
            }
        }
    }

    /**
     * Get {@code numElements} out of the {@link CodecOutputList} and forward these through the pipeline.
     * 从{@link CodecOutputList}中获取{@code numElements}条数据，并通过 Pipeline 转发它们到下一个 handler。
     */
    static void fireChannelRead(ChannelHandlerContext ctx, CodecOutputList msgs, int numElements) {
        for (int i = 0; i < numElements; i ++) {
            ctx.fireChannelRead(msgs.getUnsafe(i));
        }
    }

    /**
     * 这个方法没什么好说的，就是调用{@link #discardSomeReadBytes()}整理下缓冲区。
     * 然后顺便触发下游(后续InboundHandler)的channelReadComplete方法。
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        numReads = 0;
        discardSomeReadBytes();
        if (!firedChannelRead && !ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
        firedChannelRead = false;
        ctx.fireChannelReadComplete();
    }

    /**
     * 整理{@link #cumulation}的空间数据分布。
     */
    protected final void discardSomeReadBytes() {
        if (cumulation != null && !first && cumulation.refCnt() == 1) {
            // discard some bytes if possible to make more room in the
            // buffer but only if the refCnt == 1  as otherwise the user may have
            // used slice().retain() or duplicate().retain().
            //
            // See:
            // - https://github.com/netty/netty/issues/2327
            // - https://github.com/netty/netty/issues/1764
            cumulation.discardSomeReadBytes();
        }
    }

    /**
     * 在关闭Channel前，调用{@link #channelInputClosed(ChannelHandlerContext, boolean)}对未消费的数据进行消费。
     *
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelInputClosed(ctx, true);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent) {
            // The decodeLast method is invoked when a channelInactive event is encountered.
            // This method is responsible for ending requests in some situations and must be called
            // when the input has been shutdown.
            channelInputClosed(ctx, false);
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * 当要关闭Channel时。
     * <ol>
     * <li>该方法会将{@link #cumulation}残留的数据解码为消息放到(List)out中。</li>
     * <li>然后清理{@link #cumulation}。</li>
     * <li>接着根据(List)out中的消息条数调用相应次数的后续 InBoundHandler 的 fireChannelRead 方法。</li>
     * </ol>
     * @param ctx
     * @param callChannelInactive
     */
    private void channelInputClosed(ChannelHandlerContext ctx, boolean callChannelInactive) {
        CodecOutputList out = CodecOutputList.newInstance();
        try {
            // 该方法会最后一次前方(有可能时传输层缓冲区，也有可能是管道中本handler的前一个handler)读取数据。
            // 并把数据解码为消息放到(List)out中。
            channelInputClosed(ctx, out);
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        } finally {
            try {
                // 清理{@link #cumulation}
                if (cumulation != null) {
                    cumulation.release();
                    cumulation = null;
                }
                // 获取解码出来的消息数量
                int size = out.size();
                // 调用相应次数的后续fireChannelRead方法。
                fireChannelRead(ctx, out, size);
                if (size > 0) {
                    // Something was read, call fireChannelReadComplete()
                    ctx.fireChannelReadComplete();
                }
                if (callChannelInactive) {
                    ctx.fireChannelInactive();
                }
            } finally {
                // Recycle in all cases
                out.recycle();
            }
        }
    }

    /**
     * Called when the input of the channel was closed which may be because it changed to inactive or because of
     * {@link ChannelInputShutdownEvent}.
     * <br/>
     * 如果{@link #cumulation}由残留数据，将{@link #cumulation}中残留的数据解码成消息并放到(List)out中。
     */
    void channelInputClosed(ChannelHandlerContext ctx, List<Object> out) throws Exception {
        if (cumulation != null) {
            callDecode(ctx, cumulation, out);
            // If callDecode(...) removed the handle from the pipeline we should not call decodeLast(...) as this would
            // be unexpected.
            if (!ctx.isRemoved()) {
                // Use Unpooled.EMPTY_BUFFER if cumulation become null after calling callDecode(...).
                // See https://github.com/netty/netty/issues/10802.
                ByteBuf buffer = cumulation == null ? Unpooled.EMPTY_BUFFER : cumulation;
                decodeLast(ctx, buffer, out);
            }
        } else {
            decodeLast(ctx, Unpooled.EMPTY_BUFFER, out);
        }
    }

    /**
     * Called once data should be decoded from the given {@link ByteBuf}. This method will call
     * {@link #decode(ChannelHandlerContext, ByteBuf, List)} as long as decoding should take place.
     * <br/>
     * 一旦调用，数据应该从给定的{@link ByteBuf}解码。
     * 只要应该进行解码，该方法将调用{@link #decode(ChannelHandlerContext, ByteBuf, List)}。
     * <p>
     * 只要{@code in}里存在可消费的数据。
     * 首先将{@code out}的数据通过方法{@link #fireChannelRead(ChannelHandlerContext, List, int)}转发到下游handler中。
     * 然后调用方法{@link #decodeRemovalReentryProtection(ChannelHandlerContext, ByteBuf, List)}消费in的数据，并将解码出来的消息放到{@code out}中。
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     */
    protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            while (in.isReadable()) {
                // 获取可转发到后续handler的消息数量outSize
                final int outSize = out.size();
                // 如果 outSize 不为0则将消息转发到后续handler中，并清理(List)out
                if (outSize > 0) {
                    fireChannelRead(ctx, out, outSize);
                    out.clear();

                    // Check if this handler was removed before continuing with decoding.
                    // If it was removed, it is not safe to continue to operate on the buffer.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/4635
                    if (ctx.isRemoved()) {
                        break;
                    }
                }

                // 在对in解码前记录可读取的字节数
                int oldInputLength = in.readableBytes();
                // 将in里的数据解码为消息并存到out中
                decodeRemovalReentryProtection(ctx, in, out);

                // Check if this handler was removed before continuing the loop.
                // If it was removed, it is not safe to continue to operate on the buffer.
                //
                // See https://github.com/netty/netty/issues/1664
                if (ctx.isRemoved()) {
                    break;
                }

                if (out.isEmpty()) {
                    if (oldInputLength == in.readableBytes()) {
                        // 如果没能从in中解码出任何数据，
                        // 并且in里的数据没有被decodeRemovalReentryProtection(ctx, in, out)方法消费过，
                        // 此时说明in里面的数据由于解码逻辑问题不能被解码为消息(有可能是这次读取的数据量不够，需要再从传输层多拿点)，
                        // 那么不再尝试解码。
                        break;
                    } else {
                        // 如果没能从in中解码出任何数据，
                        // 但是in里的部分数据被decodeRemovalReentryProtection(ctx, in, out)方法消费，
                        // 那么说明可能由于各种原因decodeRemovalReentryProtection(ctx, in, out)方法没能消费完in里的数据，
                        // (比如解码逻辑每次只消费10字节数据，而想要生成一条消息需要20字节，所以就要循环两次才能解码出消息)
                        // 此时继续循环消费in的数据。
                        continue;
                    }
                }

                // 如果out不为空，但是却没有消费in里面数据，那么说明解码的逻辑可能出错了。
                if (oldInputLength == in.readableBytes()) {
                    throw new DecoderException(
                            StringUtil.simpleClassName(getClass()) +
                                    ".decode() did not read anything but decoded a message.");
                }

                // 如果对一个入参in，只解码一次则break
                if (isSingleDecode()) {
                    break;
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Exception cause) {
            throw new DecoderException(cause);
        }
    }

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     * <p>
     * 这是你必须实现的唯一抽象方法。 decode()方法被调用时将会传
     * 入一个包含了传入数据的 ByteBuf，以及一个用来添加解码消息
     * 的 List。对这个方法的调用将会重复进行，直到确定没有新的元
     * 素被添加到该 List，或者该 ByteBuf 中没有更多可读取的字节
     * 时为止。然后，如果该 List 不为空，那么它的内容将会被传递给
     * ChannelPipeline 中的下一个 ChannelInboundHandler
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     * @throws Exception    is thrown if an error occurs
     */
    protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     * <p>
     * 这个方法会调用子类的解码逻辑方法{@link #decode(ChannelHandlerContext, ByteBuf, List)}将入参{@code in}解码成消息并放入(List)out中。
     * 在调用子类的解码逻辑方法前，会将编码器的状态设置为 {@link #STATE_CALLING_CHILD_DECODE}。
     * 当调用返回后，将会判断当前handler是否在调用子类解码逻辑方法时被移出 ChannelHandlerContext。
     * 如果已经被移除，那么本handler将解码的消息传递给后续的handler后执行{@link #handlerRemoved(ChannelHandlerContext)}。
     *
     * 若{@code in}的数据能被解码成消息，那么会导致{@code in.readableBytes()}的返回值变化！！！！
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     * @throws Exception    is thrown if an error occurs
     */
    final void decodeRemovalReentryProtection(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        decodeState = STATE_CALLING_CHILD_DECODE;
        try {
            decode(ctx, in, out);
        } finally {
            boolean removePending = decodeState == STATE_HANDLER_REMOVED_PENDING;
            decodeState = STATE_INIT;
            if (removePending) {
                fireChannelRead(ctx, out, out.size());
                out.clear();
                handlerRemoved(ctx);
            }
        }
    }

    /**
     * Is called one last time when the {@link ChannelHandlerContext} goes in-active.
     * Which means the {@link #channelInactive(ChannelHandlerContext)} was triggered.
     * 在{@link ChannelHandlerContext}处于活动状态时最后一次调用。
     * 这意味着触发了{@link #channelInactive(ChannelHandlerContext)}。
     *
     * By default this will just call {@link #decode(ChannelHandlerContext, ByteBuf, List)} but sub-classes may
     * override this for some special cleanup operation.
     * 默认情况下仅仅会调用{@link #decode(ChannelHandlerContext, ByteBuf, List)}方法，但子类可能会重写这个方法来进行一些特殊的清理。
     */
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.isReadable()) {
            // Only call decode() if there is something left in the buffer to decode.
            // See https://github.com/netty/netty/issues/4386
            decodeRemovalReentryProtection(ctx, in, out);
        }
    }

    /**
     * 根据{@code oldCumulation}和{@code in}的可读数据长度来申请一个新的缓冲区。
     * 并将{@code oldCumulation}和{@code in}复制到新的缓冲区然后返回。
     */
    static ByteBuf expandCumulation(ByteBufAllocator alloc, ByteBuf oldCumulation, ByteBuf in) {
        // 获取oldCumulation的可读数据长度
        int oldBytes = oldCumulation.readableBytes();
        // 获取in的可读数据长度
        int newBytes = in.readableBytes();
        // 计算出总长度
        int totalBytes = oldBytes + newBytes;
        // 根据总长度申请缓冲区
        ByteBuf newCumulation = alloc.buffer(alloc.calculateNewCapacity(totalBytes, MAX_VALUE));
        ByteBuf toRelease = newCumulation;
        try {
            // 将 oldCumulation 的数据和 in 里的数据拷贝到新申请的缓冲区中
            // This avoids redundant checks and stack depth compared to calling writeBytes(...)
            newCumulation.setBytes(0, oldCumulation, oldCumulation.readerIndex(), oldBytes)
                    .setBytes(oldBytes, in, in.readerIndex(), newBytes)
                    .writerIndex(totalBytes);
            in.readerIndex(in.writerIndex());
            toRelease = oldCumulation;
            return newCumulation;
        } finally {
            toRelease.release();
        }
    }

    /**
     * Cumulate {@link ByteBuf}s.
     */
    public interface Cumulator {
        /**
         * Cumulate the given {@link ByteBuf}s and return the {@link ByteBuf} that holds the cumulated bytes.
         * The implementation is responsible to correctly handle the life-cycle of the given {@link ByteBuf}s and so
         * call {@link ByteBuf#release()} if a {@link ByteBuf} is fully consumed.
         */
        ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in);
    }
}