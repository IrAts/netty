/*
 * Copyright 2020 The Netty Project
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
package io.netty.buffer;

import static io.netty.buffer.PoolThreadCache.*;

/**
 * SizeClasses requires {@code pageShifts} to be defined prior to inclusion,
 * and it in turn defines:
 * <p>
 *   LOG2_SIZE_CLASS_GROUP: Log of size class count for each size doubling.
 *   LOG2_MAX_LOOKUP_SIZE: Log of max size class in the lookup table.
 *   sizeClasses: Complete table of [index, log2Group, log2Delta, nDelta, isMultiPageSize,
 *                 isSubPage, log2DeltaLookup] tuples.
 *     index: Size class index.
 *     log2Group: Log of group base size (no deltas added).
 *     log2Delta: Log of delta to previous size class.
 *     nDelta: Delta multiplier.
 *     isMultiPageSize: 'yes' if a multiple of the page size, 'no' otherwise.
 *     isSubPage: 'yes' if a subpage size class, 'no' otherwise.
 *     log2DeltaLookup: Same as log2Delta if a lookup table size class, 'no'
 *                      otherwise.
 * <p>
 *   nSubpages: Number of subpages size classes.
 *   nSizes: Number of size classes.
 *   nPSizes: Number of size classes that are multiples of pageSize.
 *
 *   smallMaxSizeIdx: Maximum small size class index.
 *
 *   lookupMaxclass: Maximum size class included in lookup table.
 *   log2NormalMinClass: Log of minimum normal size class.
 * <p>
 *   The first size class and spacing are 1 << LOG2_QUANTUM.
 *   Each group has 1 << LOG2_SIZE_CLASS_GROUP of size classes.
 *
 *   size = 1 << log2Group + nDelta * (1 << log2Delta)
 *
 *   The first size class has an unusual encoding, because the size has to be
 *   split between group and delta*nDelta.
 *
 *   If pageShift = 13, sizeClasses looks like this:
 *
 *   (index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup)
 * <p>
 *   ( 0,     4,        4,         0,       no,             yes,        4)
 *   ( 1,     4,        4,         1,       no,             yes,        4)
 *   ( 2,     4,        4,         2,       no,             yes,        4)
 *   ( 3,     4,        4,         3,       no,             yes,        4)
 * <p>
 *   ( 4,     6,        4,         1,       no,             yes,        4)
 *   ( 5,     6,        4,         2,       no,             yes,        4)
 *   ( 6,     6,        4,         3,       no,             yes,        4)
 *   ( 7,     6,        4,         4,       no,             yes,        4)
 * <p>
 *   ( 8,     7,        5,         1,       no,             yes,        5)
 *   ( 9,     7,        5,         2,       no,             yes,        5)
 *   ( 10,    7,        5,         3,       no,             yes,        5)
 *   ( 11,    7,        5,         4,       no,             yes,        5)
 *   ...
 *   ...
 *   ( 72,    23,       21,        1,       yes,            no,        no)
 *   ( 73,    23,       21,        2,       yes,            no,        no)
 *   ( 74,    23,       21,        3,       yes,            no,        no)
 *   ( 75,    23,       21,        4,       yes,            no,        no)
 * <p>
 *   ( 76,    24,       22,        1,       yes,            no,        no)
 * <br/>
 *
 * <h2>详解</h2>
 *
 * {@link SizeClasses} 是一个极其重要类，它在内部维护一个二维数组，这个数组存储与内存规格有关的详细信息。
 * 这个二维数组的元数据格式为：[index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup]
 * 每一列的含义如下：
 *    ————————————————+————————————————————————————————————————————————————————————————————————————————————————————————
 *    index           |   由 0 开始的自增序列号，表示每个 size 类型的索引。
 *    ————————————————+————————————————————————————————————————————————————————————————————————————————————————————————
 *    log2Group       |   表示每个 size 它所对应的组。以每 4 行为一组，一共有 19 组。第 0 组比较特殊，它是单独初始化的。
 *                    |   因此，我们应该从第 1 组开始，起始值为 6，每组的 log2Group 是在上一组的值 +1。
 *    ————————————————+————————————————————————————————————————————————————————————————————————————————————————————————
 *                    |   表示当前序号所对应的 size 和前一个序号所对应的 size 的差值得 log2 的值。
 *    log2Delta       |   比如 index=6 对应的 size = 112，index=7 对应的 size= 128，
 *                    |   因此 index=7 的 log2Delta(7) = log2(128-112)=4。不知道你们有没有发现，其实log2Delta=log2Group-2 。
 *    ————————————————+————————————————————————————————————————————————————————————————————————————————————————————————
 *    nDelta          |   表示组内增量的倍数。第 0 组也是比较特殊，nDelta 是从 0 开始 + 1。而其余组是从 1 开始 +1。
 *    ————————————————+————————————————————————————————————————————————————————————————————————————————————————————————
 *    isMultiPageSize |   表示当前 size 是否是 pageSize（默认值: 8192） 的整数倍。后续会把 isMultiPageSize=1 的行单独整
 *                    |   理成一张表，你会发现有 40 个 isMultiPageSize=1 的行。
 *    ————————————————+————————————————————————————————————————————————————————————————————————————————————————————————
 *    isSubPage       |   表示当前 size 是否为一个 subPage 类型，jemalloc4 会根据这个值采取不同的内存分配策略。
 *    ————————————————+————————————————————————————————————————————————————————————————————————————————————————————————
 *    log2DeltaLookup | 当 index<=27 时，其值和 log2Delta 相等，当index>27，其值为 0。但是在代码中没有看到具体用来做什么。
 *    ————————————————+————————————————————————————————————————————————————————————————————————————————————————————————
 *
 * 有了上面的信息并不够，因为最想到得到的是 index 与 size 的对应关系。因此我们再添加两列信息[size, 能人看的size]。
 * 在 SizeClasses 表中，无论哪一行的 size 都是由 _size = (1 << log2Group) + nDelta * (1 << log2Delta)_ 公式计算得到。
 * 因此通过计算可得出每行的 size。故{@link SizeClasses}的二维数组所代表的表如下：
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | index  | log2Group  | log2Delta  | nDelta  | isMultiPageSize  | isSubPage  | log2DeltaLookup  | size(Byte)| usize  |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 0      | 4          | 4          | 0       | 0                | 1          | 4                | 16        |        |
 *    | 1      | 4          | 4          | 1       | 0                | 1          | 4                | 32        |        |
 *    | 2      | 4          | 4          | 2       | 0                | 1          | 4                | 48        |        |
 *    | 3      | 4          | 4          | 3       | 0                | 1          | 4                | 64        |        |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 4      | 6          | 4          | 1       | 0                | 1          | 4                | 80        |        |
 *    | 5      | 6          | 4          | 2       | 0                | 1          | 4                | 96        |        |
 *    | 6      | 6          | 4          | 3       | 0                | 1          | 4                | 112       |        |
 *    | 7      | 6          | 4          | 4       | 0                | 1          | 4                | 128       |        |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 8      | 7          | 5          | 1       | 0                | 1          | 5                | 160       |        |
 *    | 9      | 7          | 5          | 2       | 0                | 1          | 5                | 192       |        |
 *    | 10     | 7          | 5          | 3       | 0                | 1          | 5                | 224       |        |
 *    | 11     | 7          | 5          | 4       | 0                | 1          | 5                | 256       |        |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 12     | 8          | 6          | 1       | 0                | 1          | 6                | 320       |        |
 *    | 13     | 8          | 6          | 2       | 0                | 1          | 6                | 384       |        |
 *    | 14     | 8          | 6          | 3       | 0                | 1          | 6                | 448       |        |
 *    | 15     | 8          | 6          | 4       | 0                | 1          | 6                | 512       |        |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 16     | 9          | 7          | 1       | 0                | 1          | 7                | 640       |        |
 *    | 17     | 9          | 7          | 2       | 0                | 1          | 7                | 768       |        |
 *    | 18     | 9          | 7          | 3       | 0                | 1          | 7                | 896       |        |
 *    | 19     | 9          | 7          | 4       | 0                | 1          | 7                | 1024      | 1K     |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 20     | 10         | 8          | 1       | 0                | 1          | 8                | 1280      | 1.25K  |
 *    | 21     | 10         | 8          | 2       | 0                | 1          | 8                | 1536      | 1.5K   |
 *    | 22     | 10         | 8          | 3       | 0                | 1          | 8                | 1792      | 1.75K  |
 *    | 23     | 10         | 8          | 4       | 0                | 1          | 8                | 2048      | 2K     |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 24     | 11         | 9          | 1       | 0                | 1          | 9                | 2560      | 2.5K   |
 *    | 25     | 11         | 9          | 2       | 0                | 1          | 9                | 3072      | 3K     |
 *    | 26     | 11         | 9          | 3       | 0                | 1          | 9                | 3584      | 3.5K   |
 *    | 27     | 11         | 9          | 4       | 0                | 1          | 9                | 4096      | 4K     |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 28     | 12         | 10         | 1       | 0                | 1          | 0                | 5120      | 5K     |
 *    | 29     | 12         | 10         | 2       | 0                | 1          | 0                | 6144      | 6K     |
 *    | 30     | 12         | 10         | 3       | 0                | 1          | 0                | 7168      | 7K     |
 *    | 31     | 12         | 10         | 4       | 1                | 1          | 0                | 8192      | 8K     |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 32     | 13         | 11         | 1       | 0                | 1          | 0                | 10240     | 10K    |
 *    | 33     | 13         | 11         | 2       | 0                | 1          | 0                | 12288     | 12K    |
 *    | 34     | 13         | 11         | 3       | 0                | 1          | 0                | 14336     | 14K    |
 *    | 35     | 13         | 11         | 4       | 1                | 1          | 0                | 16384     | 16K    |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 36     | 14         | 12         | 1       | 0                | 1          | 0                | 20480     | 20K    |
 *    | 37     | 14         | 12         | 2       | 1                | 1          | 0                | 24576     | 24K    |
 *    | 38     | 14         | 12         | 3       | 0                | 1          | 0                | 28672     | 28K    |
 *    | 39     | 14         | 12         | 4       | 1                | 0          | 0                | 32768     | 32K    |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 40     | 15         | 13         | 1       | 1                | 0          | 0                | 40960     | 40K    |
 *    | 41     | 15         | 13         | 2       | 1                | 0          | 0                | 49152     | 48K    |
 *    | 42     | 15         | 13         | 3       | 1                | 0          | 0                | 57344     | 56K    |
 *    | 43     | 15         | 13         | 4       | 1                | 0          | 0                | 65536     | 64K    |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 44     | 16         | 14         | 1       | 1                | 0          | 0                | 81920     | 80K    |
 *    | 45     | 16         | 14         | 2       | 1                | 0          | 0                | 98304     | 96K    |
 *    | 46     | 16         | 14         | 3       | 1                | 0          | 0                | 114688    | 112K   |
 *    | 47     | 16         | 14         | 4       | 1                | 0          | 0                | 131072    | 128K   |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 48     | 17         | 15         | 1       | 1                | 0          | 0                | 163840    | 160K   |
 *    | 49     | 17         | 15         | 2       | 1                | 0          | 0                | 196608    | 192K   |
 *    | 50     | 17         | 15         | 3       | 1                | 0          | 0                | 229376    | 224K   |
 *    | 51     | 17         | 15         | 4       | 1                | 0          | 0                | 262144    | 256K   |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 52     | 18         | 16         | 1       | 1                | 0          | 0                | 327680    | 320K   |
 *    | 53     | 18         | 16         | 2       | 1                | 0          | 0                | 393216    | 384K   |
 *    | 54     | 18         | 16         | 3       | 1                | 0          | 0                | 458752    | 448K   |
 *    | 55     | 18         | 16         | 4       | 1                | 0          | 0                | 524288    | 512K   |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 56     | 19         | 17         | 1       | 1                | 0          | 0                | 655360    | 640K   |
 *    | 57     | 19         | 17         | 2       | 1                | 0          | 0                | 786432    | 768K   |
 *    | 58     | 19         | 17         | 3       | 1                | 0          | 0                | 917504    | 896K   |
 *    | 59     | 19         | 17         | 4       | 1                | 0          | 0                | 1048576   | 1M     |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 60     | 20         | 18         | 1       | 1                | 0          | 0                | 1310720   | 1.25M  |
 *    | 61     | 20         | 18         | 2       | 1                | 0          | 0                | 1572864   | 1.5M   |
 *    | 62     | 20         | 18         | 3       | 1                | 0          | 0                | 1835008   | 1.75M  |
 *    | 63     | 20         | 18         | 4       | 1                | 0          | 0                | 2097152   | 2M     |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 64     | 21         | 19         | 1       | 1                | 0          | 0                | 2621440   | 2.5M   |
 *    | 65     | 21         | 19         | 2       | 1                | 0          | 0                | 3145728   | 3M     |
 *    | 66     | 21         | 19         | 3       | 1                | 0          | 0                | 3670016   | 3.5M   |
 *    | 67     | 21         | 19         | 4       | 1                | 0          | 0                | 4194304   | 4M     |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 68     | 22         | 20         | 1       | 1                | 0          | 0                | 5242880   | 5M     |
 *    | 69     | 22         | 20         | 2       | 1                | 0          | 0                | 6291456   | 6M     |
 *    | 70     | 22         | 20         | 3       | 1                | 0          | 0                | 7340032   | 7M     |
 *    | 71     | 22         | 20         | 4       | 1                | 0          | 0                | 8388608   | 8M     |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *    | 72     | 23         | 21         | 1       | 1                | 0          | 0                | 10485760  | 10M    |
 *    | 73     | 23         | 21         | 2       | 1                | 0          | 0                | 12582912  | 12M    |
 *    | 74     | 23         | 21         | 3       | 1                | 0          | 0                | 14680064  | 14M    |
 *    | 75     | 23         | 21         | 4       | 1                | 0          | 0                | 16777216  | 16M    |
 *    +————————+————————————+————————————+—————————+——————————————————+————————————+——————————————————+———————————+————————+
 *
 * 从表中可以发现，不管对于哪种内存规格，它都有更细粒度的内存大小的划分。比如在 512Byte~8192Byte 范围内，
 * 可分为 512、640、768 等等，不再是 jemalloc3 只有 512、1024、2048 ... 这种粒度比较大的规格值了。
 * 这就是 jemalloc4 最大的提升。
 *
 *
 *
 * <h3>Netty内存规格</h3>
 *                                                    Netty内存规格
 *                                                         |
 *                              +——————————————————————————+——————————————+—————————————————+
 *                              |                                         |                 |
 *                            Small                                     Normal             Huge
 *                              |                                         |                 |
 *   +—————+—————+——————+———————+——————+————+—————+—————+        +—————+—————+—————+       +16M
 *   |     |     |      |       |      |    |     |     |        |     |     |     |
 *  16B   32B   ...   1024B   1280B   ...   8K   ...   28K      32K   ...   14M   16M
 *   \                                      ↑           /                          ↑
 *    \                                  pageSize      /                        chunkSize
 *      —————————————————— Subpage ———————————————————
 *                           39个
 *
 *
 * <h3>SizeClasses#pageIdx2sizeTab</h3>
 *
 * 抽取 SizeClasses 中 isMultiPageSize=1 的所有行组成下面的表格。每列表示含义解释如下:
 *    index: 对应 SizeClasses 的 index 列。
 *    size: 规格值。
 *    num of page: 包含多少个 page。
 *    对应 SizeClasses#pageIdx2SizeTab 的索引值。
 *    +————————+———————————+——————————————+——————————————————————————+
 *    | index  | size(Byte)| num of page  | index of pageIdx2sizeTab |
 *    +————————+———————————+——————————————+——————————————————————————+
 *    | 31     | 8192      | 1     	  	  | 0                        |
 *    | 35     | 16384     | 2    	  	  | 1                        |
 *    | 37     | 24576     | 3    	  	  | 2                        |
 *    | 39     | 32768     | 4    	  	  | 3                        |
 *    +————————+———————————+——————————————+——————————————————————————+
 *    | 40     | 40960     | 5    	  	  | 4                        |
 *    | 41     | 49152     | 6    	  	  | 5                        |
 *    | 42     | 57344     | 7    	  	  | 6                        |
 *    | 43     | 65536     | 8    	  	  | 7                        |
 *    +————————+———————————+——————————————+——————————————————————————+
 *    | 44     | 81920     | 10    	  	  | 8                        |
 *    | 45     | 98304     | 12    	  	  | 9                        |
 *    | 46     | 114688    | 14   	  	  | 10                       |
 *    | 47     | 131072    | 16   	  	  | 11                       |
 *    +————————+———————————+——————————————+——————————————————————————+
 *    | 48     | 163840    | 20   	  	  | 12                       |
 *    | 49     | 196608    | 24   	  	  | 13                       |
 *    | 50     | 229376    | 28   	  	  | 14                       |
 *    | 51     | 262144    | 32   	  	  | 15                       |
 *    +————————+———————————+——————————————+——————————————————————————+
 *    | 52     | 327680    | 40   	  	  | 16                       |
 *    | 53     | 393216    | 48   	  	  | 17                       |
 *    | 54     | 458752    | 56   	  	  | 18                       |
 *    | 55     | 524288    | 64   	  	  | 19                       |
 *    +————————+———————————+——————————————+——————————————————————————+
 *    | 56     | 655360    | 80   	  	  | 20                       |
 *    | 57     | 786432    | 96   	  	  | 21                       |
 *    | 58     | 917504    | 112   	      | 22                       |
 *    | 59     | 1048576   | 128     	  | 23                       |
 *    +————————+———————————+——————————————+——————————————————————————+
 *    | 60     | 1310720   | 160  	      | 24                       |
 *    | 61     | 1572864   | 192   	      | 25                       |
 *    | 62     | 1835008   | 224  	      | 26                       |
 *    | 63     | 2097152   | 256     	  | 27                       |
 *    +————————+———————————+——————————————+——————————————————————————+
 *    | 64     | 2621440   | 320     	  | 28                       |
 *    | 65     | 3145728   | 384     	  | 29                       |
 *    | 66     | 3670016   | 448     	  | 30                       |
 *    | 67     | 4194304   | 512     	  | 31                       |
 *    +————————+———————————+——————————————+——————————————————————————+
 *    | 68     | 5242880   | 640     	  | 32                       |
 *    | 69     | 6291456   | 768     	  | 33                       |
 *    | 70     | 7340032   | 896     	  | 34                       |
 *    | 71     | 8388608   | 1024    	  | 35                       |
 *    +————————+———————————+——————————————+——————————————————————————+
 *    | 72     | 10485760  | 1280    	  | 36                       |
 *    | 73     | 12582912  | 1536         | 37                       |
 *    | 74     | 14680064  | 1792         | 38                       |
 *    | 75     | 16777216  | 2048         | 39                       |
 *    +————————+———————————+——————————————+——————————————————————————+
 */
