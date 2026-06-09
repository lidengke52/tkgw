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
import io.netty.handler.codec.socksx.v5.*;
import io.netty.handler.proxy.ProxyConnectionEvent;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Socks5 Command命令请求处理Handler
 */
@ChannelHandler.Sharable
public class Socks5CommandRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5CommandRequest> {
    private static final Logger logger = LoggerFactory.getLogger(Socks5CommandRequestHandler.class);
    private final EventLoopGroup forwarders;
    private final ForwardAllocateService forwardAllocateService;

    public Socks5CommandRequestHandler(EventLoopGroup forwarders) {
        this.forwarders = forwarders;
        this.forwardAllocateService = new ForwardAllocateService();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, DefaultSocks5CommandRequest commandRequest) {
        // 完成Command命令解码后，从Pipeline中移除
        ChannelPipeline pipeline = context.pipeline();
        pipeline.remove((Socks5CommandRequestDecoder.class.getName()));
        pipeline.remove(this);

        if (logger.isDebugEnabled()) {
            logger.debug("accept command, {}, {} --> {}:{}", commandRequest.type(), context.channel().remoteAddress(), commandRequest.dstAddr(), commandRequest.dstPort());
        }

        try {
            // 处理Sock5命令
            if (commandRequest.type().equals(Socks5CommandType.CONNECT)) {
                handleConnect(context, commandRequest);
            } else {
                // BIND/UDP ASSOCIATE命令不支持
                context.close();
            }
        } catch (Exception e) {
            if (logger.isWarnEnabled()) {
                logger.error("socks5 command handle error", e);
            }
            context.close();
        }
    }

