package com.dk.ipproxy.dynamic.gateway.filter;

import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CountryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * IP过滤器
 * 根据GeoIP2数据库过滤国内的连接访问
 */
public class IPFilter {
    private static final Logger logger = LoggerFactory.getLogger(IPFilter.class);
    private static DatabaseReader geoDbReader;

    /**
     * 初始化 IP过滤器，读取DB文件
     */
    public static void init() throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream("Country-only-cn-private.mmdb")) {
            if (inputStream == null) {
                logger.error("ip geo mmdb file not exists");
                throw new Exception("ip geo mmdb file not exists");
            }

            // 复制到临时文件
            Path tempFile = Files.createTempFile("Country-only-cn-private", ".tmp");
            tempFile.toFile().deleteOnExit();
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

            // 读取db文件
            geoDbReader = new DatabaseReader.Builder(tempFile.toFile())
                    .withCache(new CHMCache())
                    .build();
        }
        logger.info("finish init ip filter");
    }

    public static boolean isBlocked(String ip) {
        try {
            InetAddress ipAddress = InetAddress.getByName(ip);
            Optional<CountryResponse> response = geoDbReader.tryCountry(ipAddress);
            if (response.isPresent()) {
                if ("CN".equalsIgnoreCase(response.get().getCountry().getIsoCode())) {
                    logger.error("blocked connect from CN, ip: {}", ip);
                    return true;
                } else if ("PRIVATE".equalsIgnoreCase(response.get().getCountry().getIsoCode())) {
                    logger.info("accept connect from PRIVATE, ip: {}", ip);
                    return false;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        } catch (Exception e) {
            logger.error("failed to check ip geo", e);
            return true;
        }
    }
}