abstract class SizeClasses implements SizeClassesMetric {

    static final int LOG2_QUANTUM = 4;

    private static final int LOG2_SIZE_CLASS_GROUP = 2;
    private static final int LOG2_MAX_LOOKUP_SIZE = 12;

    private static final int INDEX_IDX = 0;
    private static final int LOG2GROUP_IDX = 1;
    private static final int LOG2DELTA_IDX = 2;
    private static final int NDELTA_IDX = 3;
    private static final int PAGESIZE_IDX = 4;
    private static final int SUBPAGE_IDX = 5;
    private static final int LOG2_DELTA_LOOKUP_IDX = 6;

    private static final byte no = 0, yes = 1;

    protected SizeClasses(int pageSize, int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        this.directMemoryCacheAlignment = directMemoryCacheAlignment;

        int group = log2(chunkSize) + 1 - LOG2_QUANTUM;

        //generate size classes
        //[index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup]
        sizeClasses = new short[group << LOG2_SIZE_CLASS_GROUP][7];
        nSizes = sizeClasses();

        //generate lookup table
        sizeIdx2sizeTab = new int[nSizes];
        pageIdx2sizeTab = new int[nPSizes];
        idx2SizeTab(sizeIdx2sizeTab, pageIdx2sizeTab);

        size2idxTab = new int[lookupMaxSize >> LOG2_QUANTUM];
        size2idxTab(size2idxTab);
    }