    /**
     * 处理Connect请求
     */
    private void handleConnect(final ChannelHandlerContext context, DefaultSocks5CommandRequest commandRequest) {
        // 判断访问的域名是否在黑名单中
        if (DomainFilter.isBlocked(commandRequest.dstAddr())) {
            if (logger.isDebugEnabled()) {
                logger.debug("domain {} is blocked", commandRequest.dstAddr());
            }
            context.writeAndFlush(socks5CommandResponse(false)).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        // 获取Gateway配置
        GatewayConfig gatewayConfig = GlobalConfigStore.value.getGatewayConfig();

        // 获取上下文信息
        ChannelContext userChannelContext = context.channel().attr(ContextAttrKey.CHANNEL_FORWARD_CONTEXT).get();
        if (userChannelContext == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("get context null");
            }
            context.writeAndFlush(socks5CommandResponse(false)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        RequestCounter.addTotalRequest(userChannelContext.getUid());

        // 获取绑定的upstream信息
        boolean bindSuccess = forwardAllocateService.getBindForwardUpstream(userChannelContext);
        if (!bindSuccess) {
            if (logger.isDebugEnabled()) {
                logger.debug("get bind forward allocate line error");
            }
            context.writeAndFlush(socks5CommandResponse(false)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("bind forward to {}:{}@{}:{}", userChannelContext.getBindForwardUsername(), userChannelContext.getBindForwardPass(), userChannelContext.getBindForwardHostname(), userChannelContext.getBindForwardPort());
        }

        if (shouldApplyUserLimit(userChannelContext)
                && !ChannelStateStore.tryBindUserChannel(context.channel(), userChannelContext.getAuthUser(), gatewayConfig.getUserMaxConcurrentConnections())) {
            if (logger.isDebugEnabled()) {
                logger.debug("user concurrent connections exceeds limit, authUser: {}, limit: {}", userChannelContext.getAuthUser(), gatewayConfig.getUserMaxConcurrentConnections());
            }
            RequestCounter.addFailRequest(userChannelContext.getUid());
            context.writeAndFlush(socks5CommandResponse(Socks5CommandStatus.FAILURE)).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        // 标记channel用于连接管理
        ChannelId channelId = context.channel().id();
        String authUser = userChannelContext.getAuthUser();
        String sessionId = userChannelContext.getForwardSessionId();
        ChannelStateStore.addChannelBindInfo(context.channel(), userChannelContext.getAuthUser(), userChannelContext.getForwardSessionId());
        if (logger.isDebugEnabled()) {
            logger.debug("add channel to state manage, channelId: {}, authUser: {}, sessionId: {}", channelId, authUser, sessionId);
        }

        startForwardConnect(context, commandRequest, userChannelContext, gatewayConfig);
    }

    private void startForwardConnect(ChannelHandlerContext context,
                                     DefaultSocks5CommandRequest commandRequest,
                                     ChannelContext userChannelContext,
                                     GatewayConfig gatewayConfig) {
        AtomicBoolean responseHandled = new AtomicBoolean(false);
        ForwardConnectUtils.resolveSupplierAddress(forwarders.next(), gatewayConfig,
                userChannelContext.getBindForwardHostname(), userChannelContext.getBindForwardPort())
                .addListener((Future<InetSocketAddress> resolveFuture) ->
                        handleSupplierAddressResolved(context, commandRequest, userChannelContext, gatewayConfig, responseHandled, resolveFuture));
    }

    private void handleSupplierAddressResolved(ChannelHandlerContext context,
                                               DefaultSocks5CommandRequest commandRequest,
                                               ChannelContext userChannelContext,
                                               GatewayConfig gatewayConfig,
                                               AtomicBoolean responseHandled,
                                               Future<InetSocketAddress> resolveFuture) {
        if (!resolveFuture.isSuccess()) {
            handleSupplierResolveFailure(context, commandRequest, userChannelContext, responseHandled, resolveFuture.cause());
            return;
        }

        SocketAddress upstreamAddress = resolveFuture.getNow();
        Bootstrap bootstrap = createForwardBootstrap(context, commandRequest, userChannelContext, gatewayConfig, responseHandled, upstreamAddress);

        // 建立连接
        logger.info("accept request, channelId: {},  {} --> {}:{}", context.channel().id(), context.channel().remoteAddress(), commandRequest.dstAddr(), commandRequest.dstPort());
        ChannelFuture forwardChannelFuture = ForwardConnectUtils.connect(
                bootstrap, gatewayConfig, commandRequest.dstAddr(), commandRequest.dstPort(), commandRequest.dstAddrType());

        // 状态监听器-异步
        forwardChannelFuture.addListener((ChannelFutureListener) future ->
                handleForwardConnectResult(context, commandRequest, userChannelContext, responseHandled, future));
    }

    private Bootstrap createForwardBootstrap(ChannelHandlerContext context,
                                             DefaultSocks5CommandRequest commandRequest,
                                             ChannelContext userChannelContext,
                                             GatewayConfig gatewayConfig,
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
                        initUpstreamPipeline(upstreamChannel, context, commandRequest, userChannelContext, gatewayConfig, responseHandled, upstreamAddress);
                    }
                });
    }

    private void initUpstreamPipeline(SocketChannel upstreamChannel,
                                      ChannelHandlerContext context,
                                      DefaultSocks5CommandRequest commandRequest,
                                      ChannelContext userChannelContext,
                                      GatewayConfig gatewayConfig,
                                      AtomicBoolean responseHandled,
                                      SocketAddress upstreamAddress) {
        ChannelPipeline upstreamPipeline = upstreamChannel.pipeline();
        addForwardTimeoutHandlers(upstreamPipeline, gatewayConfig);
        Socks5ProxyHandler proxyHandler = new Socks5ProxyHandler(upstreamAddress, userChannelContext.getBindForwardUsername(), userChannelContext.getBindForwardPass());
        proxyHandler.setConnectTimeoutMillis(gatewayConfig.getForwardConnectTimeoutMillis());
        upstreamPipeline.addFirst("upstream", proxyHandler);
        upstreamPipeline.addLast("proxy-connect-state", createProxyConnectStateHandler(context, commandRequest, userChannelContext, responseHandled));
    }

    private ChannelInboundHandlerAdapter createProxyConnectStateHandler(ChannelHandlerContext context,
                                                                       DefaultSocks5CommandRequest commandRequest,
                                                                       ChannelContext userChannelContext,
                                                                       AtomicBoolean responseHandled) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext upstreamCtx, Object evt) throws Exception {
                if (evt instanceof ProxyConnectionEvent && responseHandled.compareAndSet(false, true)) {
                    handleProxyConnected(context, userChannelContext, upstreamCtx);
                }
                super.userEventTriggered(upstreamCtx, evt);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext upstreamCtx, Throwable cause) throws Exception {
                handleProxyHandshakeFailure(context, commandRequest, userChannelContext, responseHandled, upstreamCtx, cause);
                super.exceptionCaught(upstreamCtx, cause);
            }

