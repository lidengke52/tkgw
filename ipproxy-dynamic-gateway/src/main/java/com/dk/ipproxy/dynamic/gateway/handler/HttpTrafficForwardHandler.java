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
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.util.concurrent.Future;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 普通HTTP请求转发Handler
 */
@ChannelHandler.Sharable
public class HttpTrafficForwardHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger logger = LoggerFactory.getLogger(HttpTrafficForwardHandler.class);
    private final EventLoopGroup forwarders;
    private final ForwardAllocateService forwardAllocateService;

    public HttpTrafficForwardHandler(EventLoopGroup forwarders) {
        this.forwarders = forwarders;
        this.forwardAllocateService = new ForwardAllocateService();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest req) {
        try {
            TargetEndpoint endpoint = parseHostHeader(req.headers().get(HttpHeaderNames.HOST));
            if (endpoint == null) {
                context.writeAndFlush(createHttpResponse(HttpResponseStatus.BAD_REQUEST)).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            if (DomainFilter.isBlocked(endpoint.host)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("domain {} is blocked", endpoint.host);
                }
                context.writeAndFlush(createHttpResponse(HttpResponseStatus.UNAUTHORIZED)).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            GatewayConfig gatewayConfig = GlobalConfigStore.value.getGatewayConfig();
            ChannelContext userForwardContext = context.channel().attr(ContextAttrKey.CHANNEL_FORWARD_CONTEXT).get();
            if (userForwardContext == null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("get context null");
                }
                context.writeAndFlush(createHttpResponse(HttpResponseStatus.BAD_GATEWAY)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            RequestCounter.addTotalRequest(userForwardContext.getUid());

            if (!forwardAllocateService.getBindForwardUpstream(userForwardContext)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("get bind forward allocate line error");
                }
                RequestCounter.addFailRequest(userForwardContext.getUid());
                context.writeAndFlush(createHttpResponse(HttpResponseStatus.BAD_GATEWAY)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("bind forward to {}:{}@{}:{}", userForwardContext.getBindForwardUsername(), userForwardContext.getBindForwardPass(), userForwardContext.getBindForwardHostname(), userForwardContext.getBindForwardPort());
            }

            if (shouldApplyUserLimit(userForwardContext)
                    && !ChannelStateStore.tryBindUserChannel(context.channel(), userForwardContext.getAuthUser(), gatewayConfig.getUserMaxConcurrentConnections())) {
                if (logger.isDebugEnabled()) {
                    logger.debug("user concurrent connections exceeds limit, authUser: {}, limit: {}", userForwardContext.getAuthUser(), gatewayConfig.getUserMaxConcurrentConnections());
                }
                RequestCounter.addFailRequest(userForwardContext.getUid());
                context.writeAndFlush(createHttpResponse(HttpResponseStatus.TOO_MANY_REQUESTS)).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            handleCommonForward(context, userForwardContext, gatewayConfig, req, endpoint.host, endpoint.port, userForwardContext.getUid());
        } catch (Exception e) {
            if (logger.isWarnEnabled()) {
                logger.warn("http command handle error, exp: {}", e.getMessage());
            }
            context.close();
        }
    }

    private void handleCommonForward(ChannelHandlerContext srcContext, ChannelContext userForwardContext, GatewayConfig gatewayConfig, FullHttpRequest req, String dstHost, int dstPort, int uid) {
        AtomicBoolean responseHandled = new AtomicBoolean(false);
        ForwardConnectUtils.resolveSupplierAddress(forwarders.next(), gatewayConfig,
                userForwardContext.getBindForwardHostname(), userForwardContext.getBindForwardPort())
                .addListener((Future<InetSocketAddress> resolveFuture) ->
                        handleSupplierAddressResolved(srcContext, userForwardContext, gatewayConfig, req, dstHost, dstPort, uid, responseHandled, resolveFuture));
    }

    private void handleSupplierAddressResolved(ChannelHandlerContext srcContext,
                                               ChannelContext userForwardContext,
                                               GatewayConfig gatewayConfig,
                                               FullHttpRequest req,
                                               String dstHost,
                                               int dstPort,
                                               int uid,
                                               AtomicBoolean responseHandled,
                                               Future<InetSocketAddress> resolveFuture) {
        if (!resolveFuture.isSuccess()) {
            handleSupplierResolveFailure(srcContext, userForwardContext, dstHost, dstPort, uid, responseHandled, resolveFuture.cause());
            return;
        }

        SocketAddress upstreamAddress = resolveFuture.getNow();
        Bootstrap bootstrap = createForwardBootstrap(srcContext, userForwardContext, gatewayConfig, req, dstHost, dstPort, uid, responseHandled, upstreamAddress);

        logger.info("accept request, channelId: {},  {} --> {}:{}", srcContext.channel().id(), srcContext.channel().remoteAddress(), dstHost, dstPort);
        ChannelFuture forwardChannelFuture = ForwardConnectUtils.connect(bootstrap, gatewayConfig, dstHost, dstPort);
        forwardChannelFuture.addListener((ChannelFutureListener) future ->
                handleForwardConnectResult(srcContext, userForwardContext, dstHost, dstPort, uid, responseHandled, future));
    }

    private Bootstrap createForwardBootstrap(ChannelHandlerContext srcContext,
                                             ChannelContext userForwardContext,
                                             GatewayConfig gatewayConfig,
                                             FullHttpRequest req,
                                             String dstHost,
                                             int dstPort,
                                             int uid,
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
                    protected void initChannel(SocketChannel upstreamChannel) {
                        initUpstreamPipeline(upstreamChannel, srcContext, userForwardContext, gatewayConfig, req, dstHost, dstPort, uid, responseHandled, upstreamAddress);
                    }
                });
    }

    private void initUpstreamPipeline(SocketChannel upstreamChannel,
                                      ChannelHandlerContext srcContext,
                                      ChannelContext userForwardContext,
                                      GatewayConfig gatewayConfig,
                                      FullHttpRequest req,
                                      String dstHost,
                                      int dstPort,
                                      int uid,
                                      AtomicBoolean responseHandled,
                                      SocketAddress upstreamAddress) {
        ChannelPipeline upstreamPipeline = upstreamChannel.pipeline();
        addForwardTimeoutHandlers(upstreamPipeline, gatewayConfig);

        Socks5ProxyHandler proxyHandler = new Socks5ProxyHandler(upstreamAddress, userForwardContext.getBindForwardUsername(), userForwardContext.getBindForwardPass());
        proxyHandler.setConnectTimeoutMillis(gatewayConfig.getForwardConnectTimeoutMillis());
        upstreamPipeline.addFirst(Socks5ProxyHandler.class.getName(), proxyHandler);
        upstreamPipeline.addLast("proxy-connect-state", createProxyConnectStateHandler(srcContext, userForwardContext, dstHost, dstPort, uid, responseHandled));
        upstreamPipeline.addLast(HttpClientCodec.class.getName(), new HttpClientCodec());
        upstreamPipeline.addLast(HttpObjectAggregator.class.getName(), new HttpObjectAggregator(gatewayConfig.getHttpObjectAggregatorSize()));
        upstreamPipeline.addLast("protocolConvert", createProtocolConvertHandler(srcContext, req, uid, responseHandled));
    }

    private ChannelInboundHandlerAdapter createProxyConnectStateHandler(ChannelHandlerContext srcContext,
                                                                       ChannelContext userForwardContext,
                                                                       String dstHost,
                                                                       int dstPort,
                                                                       int uid,
                                                                       AtomicBoolean responseHandled) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext upstreamCtx, Throwable cause) throws Exception {
                handleProxyHandshakeFailure(srcContext, userForwardContext, dstHost, dstPort, uid, responseHandled, upstreamCtx, cause);
                super.exceptionCaught(upstreamCtx, cause);
            }

            @Override
            public void channelInactive(ChannelHandlerContext upstreamCtx) throws Exception {
                handleUpstreamInactiveBeforeReady(srcContext, userForwardContext, dstHost, dstPort, uid, responseHandled, upstreamCtx);
                super.channelInactive(upstreamCtx);
            }
        };
    }

    private SimpleChannelInboundHandler<FullHttpResponse> createProtocolConvertHandler(ChannelHandlerContext srcContext,
                                                                                      FullHttpRequest req,
                                                                                      int uid,
                                                                                      AtomicBoolean responseHandled) {
        return new SimpleChannelInboundHandler<FullHttpResponse>() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                sendHttpRequestToUpstream(ctx, req);
            }

            @Override
            protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
                handleUpstreamHttpResponse(srcContext, uid, responseHandled, ctx, response);
            }
        };
    }

    private void sendHttpRequestToUpstream(ChannelHandlerContext ctx, FullHttpRequest req) {
        HttpHeaders headers = req.headers();
        headers.remove(HttpHeaderNames.PROXY_AUTHORIZATION);
        headers.remove(HttpHeaderNames.PROXY_CONNECTION);

        ctx.writeAndFlush(new DefaultFullHttpRequest(
                req.protocolVersion(),
                req.method(),
                req.uri(),
                Unpooled.copiedBuffer(req.content()),
                headers,
                req.trailingHeaders()
        ));
    }

    private void handleUpstreamHttpResponse(ChannelHandlerContext srcContext,
                                            int uid,
                                            AtomicBoolean responseHandled,
                                            ChannelHandlerContext upstreamCtx,
                                            FullHttpResponse response) {
        responseHandled.compareAndSet(false, true);
        srcContext.writeAndFlush(createHttpResponse(response)).addListener((ChannelFutureListener) respFuture -> {
            if (respFuture.isSuccess()) {
                RequestCounter.addSuccessRequest(uid);
            }
            ChannelFutureListener.CLOSE.operationComplete(respFuture);
        });
        upstreamCtx.fireChannelInactive();
        upstreamCtx.close();
    }

    private void handleForwardConnectResult(ChannelHandlerContext srcContext,
                                            ChannelContext userForwardContext,
                                            String dstHost,
                                            int dstPort,
                                            int uid,
                                            AtomicBoolean responseHandled,
                                            ChannelFuture future) {
        if (future.isSuccess()) {
            if (logger.isDebugEnabled()) {
                logger.debug("upstream connected, channelId: {}", future.channel().id());
            }
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("tcp connect to upstream proxy failed, proxy={}:{}, target={}:{}, msg={}",
                    userForwardContext.getBindForwardHostname(),
                    userForwardContext.getBindForwardPort(),
                    dstHost,
                    dstPort,
                    future.cause() == null ? null : future.cause().getLocalizedMessage(),
                    future.cause());
        }
        writeBadGatewayOnce(srcContext, uid, responseHandled);
    }

    private void handleProxyHandshakeFailure(ChannelHandlerContext srcContext,
                                             ChannelContext userForwardContext,
                                             String dstHost,
                                             int dstPort,
                                             int uid,
                                             AtomicBoolean responseHandled,
                                             ChannelHandlerContext upstreamCtx,
                                             Throwable cause) {
        if (logger.isDebugEnabled()) {
            logger.debug("upstream proxy handshake failed, channelId: {}, proxy={}:{}, target={}:{}, msg={}",
                    upstreamCtx.channel().id(),
                    userForwardContext.getBindForwardHostname(),
                    userForwardContext.getBindForwardPort(),
                    dstHost,
                    dstPort,
                    cause == null ? null : cause.getMessage(),
                    cause);
        }
        writeBadGatewayOnce(srcContext, uid, responseHandled);
    }

    private void handleUpstreamInactiveBeforeReady(ChannelHandlerContext srcContext,
                                                  ChannelContext userForwardContext,
                                                  String dstHost,
                                                  int dstPort,
                                                  int uid,
                                                  AtomicBoolean responseHandled,
                                                  ChannelHandlerContext upstreamCtx) {
        if (logger.isDebugEnabled()) {
            logger.debug("upstream channel inactive before proxy ready, channelId: {}, proxy={}:{}, target={}:{}",
                    upstreamCtx.channel().id(),
                    userForwardContext.getBindForwardHostname(),
                    userForwardContext.getBindForwardPort(),
                    dstHost,
                    dstPort);
        }
        writeBadGatewayOnce(srcContext, uid, responseHandled);
    }

    private void handleSupplierResolveFailure(ChannelHandlerContext srcContext,
                                              ChannelContext userForwardContext,
                                              String dstHost,
                                              int dstPort,
                                              int uid,
                                              AtomicBoolean responseHandled,
                                              Throwable cause) {
        if (logger.isDebugEnabled()) {
            logger.debug("resolve upstream supplier failed, proxy={}:{}, target={}:{}, msg={}",
                    userForwardContext.getBindForwardHostname(),
                    userForwardContext.getBindForwardPort(),
                    dstHost,
                    dstPort,
                    cause == null ? null : cause.getLocalizedMessage(),
                    cause);
        }
        writeBadGatewayOnce(srcContext, uid, responseHandled);
    }

    private void writeBadGatewayOnce(ChannelHandlerContext srcContext,
                                     int uid,
                                     AtomicBoolean responseHandled) {
        if (responseHandled.compareAndSet(false, true)) {
            RequestCounter.addFailRequest(uid);
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

    private TargetEndpoint parseHostHeader(String hostHeader) {
        if (StringUtils.isBlank(hostHeader)) {
            return null;
        }
        try {
            String host = hostHeader;
            int port = 80;
            if (hostHeader.startsWith("[")) {
                int end = hostHeader.indexOf(']');
                if (end < 0) {
                    return null;
                }
                host = hostHeader.substring(1, end);
                if (end + 2 <= hostHeader.length() && hostHeader.charAt(end + 1) == ':') {
                    port = Integer.parseInt(hostHeader.substring(end + 2));
                }
            } else if (hostHeader.contains(":")) {
                int idx = hostHeader.lastIndexOf(':');
                host = hostHeader.substring(0, idx);
                port = Integer.parseInt(hostHeader.substring(idx + 1));
            }

            if (host.isEmpty() || port <= 0 || port > 65535) {
                return null;
            }
            return new TargetEndpoint(host, port);
        } catch (Exception e) {
            return null;
        }
    }

    private FullHttpResponse createHttpResponse(FullHttpResponse resp) {
        return new DefaultFullHttpResponse(
                resp.protocolVersion(),
                resp.status(),
                Unpooled.copiedBuffer(resp.content()),
                resp.headers(),
                resp.trailingHeaders()
        );
    }

    private FullHttpResponse createHttpResponse(HttpResponseStatus status) {
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
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
