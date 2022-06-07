package io.netty.example.abug;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.InternalThreadLocalMap;

import java.util.ArrayList;
import java.util.List;

/**
 * 对于{@link FastThreadLocalThread}而言，它拥有自己私有的{@link InternalThreadLocalMap#indexedVariables}。
 * 既然是私有的，对于每个线程：
 *      indexedVariables[0] = VariablesToRemoveMap
 *      indexedVariables[1~n] 存储值
 * 那为什么{@link FastThreadLocal}共享一个的{@link InternalThreadLocalMap#nextIndex}？
 *
 * 既然啊每一个线程独占一个{@link InternalThreadLocalMap#indexedVariables}，为什么还让不同线程的存储索引互斥？
 *
 * 如下情景：
 *      Thread1 创建 1024 个 FastThreadLocal。
 *      now InternalThreadLocalMap#nextIndex == 1024。
 *      Thread2 创建1个 FastThreadLocal。
 *      now Thread2.InternalThreadLocalMap#indexedVariables.length > 1024。
 *      Thread2.InternalThreadLocalMap#indexedVariables[1 ~ 1024] == UNSET。
 *
 * 这不是bug。threadLocal 是多个线程公用的。
 */
public class BUG4FastThreadLocalThread {

    public static void main(String[] args) throws Exception{

        FastThreadLocalThread thread1 = new FastThreadLocalThread(new Runnable() {
                public List<FastThreadLocal<Integer>> list = new ArrayList<FastThreadLocal<Integer>>();
                @Override
                public void run() {
                    for(int i = 0; i < 1_000_000; i++) {
                        FastThreadLocal<Integer> threadLocal = new FastThreadLocal<Integer>();
                        threadLocal.set(i);
                        list.add(threadLocal);
                    }
                }
        });
        FastThreadLocalThread thread2 = new FastThreadLocalThread(new Runnable() {
                FastThreadLocal<Integer> threadLocal;
                @Override
                public void run() {
                    threadLocal = new FastThreadLocal<>();
                    threadLocal.set(100);
                    System.out.println(threadLocal.get()); // <- 这里会发现线程的InternalThreadLocalMap#indexedVariables空了前面的1000个没有使用。
                }
        });

        long start1 = System.currentTimeMillis();
        thread1.start();
        thread1.join();
        long end1 = System.currentTimeMillis();
        System.out.println(end1-start1 + " ms");


        long start2 = System.currentTimeMillis();
        thread2.start();
        thread2.join();
        long end2 = System.currentTimeMillis();
        System.out.println(end2-start2 + " ms");
    }
}