            @Override
            public void channelInactive(ChannelHandlerContext upstreamCtx) throws Exception {
                handleUpstreamInactiveBeforeReady(context, commandRequest, userChannelContext, responseHandled, upstreamCtx);
                super.channelInactive(upstreamCtx);
            }
        };
    }

    private void handleProxyConnected(ChannelHandlerContext context,
                                      ChannelContext userChannelContext,
                                      ChannelHandlerContext upstreamCtx) {
        if (logger.isDebugEnabled()) {
            logger.debug("upstream proxy connected, channelId: {}", upstreamCtx.channel().id());
        }

        // 建立upstream下行链路
        ChannelPipeline downstreamPipeline = upstreamCtx.channel().pipeline();
        downstreamPipeline.addLast("from-upstream", new ForwardDownstreamChannelHandler(context.channel()));

        // 建立upstream上行链路
        ChannelPipeline userPipeline = context.pipeline();
        userPipeline.addLast("to-upstream", new ForwardUpstreamChannelHandler(upstreamCtx.channel()));

        context.writeAndFlush(socks5CommandResponse(true)).addListener((ChannelFutureListener) responseFuture -> {
            if (responseFuture.isSuccess()) {
                RequestCounter.addSuccessRequest(userChannelContext.getUid());
            }
        });
    }

    private void handleProxyHandshakeFailure(ChannelHandlerContext context,
                                             DefaultSocks5CommandRequest commandRequest,
                                             ChannelContext userChannelContext,
                                             AtomicBoolean responseHandled,
                                             ChannelHandlerContext upstreamCtx,
                                             Throwable cause) {
        if (logger.isDebugEnabled()) {
            logger.debug("upstream proxy handshake failed, clientChannelId: {}, upstreamChannelId: {}, clientRemote={}, upstreamLocal={}, upstreamRemote={}, proxy={}:{}, proxyUsername={}, proxyPassword={}, target={}:{}, targetAddrType={}, authUser={}, uid={}, sessionId={}, exceptionClass={}, msg={}, localizedMsg={}",
                    context.channel().id(),
                    upstreamCtx.channel().id(),
                    context.channel().remoteAddress(),
                    upstreamCtx.channel().localAddress(),
                    upstreamCtx.channel().remoteAddress(),
                    userChannelContext.getBindForwardHostname(),
                    userChannelContext.getBindForwardPort(),
                    userChannelContext.getBindForwardUsername(),
                    userChannelContext.getBindForwardPass(),
                    commandRequest.dstAddr(),
                    commandRequest.dstPort(),
                    commandRequest.dstAddrType(),
                    userChannelContext.getAuthUser(),
                    userChannelContext.getUid(),
                    userChannelContext.getForwardSessionId(),
                    cause == null ? null : cause.getClass().getName(),
                    cause == null ? null : cause.getMessage(),
                    cause == null ? null : cause.getLocalizedMessage(),
                    cause);
        }
        writeSocks5FailureOnce(context, userChannelContext, responseHandled);
    }

    private void handleUpstreamInactiveBeforeReady(ChannelHandlerContext context,
                                                  DefaultSocks5CommandRequest commandRequest,
                                                  ChannelContext userChannelContext,
                                                  AtomicBoolean responseHandled,
                                                  ChannelHandlerContext upstreamCtx) {
        if (logger.isDebugEnabled()) {
            logger.debug("upstream channel inactive before proxy ready, channelId: {}, proxy={}:{}, target={}:{}",
                    upstreamCtx.channel().id(),
                    userChannelContext.getBindForwardHostname(),
                    userChannelContext.getBindForwardPort(),
                    commandRequest.dstAddr(),
                    commandRequest.dstPort());
        }
        writeSocks5FailureOnce(context, userChannelContext, responseHandled);
    }

    private void handleSupplierResolveFailure(ChannelHandlerContext context,
                                              DefaultSocks5CommandRequest commandRequest,
                                              ChannelContext userChannelContext,
                                              AtomicBoolean responseHandled,
                                              Throwable cause) {
        if (logger.isDebugEnabled()) {
            logger.debug("resolve upstream supplier failed, proxy={}:{}, target={}:{}, msg={}",
                    userChannelContext.getBindForwardHostname(),
                    userChannelContext.getBindForwardPort(),
                    commandRequest.dstAddr(),
                    commandRequest.dstPort(),
                    cause == null ? null : cause.getLocalizedMessage(),
                    cause);
        }
        writeSocks5FailureOnce(context, userChannelContext, responseHandled);
    }

    private void handleForwardConnectResult(ChannelHandlerContext context,
                                            DefaultSocks5CommandRequest commandRequest,
                                            ChannelContext userChannelContext,
                                            AtomicBoolean responseHandled,
                                            ChannelFuture future) {
        if (future.isSuccess()) {
            if (logger.isDebugEnabled()) {
                logger.debug("upstream tcp connected, waiting proxy handshake, channelId: {}", future.channel().id());
            }
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("tcp connect to upstream proxy failed, proxy={}:{}, target={}:{}, msg={}",
                    userChannelContext.getBindForwardHostname(),
                    userChannelContext.getBindForwardPort(),
                    commandRequest.dstAddr(),
                    commandRequest.dstPort(),
                    future.cause() == null ? null : future.cause().getLocalizedMessage(),
                    future.cause());
        }
        writeSocks5FailureOnce(context, userChannelContext, responseHandled);
    }

    private void writeSocks5FailureOnce(ChannelHandlerContext context,
                                        ChannelContext userChannelContext,
                                        AtomicBoolean responseHandled) {
        if (responseHandled.compareAndSet(false, true)) {
            RequestCounter.addFailRequest(userChannelContext.getUid());
            context.writeAndFlush(socks5CommandResponse(false)).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private Socks5CommandResponse socks5CommandResponse(boolean success) {
        Socks5CommandStatus status = success ? Socks5CommandStatus.SUCCESS : Socks5CommandStatus.FAILURE;
        return socks5CommandResponse(status);
    }

    private Socks5CommandResponse socks5CommandResponse(Socks5CommandStatus status) {
        return new DefaultSocks5CommandResponse(status, Socks5AddressType.IPv4);
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
        return context != null && context.getAuthUser() != null && !":".equals(context.getRawConnectUser());
    }
}
