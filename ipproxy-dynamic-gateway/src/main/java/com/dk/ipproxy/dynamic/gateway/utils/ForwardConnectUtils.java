package com.dk.ipproxy.dynamic.gateway.utils;

import com.dk.ipproxy.dynamic.gateway.config.GatewayConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.NoopDnsCache;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * 建立到上游 SOCKS5 代理的目标连接；根据 {@link GatewayConfig#isDnsRemote()} 选择本机或远程 DNS。
 */
public final class ForwardConnectUtils {

    private ForwardConnectUtils() {
    }

    /**
     * HTTP 转发：根据 host 字符串判断是否为字面量 IP。
     */
    public static ChannelFuture connect(Bootstrap bootstrap, GatewayConfig config, String host, int port) {
        if (!config.isDnsRemote()) {
            return bootstrap.connect(host, port);
        }
        bootstrap.disableResolver();
        return bootstrap.connect(toTargetAddress(host, port, null));
    }

    /**
     * SOCKS5 转发：使用客户端 CONNECT 请求中的地址类型。
     */
    public static ChannelFuture connect(Bootstrap bootstrap, GatewayConfig config, String host, int port,
                                        Socks5AddressType addrType) {
        if (!config.isDnsRemote()) {
            return bootstrap.connect(host, port);
        }
        bootstrap.disableResolver();
        return bootstrap.connect(toTargetAddress(host, port, addrType));
    }

    /**
     * Resolve upstream supplier hostname without application-side DNS caching.
     */
    public static Future<InetSocketAddress> resolveSupplierAddress(EventLoop eventLoop, GatewayConfig config, String host, int port) {
        if (!config.isDisableSupplierDnsCache() || isLiteralIp(host)) {
            return eventLoop.newSucceededFuture(new InetSocketAddress(host, port));
        }
        return resolveFresh(eventLoop, host, port);
    }

    /**
     * Resolve hostname with a one-shot Netty resolver and no resolver-level cache.
     */
    public static Future<InetSocketAddress> resolveFresh(EventLoop eventLoop, String host, int port) {
        if (isLiteralIp(host)) {
            return eventLoop.newSucceededFuture(new InetSocketAddress(host, port));
        }

        Promise<InetSocketAddress> promise = eventLoop.newPromise();
        DnsNameResolver resolver = new DnsNameResolverBuilder(eventLoop)
                .channelType(NioDatagramChannel.class)
                .resolveCache(NoopDnsCache.INSTANCE)
                .build();
        resolver.resolve(host).addListener((Future<InetAddress> future) -> {
            try {
                if (future.isSuccess()) {
                    promise.setSuccess(new InetSocketAddress(future.getNow(), port));
                } else {
                    promise.setFailure(future.cause());
                }
            } finally {
                resolver.close();
            }
        });
        return promise;
    }

    private static InetSocketAddress toTargetAddress(String host, int port, Socks5AddressType addrType) {
        if (addrType != null) {
            if (addrType == Socks5AddressType.DOMAIN) {
                return InetSocketAddress.createUnresolved(host, port);
            }
            return new InetSocketAddress(host, port);
        }
        if (isLiteralIp(host)) {
            return new InetSocketAddress(host, port);
        }
        return InetSocketAddress.createUnresolved(host, port);
    }

    private static boolean isLiteralIp(String host) {
        return NetUtil.isValidIpV4Address(host) || NetUtil.isValidIpV6Address(host);
    }
}
