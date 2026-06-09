package com.dk.ipproxy.dynamic.gateway.handler;

import com.dk.ipproxy.dynamic.gateway.authenticator.BasicAuthenticator;
import com.dk.ipproxy.dynamic.gateway.context.ChannelContext;
import com.dk.ipproxy.dynamic.gateway.context.ContextAttrKey;
import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Socks5账密验证请求处理Handler
 */
@ChannelHandler.Sharable
public class Socks5PasswordAuthRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5PasswordAuthRequest> {
    private static final Logger logger = LoggerFactory.getLogger(Socks5PasswordAuthRequestHandler.class);
    private final BasicAuthenticator authenticator;

    public Socks5PasswordAuthRequestHandler(BasicAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, DefaultSocks5PasswordAuthRequest authRequest) {
        // 完成账密请求解码后，从pipeline中移除
        ChannelPipeline pipeline = context.pipeline();
        pipeline.remove(Socks5PasswordAuthRequestDecoder.class.getName());
        pipeline.remove(this);

        // 执行账密验证
        try {
            BasicAuthenticator.AuthInfo authInfo = authenticator.parseRawConnectUser(authRequest.username(), authRequest.password());
            if (authenticator.identify(authInfo)) {
                accepted(context, authRequest, authInfo);
            } else {
                rejected(context, authRequest);
            }
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.error("socks5 auth handle error", e);
            }
            if (logger.isWarnEnabled()) {
                logger.warn("socks5 auth handle error, exp: {}", e.getMessage());
            }
        }
    }

    /**
     * 账密验证成功，设置代理信息到上下文
     */
    private void accepted(ChannelHandlerContext context, DefaultSocks5PasswordAuthRequest authRequest, BasicAuthenticator.AuthInfo authInfo) {
        if (logger.isDebugEnabled()) {
            logger.debug("accept connect from username: {}", authRequest.username());
        }

        // 设置上下文
        ChannelContext channelContext = new ChannelContext();
        channelContext.setRawConnectUser(authRequest.username());
        channelContext.setUid(authInfo.getUid());
        channelContext.setAuthUser(authInfo.getAuthUser());
        channelContext.setAuthPass(authInfo.getAuthPass());
        channelContext.setForwardCountry(authInfo.getCountry());
        channelContext.setForwardState(authInfo.getState());
        channelContext.setForwardCity(authInfo.getCity());
        channelContext.setForwardSessionId(authInfo.getSessionId());
        channelContext.setForwardSessionKeepTime(authInfo.getKeepTime());
        context.channel().attr(ContextAttrKey.CHANNEL_FORWARD_CONTEXT).setIfAbsent(channelContext);

        // 转到下一个Handler
        Socks5PasswordAuthResponse response = new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS);
        context.writeAndFlush(response);
    }

    /**
     * 账密验证失败，关闭连接
     */
    private void rejected(ChannelHandlerContext context, DefaultSocks5PasswordAuthRequest authRequest) {
        if (logger.isDebugEnabled()) {
            logger.debug("reject connect from username: {}", authRequest.username());
        }

        Socks5PasswordAuthResponse response = new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE);
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
