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

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Handles an I/O event or intercepts an I/O operation, and forwards it to its next handler in
 * its {@link ChannelPipeline}.
 *
 * <h3>Sub-types</h3>
 * <p>
 * {@link ChannelHandler} itself does not provide many methods, but you usually have to implement one of its subtypes:
 * <ul>
 * <li>{@link ChannelInboundHandler} to handle inbound I/O events, and</li>
 * <li>{@link ChannelOutboundHandler} to handle outbound I/O operations.</li>
 * </ul>
 * </p>
 * <p>
 * Alternatively, the following adapter classes are provided for your convenience:
 * <ul>
 * <li>{@link ChannelInboundHandlerAdapter} to handle inbound I/O events,</li>
 * <li>{@link ChannelOutboundHandlerAdapter} to handle outbound I/O operations, and</li>
 * <li>{@link ChannelDuplexHandler} to handle both inbound and outbound events</li>
 * </ul>
 * </p>
 * <p>
 * For more information, please refer to the documentation of each subtype.
 * </p>
 *
 * <h3>The context object</h3>
 * <p>
 * A {@link ChannelHandler} is provided with a {@link ChannelHandlerContext}
 * object.  A {@link ChannelHandler} is supposed to interact with the
 * {@link ChannelPipeline} it belongs to via a context object.  Using the
 * context object, the {@link ChannelHandler} can pass events upstream or
 * downstream, modify the pipeline dynamically, or store the information
 * (using {@link AttributeKey}s) which is specific to the handler.
 *
 * <h3>State management</h3>
 *
 * A {@link ChannelHandler} often needs to store some stateful information.
 * The simplest and recommended approach is to use member variables:
 * <pre>
 * public interface Message {
 *     // your methods here
 * }
 *
 * public class DataServerHandler extends {@link SimpleChannelInboundHandler}&lt;Message&gt; {
 *
 *     <b>private boolean loggedIn;</b>
 *
 *     {@code @Override}
 *     public void channelRead0({@link ChannelHandlerContext} ctx, Message message) {
 *         if (message instanceof LoginMessage) {
 *             authenticate((LoginMessage) message);
 *             <b>loggedIn = true;</b>
 *         } else (message instanceof GetDataMessage) {
 *             if (<b>loggedIn</b>) {
 *                 ctx.writeAndFlush(fetchSecret((GetDataMessage) message));
 *             } else {
 *                 fail();
 *             }
 *         }
 *     }
 *     ...
 * }
 * </pre>
 * Because the handler instance has a state variable which is dedicated to
 * one connection, you have to create a new handler instance for each new
 * channel to avoid a race condition where a unauthenticated client can get
 * the confidential information:
 * <pre>
 * // Create a new handler instance per channel.
 * // See {@link ChannelInitializer#initChannel(Channel)}.
 * public class DataServerInitializer extends {@link ChannelInitializer}&lt;{@link Channel}&gt; {
 *     {@code @Override}
 *     public void initChannel({@link Channel} channel) {
 *         channel.pipeline().addLast("handler", <b>new DataServerHandler()</b>);
 *     }
 * }
 *
 * </pre>
 *
 * <h4>Using {@link AttributeKey}s</h4>
 *
 * Although it's recommended to use member variables to store the state of a
 * handler, for some reason you might not want to create many handler instances.
 * In such a case, you can use {@link AttributeKey}s which is provided by
 * {@link ChannelHandlerContext}:
 * <pre>
 * public interface Message {
 *     // your methods here
 * }
 *
 * {@code @Sharable}
 * public class DataServerHandler extends {@link SimpleChannelInboundHandler}&lt;Message&gt; {
 *     private final {@link AttributeKey}&lt;{@link Boolean}&gt; auth =
 *           {@link AttributeKey#valueOf(String) AttributeKey.valueOf("auth")};
 *
 *     {@code @Override}
 *     public void channelRead({@link ChannelHandlerContext} ctx, Message message) {
 *         {@link Attribute}&lt;{@link Boolean}&gt; attr = ctx.attr(auth);
 *         if (message instanceof LoginMessage) {
 *             authenticate((LoginMessage) o);
 *             <b>attr.set(true)</b>;
 *         } else (message instanceof GetDataMessage) {
 *             if (<b>Boolean.TRUE.equals(attr.get())</b>) {
 *                 ctx.writeAndFlush(fetchSecret((GetDataMessage) o));
 *             } else {
 *                 fail();
 *             }
 *         }
 *     }
 *     ...
 * }
 * </pre>
 * Now that the state of the handler is attached to the {@link ChannelHandlerContext}, you can add the
 * same handler instance to different pipelines:
 * <pre>
 * public class DataServerInitializer extends {@link ChannelInitializer}&lt;{@link Channel}&gt; {
 *
 *     private static final DataServerHandler <b>SHARED</b> = new DataServerHandler();
 *
 *     {@code @Override}
 *     public void initChannel({@link Channel} channel) {
 *         channel.pipeline().addLast("handler", <b>SHARED</b>);
 *     }
 * }
 * </pre>
 *
 *
 * <h4>The {@code @Sharable} annotation</h4>
 * <p>
 * In the example above which used an {@link AttributeKey},
 * you might have noticed the {@code @Sharable} annotation.
 * <p>
 * If a {@link ChannelHandler} is annotated with the {@code @Sharable}
 * annotation, it means you can create an instance of the handler just once and
 * add it to one or more {@link ChannelPipeline}s multiple times without
 * a race condition.
 * <p>
 * If this annotation is not specified, you have to create a new handler
 * instance every time you add it to a pipeline because it has unshared state
 * such as member variables.
 * <p>
 * This annotation is provided for documentation purpose, just like
 * <a href="http://www.javaconcurrencyinpractice.com/annotations/doc/">the JCIP annotations</a>.
 *
 * <h3>Additional resources worth reading</h3>
 * <p>
 * Please refer to the {@link ChannelHandler}, and
 * {@link ChannelPipeline} to find out more about inbound and outbound operations,
 * what fundamental differences they have, how they flow in a  pipeline,  and how to handle
 * the operation in your application.
 *
 * 可以将它看作是处理往来 ChannelPipeline 事件（包括数据）的任何代码的通用容器。
 * 其有两个子接口 ChannelInboundHandler、ChannelOutboundHandler。
 *
 *  网 --> 传 --> 套 --> ChannelInboundHandler  -->  | 应  用
 *  络     输     接                                 |
 *  层 <-- 层 <-- 字 <-- ChannelOutboundHandler <--  | 程  序
 *                      |<- ChannelPipeline ->|
 *                     头部                   尾部
 *
 * {@link  ChannelHandler}中定义了一些生命周期操作：
 *   handlerAdded   | 当把 ChannelHandler 添加到 ChannelPipeline 中时被调用
 *   handlerRemoved | 当从 ChannelPipeline 中移除 ChannelHandler 时被调用
 *   exceptionCaught| 当处理过程中在 ChannelPipeline 中有错误产生时被调用
 *
 * <h1>如何理解Netty中的Inbound和Outbound</h1>
 *
 * <h2>错误的理解</h2>
 * ChannelInboundHandler、ChannelOutboundHandler，这里的inbound和outbound是
 * 什么意思呢？inbound对应IO输入，outbound对应IO输出？这是我看到这两个名字时的第一反
 * 应，但当我看到ChannelOutboundHandler接口中有read方法时，就开始疑惑了，应该是理解
 * 错了，如果outbound对应IO输出，为什么这个接口里会有明显表示IO输入的read方法呢？
 *
 * <h2>正确的理解</h2>
 * <a href="https://stackoverflow.com/questions/22354135/in-netty4-why-read-and-write-both-in-outboundhandler">Netty作者Trustin Lee对inbound和outbound的解释</a>
 * 众所周知，Netty是事件驱动的，而事件分为两大类：inboud和outbound，分别由ChannelInboundHandler
 * 和ChannelOutboundHandler负责处理。所以，inbound和outbound并非指IO的输入和输出，而是指事件类型。
 * 那么什么样的事件属于inbound，什么样的事件属于outbound呢？也就是说，事件类型的划分依据是什么？
 * 答案是：触发事件的源头。
 *
 * <h2>Inbound</h2>
 * 由外部触发的事件是inbound事件。外部是指应用程序之外，因此inbound事件就是并非因为应用
 * 程序主动请求做了什么而触发的事件，比如某个socket上有数据读取进来了（注意是“读完了”这个
 * 事件，而不是“读取”这个操作），再比如某个socket连接了上来并被注册到了某个EventLoop。
 * 即，这个事件是由操作系统或者当前应用层外的某些东西告知应用层的。
 * Inbound事件的详细列表：
 * <ul>
 *     <li/>channelActive / channelInactive
 *     <li/>channelRead
 *     <li/>channelReadComplete
 *     <li/>channelRegistered / channelUnregistered
 *     <li/>channelWritabilityChanged
 *     <li/>exceptionCaught
 *     <li/>userEventTriggered
 * </ul>
 *
 * <h2>Outbound</h2>
 * 而outbound事件是由应用程序主动请求而触发的事件，可以认为，outbound是指应用程序发起了
 * 某个操作。比如向socket写入数据，再比如从socket读取数据（注意是“读取”这个操作请求，而
 * 非“读完了”这个事件），这也解释了为什么ChannelOutboundHandler中会有read方法。即，这
 * 个事件是有本应用主动发起的。
 * <ul>
 *     <li/>bind
 *     <li/>close
 *     <li/>connect
 *     <li/>deregister
 *     <li/>disconnect
 *     <li/>flush
 *     <li/>read
 *     <li/>write
 * </ul>
 * 大都是在socket上可以执行的一系列常见操作：绑定地址、建立和关闭连接、IO操作，另外还有Netty
 * 定义的一种操作deregister：解除channel与eventloop的绑定关系。值得注意的是，一旦应用程序
 * 发出以上操作请求，ChannelOutboundHandler中对应的方法就会被调用，而不是等到操作完毕之后才
 * 被调用，一个handler在处理时甚至可以将请求拦截而不再传递给后续的handler，使得真正的操作并不
 * 会被执行。
 *
 * <h2>Inbound events & Outbound operations</h2>
 * 以上对inbound和outbound的理解从ChannelPipeline的Javadoc中也可以得到佐证：
 * A list of ChannelHandlers which handles or intercepts inbound events and
 * outbound operations of a Channel. ChannelPipeline implements an advanced
 * form of the Intercepting Filter pattern to give a user full control over
 * how an event is handled and how the ChannelHandlers in a pipeline interact
 * with each other.
 * 重点在于inbound events和outbound operations，即inbound是事件，outbound是操作（直接导致的事件）。
 *
 */
public interface ChannelHandler {

    /**
     * Gets called after the {@link ChannelHandler} was added to the actual context and it's ready to handle events.
     */
    void handlerAdded(ChannelHandlerContext ctx) throws Exception;

    /**
     * Gets called after the {@link ChannelHandler} was removed from the actual context and it doesn't handle events
     * anymore.
     * <br/>
     * 从实际上下文中删除{@link ChannelHandler}后调用，它不再处理事件。
     */
    void handlerRemoved(ChannelHandlerContext ctx) throws Exception;

    /**
     * Gets called if a {@link Throwable} was thrown.
     *
     * @deprecated if you want to handle this event you should implement {@link ChannelInboundHandler} and
     * implement the method there.
     */
    @Deprecated
    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception;

    /**
     * Indicates that the same instance of the annotated {@link ChannelHandler}
     * can be added to one or more {@link ChannelPipeline}s multiple times
     * without a race condition.
     * <p>
     * If this annotation is not specified, you have to create a new handler
     * instance every time you add it to a pipeline because it has unshared
     * state such as member variables.
     * <p>
     * This annotation is provided for documentation purpose, just like
     * <a href="http://www.javaconcurrencyinpractice.com/annotations/doc/">the JCIP annotations</a>.
     */
    @Inherited
    @Documented
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Sharable {
        // no value
    }
}