    /**
     * 记录了每个叶节点内存的大小，默认为8192，即8KB
     */
    protected final int pageSize;
    /**
     * <pre>
     * 值为13。
     * 该值可用于计算一个 run 中有多少个 page ，或者计算n个 page 的大小是多少字节。
     * 我们知道一个 run 包含多个 page，即 runSize 必然为 pageSize 的整数倍。
     * 又由于一个 pageSize 的默认为8192(8Kib)，是2的幂次方，pageSize对应的位图为：
     *     0000 0000 0000 0000 0010 0000 0000 0000 == 1 << 13
     * 故通过 page 个数计算 runSize 或者通过 runSize 计算 page 个数时可使用位操作。
     *     通过 page 个数计算 runSize ：runSize = n << pageShifts
     *     通过 runSize 计算 page 个数：n = runSize >> pageShifts
     */
    protected final int pageShifts;
    /**
     * 记录了当前整个PoolChunk申请的内存大小，默认为16M
     */
    protected final int chunkSize;
    /**
     * 指示分配直接内存时需要的对齐数。
     * 如果是0则表示不要求地址对齐。
     */
    protected final int directMemoryCacheAlignment;

    final int nSizes;
    int nSubpages;
    int nPSizes;

    int smallMaxSizeIdx;

    private int lookupMaxSize;

