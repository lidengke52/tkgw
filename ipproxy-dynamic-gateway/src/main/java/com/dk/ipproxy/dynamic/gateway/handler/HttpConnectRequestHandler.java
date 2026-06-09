package com.dk.ipproxy.dynamic.gateway.handler;

import com.dk.ipproxy.dynamic.gateway.cache.ChannelStateStore;
import com.dk.ipproxy.dynamic.gateway.cache.RequestCounter;
import com.dk.ipproxy.dynamic.gateway.config.GatewayConfig;
import com.dk.ipproxy.dynamic.gateway.config.GlobalConfigStore;
import com.dk.ipproxy.dynamic.gateway.context.ChannelContext;
import com.dk.ipproxy.dynamic.gateway.context.ContextAttrKey;
import com.dk.ipproxy.dynamic.gateway.filter.DomainFilter;
import com.dk.ipproxy.dynamic.gateway.service.ForwardAllocateService;
import com.dk.ipproxy.dynamic.gateway.utils.ForwardConnectUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在大聚合器前优先处理 CONNECT 隧道请求
 */
public class HttpConnectRequestHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(HttpConnectRequestHandler.class);
    private final EventLoopGroup forwarders;
    private final ForwardAllocateService forwardAllocateService;
    private boolean connectPending;

    public HttpConnectRequestHandler(EventLoopGroup forwarders) {
        this.forwarders = forwarders;
        this.forwardAllocateService = new ForwardAllocateService();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof HttpObject)) {
            super.channelRead(ctx, msg);
            return;
        }

        if (!(msg instanceof HttpRequest)) {
            if (connectPending) {
                ReferenceCountUtil.release(msg);
                return;
            }
            ctx.fireChannelRead(msg);
            return;
        }

        HttpRequest request = (HttpRequest) msg;
        if (!HttpMethod.CONNECT.equals(request.method())) {
            ctx.fireChannelRead(msg);
            return;
        }

        connectPending = true;
        try {
            handleConnect(ctx, request);
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void handleConnect(ChannelHandlerContext srcContext, HttpRequest request) {
        TargetEndpoint endpoint = parseConnectTarget(request.uri());
        if (endpoint == null) {
            srcContext.writeAndFlush(createHttpResponse(HttpResponseStatus.BAD_REQUEST)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        if (DomainFilter.isBlocked(endpoint.host)) {
            if (logger.isDebugEnabled()) {
                logger.debug("domain {} is blocked", endpoint.host);
            }
            srcContext.writeAndFlush(createHttpResponse(HttpResponseStatus.UNAUTHORIZED)).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        GatewayConfig gatewayConfig = GlobalConfigStore.value.getGatewayConfig();
        ChannelContext userForwardContext = srcContext.channel().attr(ContextAttrKey.CHANNEL_FORWARD_CONTEXT).get();
        if (userForwardContext == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("get context null");
            }
            srcContext.writeAndFlush(createHttpResponse(HttpResponseStatus.BAD_GATEWAY)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        RequestCounter.addTotalRequest(userForwardContext.getUid());

        if (!forwardAllocateService.getBindForwardUpstream(userForwardContext)) {
            if (logger.isDebugEnabled()) {
                logger.debug("get bind forward allocate line error");
            }
            RequestCounter.addFailRequest(userForwardContext.getUid());
            srcContext.writeAndFlush(createHttpResponse(HttpResponseStatus.BAD_GATEWAY)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("bind forward to {}:{}@{}:{}", userForwardContext.getBindForwardUsername(), userForwardContext.getBindForwardPass(), userForwardContext.getBindForwardHostname(), userForwardContext.getBindForwardPort());
        }

        if (shouldApplyUserLimit(userForwardContext)
                && !ChannelStateStore.tryBindUserChannel(srcContext.channel(), userForwardContext.getAuthUser(), gatewayConfig.getUserMaxConcurrentConnections())) {
            if (logger.isDebugEnabled()) {
                logger.debug("user concurrent connections exceeds limit, authUser: {}, limit: {}", userForwardContext.getAuthUser(), gatewayConfig.getUserMaxConcurrentConnections());
            }
            RequestCounter.addFailRequest(userForwardContext.getUid());
            srcContext.writeAndFlush(createHttpResponse(HttpResponseStatus.TOO_MANY_REQUESTS)).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        startForwardConnect(srcContext, userForwardContext, gatewayConfig, endpoint);
    }

    private void startForwardConnect(ChannelHandlerContext srcContext,
                                     ChannelContext userForwardContext,
                                     GatewayConfig gatewayConfig,
                                     TargetEndpoint endpoint) {
        AtomicBoolean responseHandled = new AtomicBoolean(false);
        ForwardConnectUtils.resolveSupplierAddress(forwarders.next(), gatewayConfig,
                userForwardContext.getBindForwardHostname(), userForwardContext.getBindForwardPort())
                .addListener((Future<InetSocketAddress> resolveFuture) ->
                        handleSupplierAddressResolved(srcContext, userForwardContext, gatewayConfig, endpoint, responseHandled, resolveFuture));
    }

    private void handleSupplierAddressResolved(ChannelHandlerContext srcContext,
                                               ChannelContext userForwardContext,
                                               GatewayConfig gatewayConfig,
                                               TargetEndpoint endpoint,
                                               AtomicBoolean responseHandled,
                                               Future<InetSocketAddress> resolveFuture) {
        if (!resolveFuture.isSuccess()) {
            handleSupplierResolveFailure(srcContext, userForwardContext, endpoint, responseHandled, resolveFuture.cause());
            return;
        }

        SocketAddress upstreamAddress = resolveFuture.getNow();
        Bootstrap bootstrap = createForwardBootstrap(srcContext, userForwardContext, gatewayConfig, endpoint, responseHandled, upstreamAddress);
        ChannelFuture forwardChannelFuture = ForwardConnectUtils.connect(bootstrap, gatewayConfig, endpoint.host, endpoint.port);
        forwardChannelFuture.addListener((ChannelFutureListener) future ->
                handleForwardConnectResult(srcContext, userForwardContext, endpoint, responseHandled, future));
    }

    private Bootstrap createForwardBootstrap(ChannelHandlerContext srcContext,
                                             ChannelContext userForwardContext,
                                             GatewayConfig gatewayConfig,
                                             TargetEndpoint endpoint,
                                             AtomicBoolean responseHandled,
                                             SocketAddress upstreamAddress) {
        WriteBufferWaterMark writeBufferWaterMark = new WriteBufferWaterMark(
                gatewayConfig.getWriteBufferLowWaterMark(),
                gatewayConfig.getWriteBufferHighWaterMark()
        );
        return new Bootstrap()
                .group(forwarders)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, gatewayConfig.getForwardConnectTimeoutMillis())
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        initUpstreamPipeline(channel, srcContext, userForwardContext, gatewayConfig, endpoint, responseHandled, upstreamAddress);
                    }
                });
    }

    private void initUpstreamPipeline(SocketChannel channel,
                                      ChannelHandlerContext srcContext,
                                      ChannelContext userForwardContext,
                                      GatewayConfig gatewayConfig,
                                      TargetEndpoint endpoint,
                                      AtomicBoolean responseHandled,
                                      SocketAddress upstreamAddress) {
        ChannelPipeline pipeline = channel.pipeline();
        addForwardTimeoutHandlers(pipeline, gatewayConfig);
        Socks5ProxyHandler proxyHandler = new Socks5ProxyHandler(upstreamAddress, userForwardContext.getBindForwardUsername(), userForwardContext.getBindForwardPass());
        proxyHandler.setConnectTimeoutMillis(gatewayConfig.getForwardConnectTimeoutMillis());
        pipeline.addLast(Socks5ProxyHandler.class.getName(), proxyHandler);
        pipeline.addLast("proxy-connect-state", createProxyConnectStateHandler(srcContext, userForwardContext, endpoint, responseHandled));
    }

    private ChannelInboundHandlerAdapter createProxyConnectStateHandler(ChannelHandlerContext srcContext,
                                                                       ChannelContext userForwardContext,
                                                                       TargetEndpoint endpoint,
                                                                       AtomicBoolean responseHandled) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext upstreamCtx, Throwable cause) throws Exception {
                handleProxyHandshakeFailure(srcContext, userForwardContext, endpoint, responseHandled, upstreamCtx, cause);
                super.exceptionCaught(upstreamCtx, cause);
            }

            @Override
            public void channelInactive(ChannelHandlerContext upstreamCtx) throws Exception {
                handleUpstreamInactiveBeforeReady(srcContext, userForwardContext, endpoint, responseHandled, upstreamCtx);
                super.channelInactive(upstreamCtx);
            }
        };
    }

    private void handleForwardConnectResult(ChannelHandlerContext srcContext,
                                            ChannelContext userForwardContext,
                                            TargetEndpoint endpoint,
                                            AtomicBoolean responseHandled,
                                            ChannelFuture future) {
        if (future.isSuccess()) {
            establishHttpTunnel(srcContext, userForwardContext, responseHandled, future.channel());
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("tcp connect to upstream proxy failed, proxy={}:{}, target={}:{}, msg={}",
                    userForwardContext.getBindForwardHostname(),
                    userForwardContext.getBindForwardPort(),
                    endpoint.host,
                    endpoint.port,
                    future.cause() == null ? null : future.cause().getLocalizedMessage(),
                    future.cause());
        }
        writeBadGatewayOnce(srcContext, userForwardContext, responseHandled);
    }

    private void establishHttpTunnel(ChannelHandlerContext srcContext,
                                     ChannelContext userForwardContext,
                                     AtomicBoolean responseHandled,
                                     Channel upstreamChannel) {
        responseHandled.compareAndSet(false, true);
        ChannelPipeline downstreamPipeline = upstreamChannel.pipeline();
        downstreamPipeline.addLast("from-upstream", new ForwardDownstreamChannelHandler(srcContext.channel()));

        ChannelPipeline upstreamPipeline = srcContext.pipeline();
        upstreamPipeline.addLast("to-upstream", new ForwardUpstreamChannelHandler(upstreamChannel));

        HttpResponse connectResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        srcContext.writeAndFlush(connectResp).addListener((ChannelFutureListener) responseFuture -> {
            if (responseFuture.isSuccess()) {
                RequestCounter.addSuccessRequest(userForwardContext.getUid());
            }
            removeIfPresent(srcContext.pipeline(), HttpServerCodec.class.getName());
            removeIfPresent(srcContext.pipeline(), HttpObjectAggregator.class.getName());
            removeIfPresent(srcContext.pipeline(), HttpConnectRequestHandler.class.getName());
            removeIfPresent(srcContext.pipeline(), HttpTrafficForwardHandler.class.getName());
        });
    }

    private void handleProxyHandshakeFailure(ChannelHandlerContext srcContext,
                                             ChannelContext userForwardContext,
                                             TargetEndpoint endpoint,
                                             AtomicBoolean responseHandled,
                                             ChannelHandlerContext upstreamCtx,
                                             Throwable cause) {
        if (logger.isDebugEnabled()) {
            logger.debug("upstream proxy handshake failed, channelId: {}, proxy={}:{}, target={}:{}, msg={}",
                    upstreamCtx.channel().id(),
                    userForwardContext.getBindForwardHostname(),
                    userForwardContext.getBindForwardPort(),
                    endpoint.host,
                    endpoint.port,
                    cause == null ? null : cause.getMessage(),
                    cause);
        }
        writeBadGatewayOnce(srcContext, userForwardContext, responseHandled);
    }

    private void handleUpstreamInactiveBeforeReady(ChannelHandlerContext srcContext,
                                                  ChannelContext userForwardContext,
                                                  TargetEndpoint endpoint,
                                                  AtomicBoolean responseHandled,
                                                  ChannelHandlerContext upstreamCtx) {
        if (logger.isDebugEnabled()) {
            logger.debug("upstream channel inactive before proxy ready, channelId: {}, proxy={}:{}, target={}:{}",
                    upstreamCtx.channel().id(),
                    userForwardContext.getBindForwardHostname(),
                    userForwardContext.getBindForwardPort(),
                    endpoint.host,
                    endpoint.port);
        }
        writeBadGatewayOnce(srcContext, userForwardContext, responseHandled);
    }

    private void handleSupplierResolveFailure(ChannelHandlerContext srcContext,
                                              ChannelContext userForwardContext,
                                              TargetEndpoint endpoint,
                                              AtomicBoolean responseHandled,
                                              Throwable cause) {
        if (logger.isDebugEnabled()) {
            logger.debug("resolve upstream supplier failed, proxy={}:{}, target={}:{}, msg={}",
                    userForwardContext.getBindForwardHostname(),
                    userForwardContext.getBindForwardPort(),
                    endpoint.host,
                    endpoint.port,
                    cause == null ? null : cause.getLocalizedMessage(),
                    cause);
        }
        writeBadGatewayOnce(srcContext, userForwardContext, responseHandled);
    }

    private void writeBadGatewayOnce(ChannelHandlerContext srcContext,
                                     ChannelContext userForwardContext,
                                     AtomicBoolean responseHandled) {
        if (responseHandled.compareAndSet(false, true)) {
            RequestCounter.addFailRequest(userForwardContext.getUid());
            srcContext.writeAndFlush(createHttpResponse(HttpResponseStatus.BAD_GATEWAY)).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void addForwardTimeoutHandlers(ChannelPipeline pipeline, GatewayConfig gatewayConfig) {
        if (gatewayConfig.getForwardReadTimeoutMillis() > 0) {
            pipeline.addLast("forward-read-timeout", new ReadTimeoutHandler(gatewayConfig.getForwardReadTimeoutMillis(), TimeUnit.MILLISECONDS));
        }
        if (gatewayConfig.getForwardWriteTimeoutMillis() > 0) {
            pipeline.addLast("forward-write-timeout", new WriteTimeoutHandler(gatewayConfig.getForwardWriteTimeoutMillis(), TimeUnit.MILLISECONDS));
        }
    }

    private boolean shouldApplyUserLimit(ChannelContext context) {
        return context != null
                && !StringUtils.isBlank(context.getAuthUser())
                && !":".equals(context.getRawConnectUser());
    }

    private void removeIfPresent(ChannelPipeline pipeline, String handlerName) {
        if (pipeline.context(handlerName) != null) {
            pipeline.remove(handlerName);
        }
    }

    private FullHttpResponse createHttpResponse(HttpResponseStatus status) {
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    }

    private TargetEndpoint parseConnectTarget(String uri) {
        if (StringUtils.isBlank(uri)) {
            return null;
        }
        try {
            String host;
            String portString;
            if (uri.startsWith("[")) {
                int end = uri.indexOf(']');
                if (end < 0 || end + 2 > uri.length() || uri.charAt(end + 1) != ':') {
                    return null;
                }
                host = uri.substring(1, end);
                portString = uri.substring(end + 2);
            } else {
                int idx = uri.lastIndexOf(':');
                if (idx <= 0 || idx == uri.length() - 1) {
                    return null;
                }
                host = uri.substring(0, idx);
                portString = uri.substring(idx + 1);
            }
            int port = Integer.parseInt(portString);
            if (port <= 0 || port > 65535 || host.isEmpty()) {
                return null;
            }
            return new TargetEndpoint(host, port);
        } catch (Exception e) {
            return null;
        }
    }

    private static class TargetEndpoint {
        private final String host;
        private final int port;

        private TargetEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
