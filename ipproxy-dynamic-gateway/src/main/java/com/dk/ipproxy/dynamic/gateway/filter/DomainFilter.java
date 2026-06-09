package com.dk.ipproxy.dynamic.gateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 域名过滤器
 */
public class DomainFilter {
    private static final Logger logger = LoggerFactory.getLogger(DomainFilter.class);
    private static Cache<String, Boolean> domainCache;
    private static Map<String, Pattern> domainPatternMap;

    public static void addDomainBlack(String domainPatten) {
        try {
            String regexPattern = domainPatten.replace(".", "\\.").replace("*", ".*");
            Pattern pattern = Pattern.compile(regexPattern);
            domainPatternMap.put(domainPatten, pattern);
        } catch (Exception e) {
            logger.error("failed to add domain to black list", e);
        }
    }

    public static void init() {
        // 创建缓存cache
        domainCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterAccess(30, TimeUnit.SECONDS)
                .build();
        // 创建域名正则表达式Map
        domainPatternMap = new ConcurrentHashMap<>();
        logger.info("finish init domain filter");
    }

    public static boolean isBlocked(String domain) {
        // 首先从Cache获取
        Boolean isBlocked = domainCache.getIfPresent(domain);
        if (isBlocked != null) {
            return isBlocked;
        }
        // 匹配规则
        for (Pattern pattern : domainPatternMap.values()) {
            if (pattern.matcher(domain).matches()) {
                domainCache.put(domain, true);
                return true;
            }
        }
        logger.info("check visit domain: {}", domain);
        return false;
    }
}