    private final short[][] sizeClasses;

    private final int[] pageIdx2sizeTab;

    // lookup table for sizeIdx <= smallMaxSizeIdx
    private final int[] sizeIdx2sizeTab;

    // lookup table used for size <= lookupMaxclass
    // spacing is 1 << LOG2_QUANTUM, so the size of array is lookupMaxclass >> LOG2_QUANTUM
    private final int[] size2idxTab;

    private int sizeClasses() {
        int normalMaxSize = -1;

        int index = 0;
        int size = 0;

        int log2Group = LOG2_QUANTUM;
        int log2Delta = LOG2_QUANTUM;
        int ndeltaLimit = 1 << LOG2_SIZE_CLASS_GROUP;

        //First small group, nDelta start at 0.
        //first size class is 1 << LOG2_QUANTUM
        int nDelta = 0;
        while (nDelta < ndeltaLimit) {
            size = sizeClass(index++, log2Group, log2Delta, nDelta++);
        }
        log2Group += LOG2_SIZE_CLASS_GROUP;

        //All remaining groups, nDelta start at 1.
        while (size < chunkSize) {
            nDelta = 1;

            while (nDelta <= ndeltaLimit && size < chunkSize) {
                size = sizeClass(index++, log2Group, log2Delta, nDelta++);
                normalMaxSize = size;
            }

            log2Group++;
            log2Delta++;
        }

        //chunkSize must be normalMaxSize
        assert chunkSize == normalMaxSize;

        //return number of size index
        return index;
    }

