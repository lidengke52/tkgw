package com.dk.ipproxy.dynamic.gateway.handler;

import com.dk.ipproxy.dynamic.gateway.authenticator.BasicAuthenticator;
import com.dk.ipproxy.dynamic.gateway.context.ChannelContext;
import com.dk.ipproxy.dynamic.gateway.context.ContextAttrKey;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * http账密验证请求处理Handler
 */
@ChannelHandler.Sharable
public class HttpPasswordAuthRequestHandler extends SimpleChannelInboundHandler<HttpRequest> {
    private static final Logger logger = LoggerFactory.getLogger(HttpPasswordAuthRequestHandler.class);
    private final BasicAuthenticator authenticator;

    public HttpPasswordAuthRequestHandler(BasicAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, HttpRequest authRequest) throws Exception {
        // 完成账密请求解码后，从pipeline中移除
        ChannelPipeline pipeline = context.pipeline();
        pipeline.remove(this);

        try {
            // 获取传入的账密
            String auth = authRequest.headers().get(HttpHeaderNames.PROXY_AUTHORIZATION);

            // 未设置账密
            if (auth == null || !auth.startsWith("Basic ")) {
                reject(context, "null");
                return;
            }

            // 解码账密
            String[] userPassword = decodeAuthHeader(auth);
            if (userPassword == null) {
                reject(context, "null");
                return;
            }

            // 账密验证
            String username = userPassword[0];
            String password = userPassword[1];
            BasicAuthenticator.AuthInfo authInfo = authenticator.parseRawConnectUser(username, password);
            if (authenticator.identify(authInfo)) {
                accepted(context, authRequest, username, authInfo);
            } else {
                reject(context, username);
            }
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.error("http auth handle error", e);
            }
            if (logger.isWarnEnabled()) {
                logger.warn("http auth handle error, exp: {}", e.getMessage());
            }
        }
    }

    /**
     * 解码账密
     */
    private String[] decodeAuthHeader(String auth) {
        String[] authParts = auth.split(" ");
        if (authParts.length != 2) {
            return null;
        }

        String authInfo = new String(Base64.getDecoder().decode(authParts[1]), StandardCharsets.UTF_8);
        return authInfo.split(":", 2);
    }

    /**
     * 账密验证成功，设置代理信息到上下文
     */
    private void accepted(ChannelHandlerContext context, HttpRequest authRequest, String rawUser, BasicAuthenticator.AuthInfo authInfo) {
        if (logger.isDebugEnabled()) {
            logger.debug("accept connect from username: {}", rawUser);
        }

        // 设置上下文
        ChannelContext channelContext = new ChannelContext();
        channelContext.setRawConnectUser(rawUser);
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
        context.fireChannelRead(ReferenceCountUtil.retain(authRequest));
    }

    /**
     * 发送未授权响应
     */
    private void reject(ChannelHandlerContext ctx, String rawUser) {
        if (logger.isDebugEnabled()) {
            logger.debug("reject connect from username: {}", rawUser);
        }

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
