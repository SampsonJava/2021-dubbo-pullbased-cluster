package com.aliware.tianchi;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.*;

import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * 客户端过滤器（选址后）
 * 可选接口
 * 此类可以修改实现，不可以移动类或者修改包名
 * 用户可以在客户端拦截请求和响应,捕获 rpc 调用时产生、服务端返回的已知异常。
 */
@Activate(group = CommonConstants.CONSUMER)
public class TestClientFilter implements Filter, BaseFilter.Listener {

    private static final String ACTIVELIMIT_FILTER_START_TIME = "activelimit_filter_start_time";
    private static final String MAX_CONCURRENT = "max_concurrent";
    private static final String ACTIVES = "ACTIVES";
    private static final String FINE_TUNE_FROM_CLIENT = "fineTuneFromClient";
    private static final AtomicInteger countToMax = new AtomicInteger(0);

    // step.4
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        invocation.put(ACTIVELIMIT_FILTER_START_TIME, System.currentTimeMillis());
        URL url = invoker.getUrl();
        int timeout = MyRpcStatus.getTimeout(url); // todo
        RpcContext.getClientAttachment().setAttachment(TIMEOUT_KEY, timeout);
        int max = 0; // todo
        final MyCount myCount = MyCount.getCount(url);
        if (!myCount.beginCount(url, max)) {
            countToMax.incrementAndGet();
            throw new RpcException(RpcException.LIMIT_EXCEEDED_EXCEPTION,
                    "=.= get to limit concurrent invoke for service:  " +
                            invoker.getInterface().getName() + ", method: " + invocation.getMethodName() +
                            ". concurrent invokes: " +
                            myCount.getActive() + ". max concurrent invoke limit: " + max);
        }

        int fineTuneToProvider = 0;
        if (myCount.fineTune.get() != 0) {
            synchronized (myCount) {
                if (myCount.fineTune.get() != 0) {
                    fineTuneToProvider = myCount.fineTune.get();
                    myCount.fineTune.set(0);
                }
            }
        }
        invocation.setAttachment(FINE_TUNE_FROM_CLIENT, String.valueOf(fineTuneToProvider));

        Result result = invoker.invoke(invocation);
        // wait server a little
        try {
            Thread.sleep(1, 500_000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
//        System.out.println("active: " + myCount.getActive() + " max: " + max + " maxToGetCount: " + countToMax.get());
        return result;
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        URL url = invoker.getUrl();

        int maxConcurrent = Integer.parseInt(appResponse.getAttachment(MAX_CONCURRENT));
        MyRpcStatus status = MyRpcStatus.getStatus(url);
        status.maxConcurrent.set(maxConcurrent);

        if (maxConcurrent != 0 && !status.isInit.get()) {
            synchronized (status) {
                if (!status.isInit.get()) {
                    status.isInit.set(true);
                    MyRpcStatus.initQueue(url, 2 * maxConcurrent);
                }
            }
        }
        long elapsed = getElapsed(invocation);

        // 判断是否预热完成
        if (status.isInit.get()) {
            // 活跃数
            int active = Integer.parseInt(appResponse.getAttachment(ACTIVES));
            MyRpcStatus.record(url, active);
            MyCount.endCountAfterPreheat(url, elapsed, true);
        }


        MyCount.endCount(url, elapsed, true);
        MyRpcStatus.endCount(url, elapsed, true);
//        System.out.println("+succ: " + MyCount.getCount(url).getSucceeded() + " maxConcurrent: " + maxConcurrent + " queue_size: "
//                + MyRpcStatus.RPC_QUEUE.size() + " initMaxQueueSize: " + MyRpcStatus.initMaxQueueSize.get());
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
//        System.out.println("== " + t);
        URL url = invoker.getUrl();

        if (MyRpcStatus.getStatus(url).isInit.get()) {
            MyRpcStatus.record(url, -1);
        }

        if (t instanceof RpcException) {
            RpcException rpcException = (RpcException) t;
            if (rpcException.isLimitExceed()) {
                return;
            }
        }
        MyRpcStatus.endCount(url, getElapsed(invocation), false);
        MyCount.endCount(url, getElapsed(invocation), false);
//        System.out.println("-fail: " + MyCount.getCount(url).getFailed());
    }

    private long getElapsed(Invocation invocation) {
        Object beginTime = invocation.get(ACTIVELIMIT_FILTER_START_TIME);
        return beginTime != null ? System.currentTimeMillis() - (Long) beginTime : 0;
    }
}