    //calculate size class
    private int sizeClass(int index, int log2Group, int log2Delta, int nDelta) {
        short isMultiPageSize;
        if (log2Delta >= pageShifts) {
            isMultiPageSize = yes;
        } else {
            int pageSize = 1 << pageShifts;
            int size = (1 << log2Group) + (1 << log2Delta) * nDelta;

            isMultiPageSize = size == size / pageSize * pageSize? yes : no;
        }

        int log2Ndelta = nDelta == 0? 0 : log2(nDelta);

        byte remove = 1 << log2Ndelta < nDelta? yes : no;

        int log2Size = log2Delta + log2Ndelta == log2Group? log2Group + 1 : log2Group;
        if (log2Size == log2Group) {
            remove = yes;
        }

        short isSubpage = log2Size < pageShifts + LOG2_SIZE_CLASS_GROUP? yes : no;

        int log2DeltaLookup = log2Size < LOG2_MAX_LOOKUP_SIZE ||
                              log2Size == LOG2_MAX_LOOKUP_SIZE && remove == no
                ? log2Delta : no;

        short[] sz = {
                (short) index, (short) log2Group, (short) log2Delta,
                (short) nDelta, isMultiPageSize, isSubpage, (short) log2DeltaLookup
        };

        sizeClasses[index] = sz;
        int size = (1 << log2Group) + (nDelta << log2Delta);

        if (sz[PAGESIZE_IDX] == yes) {
            nPSizes++;
        }
        if (sz[SUBPAGE_IDX] == yes) {
            nSubpages++;
            smallMaxSizeIdx = index;
        }
        if (sz[LOG2_DELTA_LOOKUP_IDX] != no) {
            lookupMaxSize = size;
        }
        return size;
    }

