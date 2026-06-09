package com.dk.ipproxy.dynamic.gateway.cache;

import com.dk.ipproxy.dynamic.gateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 请求计数统计
 */
public class RequestCounter {
    private static final Logger logger = LoggerFactory.getLogger(RequestCounter.class);
    private static final Map<Integer, Long> totalRequestMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> successRequestMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> failRequestMap = new ConcurrentHashMap<>();

    public static void init(GatewayConfig gatewayConfig) {
        ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(1);
        executorService.scheduleAtFixedRate(new ScheduleRequestReport(),
                gatewayConfig.getTrafficReportIntervalSec(),
                gatewayConfig.getTrafficReportIntervalSec(),
                TimeUnit.SECONDS);
        logger.info("finish init request counter");
    }

    /**
     * 增加总请求数
     */
    public static void addTotalRequest(int uid) {
        totalRequestMap.put(uid, totalRequestMap.getOrDefault(uid, 0L) + 1L);
    }

    /**
     * 增加成功请求数
     */
    public static void addSuccessRequest(int uid) {
        successRequestMap.put(uid, successRequestMap.getOrDefault(uid, 0L) + 1L);
    }

    /**
     * 增加失败请求数
     */
    public static void addFailRequest(int uid) {
        failRequestMap.put(uid, failRequestMap.getOrDefault(uid, 0L) + 1L);
    }

    /**
     * 重置用户请求统计
     */
    public static void resetUserRequest(int uid) {
        totalRequestMap.put(uid, 0L);
        successRequestMap.put(uid, 0L);
        failRequestMap.put(uid, 0L);
    }

    /**
     * 定时请求统计输出
     */
    private static class ScheduleRequestReport implements Runnable {
        public void run() {
            long totalRequest = 0L;
            long totalSuccess = 0L;
            long totalFail = 0L;

            Set<Integer> userIdSet = new HashSet<>();
            userIdSet.addAll(totalRequestMap.keySet());
            userIdSet.addAll(successRequestMap.keySet());
            userIdSet.addAll(failRequestMap.keySet());

            for (Integer userId : userIdSet) {
                long requestCount = totalRequestMap.getOrDefault(userId, 0L);
                long successCount = successRequestMap.getOrDefault(userId, 0L);
                long failCount = failRequestMap.getOrDefault(userId, 0L);
                if (requestCount == 0L && successCount == 0L && failCount == 0L) {
                    continue;
                }

                totalRequest += requestCount;
                totalSuccess += successCount;
                totalFail += failCount;
                double userSuccessRate = requestCount == 0L ? 0.0D : (double) successCount / requestCount * 100;
                logger.info("request stats, uid: {}, total: {}, success: {}, fail: {}, successRate: {}%",
                        userId, requestCount, successCount, failCount, String.format("%.2f", userSuccessRate));
                resetUserRequest(userId);
            }

            if (totalRequest == 0L && totalSuccess == 0L && totalFail == 0L) {
                logger.info("no request stats to report");
                return;
            }

            double totalSuccessRate = totalRequest == 0L ? 0.0D : (double) totalSuccess / totalRequest * 100;
            logger.info("request stats total, request: {}, success: {}, fail: {}, successRate: {}%",
                    totalRequest, totalSuccess, totalFail, String.format("%.2f", totalSuccessRate));
        }
    }
}
