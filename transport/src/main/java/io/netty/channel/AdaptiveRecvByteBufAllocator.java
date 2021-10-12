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

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 * <p>
 * 使用动态缓冲区分配器{@link AdaptiveRecvByteBufAllocator}的优点如下：
 * <p>
 * Netty 作为一个通用的 NIO 框架，并不对客户的应用场景进行假设，你可以使用它做流媒体传输，
 * 也可使用它做聊天工具，不同的应用场景，传输的码流千差万别；无论初始分配的是32K还是1M，都
 * 会随着应用场景的变化而变得不适应，因此，Netty 将根据上次实际读取的码流大小对下次的接收
 * Buffer 缓冲区进行预测和调整，能够最大限度的满足不同行业的应用场景。
 * <p>
 * 性能更高，容量过大会导致内存开销增加，后续的 Buffer 处理性能会下降；容量过小时需要频繁
 * 的内存扩张来接受大的请求消息，同样会导致性能下降。
 * <p>
 * 更节约内存：摄像，加入通常情况下请求消息平均值为1M左右，接收缓冲区大小为1.2M；突然某个
 * 客户发送了一个10M的流媒体附件，接收缓冲区扩张为10M以接纳该附件，如果缓冲区不能收缩，每
 * 次缓冲区创建都会分配10M内存，但是后续的消息都是1M左右，这样会导致内存浪费而且还性能下降。
 *
 *
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    /**
     * 缓冲区最小容量64字节
     */
    static final int DEFAULT_MINIMUM = 64;
    /**
     * 初始容量2048，该值故意设置为比MTU(1500)大。
     * Use an initial value that is bigger than the common MTU of 1500.
     */
    static final int DEFAULT_INITIAL = 2048;
    /**
     * 缓冲区最大容量65536字节
     */
    static final int DEFAULT_MAXIMUM = 65536;

    // 这是两个动态调整容量时的步进参数
    /**
     * 扩张的步进索引为4
     */
    private static final int INDEX_INCREMENT = 4;
    /**
     * 收缩的步进所以为1
     */
    private static final int INDEX_DECREMENT = 1;

    /**
     * 长度向量表。
     * 对于数组索引 i 处于 [0, 31]，SIZE_TABLE[i] = 16 * i;
     * 对于数组索引 i 处于 [32, 52]，SIZE_TABLE[i] = 512 << (i-31);
     *
     * 值如此设计的原因在于：
     * 当容量小于512时，由于缓冲区比较小，即使发生容量变换代价也不高，所以降低步进值，每次容量的调整幅度小一些。
     * 当大于512时，说明需要解码的消息码流比较大，发生容量变换代价的代价较高，这时采用调大步进值的方式减少动态夸张的频率。
     */
    private static final int[] SIZE_TABLE;

    /**
     * 初始化SIZE_TABLE
     */
    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        // Suppress a warning since i becomes negative when an integer overflow happens
        for (int i = 512; i > 0; i <<= 1) { // lgtm[java/constant-comparison]
            sizeTable.add(i);
        }

        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    /**
     * 根据指定的{@code size}在{@link #SIZE_TABLE}获取比{@code size}大的最小容量的索引。
     *
     * @param size
     * @return
     */
    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        /**
         * 向量表的最小索引
         */
        private final int minIndex;
        /**
         * 向量表的最大索引
         */
        private final int maxIndex;
        /**
         * 当前索引
         */
        private int index;
        /**
         * 下次与分配的Buffer大小
         */
        private int nextReceiveBufferSize;
        /**
         * 是否立即执行容量收缩操作
         */
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            index = getSizeTableIndex(initial);
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            // 如果我们读了足够多的内容，我们应该检查是否需要增加下一个猜测的大小。当大量数据挂起时，
            // 这有助于更快地调整，并可以避免返回选择器来检查更多数据。返回选择器会增加很大的延迟数据传输。
            if (bytes == attemptedBytesRead()) {
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        /**
         * 当  NioSocketChannel 执行完读操作后，会计算获得本次轮询读取的总字节数，
         * 这个字节数就是入参{@code actualReadBytes}，本方法会根据实际读取的字节
         * 数对 ByteBuf 进行动态伸缩和扩容。
         *
         * @param actualReadBytes
         */
        private void record(int actualReadBytes) {
            // 尝试对当前索引做步进缩减，获取缩减后索引对应的容量，与实际读取的字节数 actualReadBytes 对比。
            // 如果 actualReadBytes 小于等于收缩后的容量，则重新对当前索引进行赋值，并根据 decreaseNow 来决定是否立即执行容量收缩操作。
            // 如果 actualReadBytes 大于收缩后的容量，则说明预分配的容量不足，需要动态扩容。
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                if (decreaseNow) {
                    index = max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