    private void idx2SizeTab(int[] sizeIdx2sizeTab, int[] pageIdx2sizeTab) {
        int pageIdx = 0;

        for (int i = 0; i < nSizes; i++) {
            short[] sizeClass = sizeClasses[i];
            int log2Group = sizeClass[LOG2GROUP_IDX];
            int log2Delta = sizeClass[LOG2DELTA_IDX];
            int nDelta = sizeClass[NDELTA_IDX];

            int size = (1 << log2Group) + (nDelta << log2Delta);
            sizeIdx2sizeTab[i] = size;

            if (sizeClass[PAGESIZE_IDX] == yes) {
                pageIdx2sizeTab[pageIdx++] = size;
            }
        }
    }

    private void size2idxTab(int[] size2idxTab) {
        int idx = 0;
        int size = 0;

        for (int i = 0; size <= lookupMaxSize; i++) {
            int log2Delta = sizeClasses[i][LOG2DELTA_IDX];
            int times = 1 << log2Delta - LOG2_QUANTUM;

            while (size <= lookupMaxSize && times-- > 0) {
                size2idxTab[idx++] = i;
                size = idx + 1 << LOG2_QUANTUM;
            }
        }
    }

    @Override
    public int sizeIdx2size(int sizeIdx) {
        return sizeIdx2sizeTab[sizeIdx];
    }

