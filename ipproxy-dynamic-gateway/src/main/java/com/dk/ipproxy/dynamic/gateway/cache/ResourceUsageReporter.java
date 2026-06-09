package com.dk.ipproxy.dynamic.gateway.cache;

import com.dk.ipproxy.dynamic.gateway.config.GatewayConfig;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class ResourceUsageReporter {
    private static final Logger logger = LoggerFactory.getLogger(ResourceUsageReporter.class);
    private static final MemoryMXBean MEMORY_MX_BEAN = ManagementFactory.getMemoryMXBean();
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final PooledByteBufAllocatorMetric NETTY_ALLOCATOR_METRIC = PooledByteBufAllocator.DEFAULT.metric();
    private static final BufferPoolMXBean DIRECT_BUFFER_POOL_MX_BEAN = findDirectBufferPoolMxBean();

    private ResourceUsageReporter() {
    }

    public static void init(GatewayConfig gatewayConfig) {
        int intervalSec = gatewayConfig.getResourceUsageLogIntervalSec();
        if (intervalSec <= 0) {
            logger.info("resource usage reporter disabled, intervalSec: {}", intervalSec);
            return;
        }

        ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(1, new ReporterThreadFactory());
        executorService.scheduleAtFixedRate(new ResourceUsageReportTask(), intervalSec, intervalSec, TimeUnit.SECONDS);
        logger.info("finish init resource usage reporter, intervalSec: {}", intervalSec);
    }

    private static BufferPoolMXBean findDirectBufferPoolMxBean() {
        List<BufferPoolMXBean> bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        for (BufferPoolMXBean bufferPool : bufferPools) {
            if ("direct".equalsIgnoreCase(bufferPool.getName())) {
                return bufferPool;
            }
        }
        return null;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "N/A";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }

        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unitIndex = -1;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        return String.format(Locale.ROOT, "%.2f %s (%d B)", value, units[unitIndex], bytes);
    }

    private static class ResourceUsageReportTask implements Runnable {
        @Override
        public void run() {
            try {
                MemoryUsage heapUsage = MEMORY_MX_BEAN.getHeapMemoryUsage();
                long directBufferCount = DIRECT_BUFFER_POOL_MX_BEAN == null ? -1L : DIRECT_BUFFER_POOL_MX_BEAN.getCount();
                long directBufferUsedBytes = DIRECT_BUFFER_POOL_MX_BEAN == null ? -1L : DIRECT_BUFFER_POOL_MX_BEAN.getMemoryUsed();
                long directBufferCapacityBytes = DIRECT_BUFFER_POOL_MX_BEAN == null ? -1L : DIRECT_BUFFER_POOL_MX_BEAN.getTotalCapacity();
                logger.info("resource usage stats:\n" +
                                "  heap:\n" +
                                "    used      : {}\n" +
                                "    committed : {}\n" +
                                "    max       : {}\n" +
                                "  direct buffer pool:\n" +
                                "    count     : {}\n" +
                                "    used      : {}\n" +
                                "    capacity  : {}\n" +
                                "  netty:\n" +
                                "    directUsed: {}\n" +
                                "  threads:\n" +
                                "    live      : {}\n" +
                                "    daemon    : {}\n" +
                                "    peak      : {}\n" +
                                "  channels:\n" +
                                "    active    : {}",
                        formatBytes(heapUsage.getUsed()),
                        formatBytes(heapUsage.getCommitted()),
                        formatBytes(heapUsage.getMax()),
                        directBufferCount,
                        formatBytes(directBufferUsedBytes),
                        formatBytes(directBufferCapacityBytes),
                        formatBytes(NETTY_ALLOCATOR_METRIC.usedDirectMemory()),
                        THREAD_MX_BEAN.getThreadCount(),
                        THREAD_MX_BEAN.getDaemonThreadCount(),
                        THREAD_MX_BEAN.getPeakThreadCount(),
                        ChannelStateStore.getActiveChannelCount());
            } catch (Exception e) {
                logger.error("report resource usage failed", e);
            }
        }
    }

    private static class ReporterThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "resource-usage-reporter");
            thread.setDaemon(true);
            return thread;
        }
    }
}
