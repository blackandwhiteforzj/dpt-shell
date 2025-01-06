package com.yeahka.dexshadow.task;

import com.yeahka.dexshadow.Const;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Copyright © Yeahka All Rights Reserved.
 *
 * @author jimmyzhou
 * @date 2024/7/2
 * @desc 线程池管理
 */
public class ThreadPool {
    private static ThreadPool sInst;
    private static final ThreadPoolExecutor sThreadPoolExecutor = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors(),
            Runtime.getRuntime().availableProcessors() * 2,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new CustomThreadFactory());
    private ThreadPool(){

    }
    public static ThreadPool getInstance() {
        if(sInst == null){
            sInst = new ThreadPool();
        }
        return sInst;
    }

    public static class CustomThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName(Const.DEFAULT_THREAD_NAME + "-" + t.getId());
            return t;
        }
    }

    public void execute(Runnable task){
        sThreadPoolExecutor.execute(task);
    }

    public void shutdown(){
        sThreadPoolExecutor.shutdown();
    }
}