    @Override
    public int sizeIdx2sizeCompute(int sizeIdx) {
        int group = sizeIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = sizeIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int groupSize = group == 0? 0 :
                1 << LOG2_QUANTUM + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int lgDelta = shift + LOG2_QUANTUM - 1;
        int modSize = mod + 1 << lgDelta;

        return groupSize + modSize;
    }

    @Override
    public long pageIdx2size(int pageIdx) {
        return pageIdx2sizeTab[pageIdx];
    }

    @Override
    public long pageIdx2sizeCompute(int pageIdx) {
        int group = pageIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = pageIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        long groupSize = group == 0? 0 :
                1L << pageShifts + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int log2Delta = shift + pageShifts - 1;
        int modSize = mod + 1 << log2Delta;

        return groupSize + modSize;
    }

    @Override
    public int size2SizeIdx(int size) {
        if (size == 0) {
            return 0;
        }
        if (size > chunkSize) {
            return nSizes;
        }

        if (directMemoryCacheAlignment > 0) {
            size = alignSize(size);
        }

        if (size <= lookupMaxSize) {
            //size-1 / MIN_TINY
            return size2idxTab[size - 1 >> LOG2_QUANTUM];
        }

        int x = log2((size << 1) - 1);
        int shift = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM);

        int group = shift << LOG2_SIZE_CLASS_GROUP;

        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;

        int deltaInverseMask = -1 << log2Delta;
        int mod = (size - 1 & deltaInverseMask) >> log2Delta &
                  (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        return group + mod;
    }

    @Override
    public int pages2pageIdx(int pages) {
        return pages2pageIdxCompute(pages, false);
    }

    @Override
    public int pages2pageIdxFloor(int pages) {
        return pages2pageIdxCompute(pages, true);
    }

    private int pages2pageIdxCompute(int pages, boolean floor) {
        int pageSize = pages << pageShifts;
        if (pageSize > chunkSize) {
            return nPSizes;
        }

        int x = log2((pageSize << 1) - 1);

        int shift = x < LOG2_SIZE_CLASS_GROUP + pageShifts
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + pageShifts);

        int group = shift << LOG2_SIZE_CLASS_GROUP;

        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + pageShifts + 1?
                pageShifts : x - LOG2_SIZE_CLASS_GROUP - 1;

        int deltaInverseMask = -1 << log2Delta;
        int mod = (pageSize - 1 & deltaInverseMask) >> log2Delta &
                  (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int pageIdx = group + mod;

        if (floor && pageIdx2sizeTab[pageIdx] > pages << pageShifts) {
            pageIdx--;
        }

        return pageIdx;
    }

    // Round size up to the nearest multiple of alignment.
    private int alignSize(int size) {
        int delta = size & directMemoryCacheAlignment - 1;
        return delta == 0? size : size + directMemoryCacheAlignment - delta;
    }

    @Override
    public int normalizeSize(int size) {
        if (size == 0) {
            return sizeIdx2sizeTab[0];
        }
        if (directMemoryCacheAlignment > 0) {
            size = alignSize(size);
        }

        if (size <= lookupMaxSize) {
            int ret = sizeIdx2sizeTab[size2idxTab[size - 1 >> LOG2_QUANTUM]];
            assert ret == normalizeSizeCompute(size);
            return ret;
        }
        return normalizeSizeCompute(size);
    }

    private static int normalizeSizeCompute(int size) {
        int x = log2((size << 1) - 1);
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;
        int delta = 1 << log2Delta;
        int delta_mask = delta - 1;
        return size + delta_mask & ~delta_mask;
    }
}
