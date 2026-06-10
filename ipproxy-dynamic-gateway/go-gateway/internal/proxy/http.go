package proxy

import (
	"bufio"
	"encoding/base64"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"ipproxy-dynamic-gateway-go/internal/config"
	"ipproxy-dynamic-gateway-go/internal/dynamic"
	"ipproxy-dynamic-gateway-go/internal/filter"
	"ipproxy-dynamic-gateway-go/internal/stats"
)

type HTTPProxy struct {
	cfg    config.Config
	store  *dynamic.Store
	stats  *stats.Collector
	noAuth func(*http.Request) (dynamic.Context, bool)
	ipf    *filter.IPFilter
}

func NewHTTPProxy(cfg config.Config, store *dynamic.Store, stats *stats.Collector, ipf *filter.IPFilter, noAuth func(*http.Request) (dynamic.Context, bool)) *HTTPProxy {
	return &HTTPProxy{cfg: cfg, store: store, stats: stats, noAuth: noAuth, ipf: ipf}
}

func (p *HTTPProxy) ListenAndServe(addr string) error {
	srv := &http.Server{
		Addr:              addr,
		Handler:           p,
		ReadHeaderTimeout: p.cfg.ReadTimeout,
		ReadTimeout:       p.cfg.ReadTimeout,
		WriteTimeout:      p.cfg.WriteTimeout,
		IdleTimeout:       IdleTimeout(p.cfg),
		MaxHeaderBytes:    int(p.cfg.HTTPRequestHeaderMaxSize),
	}
	log.Printf("http proxy listening on %s", addr)
	return srv.ListenAndServe()
}

func (p *HTTPProxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	p.stats.ConnOpen()
	defer p.stats.ConnClose()
	if p.ipf != nil && p.ipf.Blocked(r.RemoteAddr) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	ctx, ok := p.contextFromRequest(r)
	if !ok {
		w.Header().Set("Proxy-Authenticate", `Basic realm="dynamic-gateway"`)
		http.Error(w, "proxy authentication required", http.StatusProxyAuthRequired)
		return
	}
	host, port, ok := targetFromRequest(r)
	if !ok {
		http.Error(w, "bad target", http.StatusBadRequest)
		return
	}
	p.stats.Total(ctx.UID)
	if !p.store.BindForward(&ctx) {
		p.stats.Fail(ctx.UID)
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return
	}
	if !p.store.TryAcquireUser(ctx.AuthUser) {
		p.stats.Fail(ctx.UID)
		http.Error(w, "too many requests", http.StatusTooManyRequests)
		return
	}
	defer p.store.ReleaseUser(ctx.AuthUser)
	if r.Method == http.MethodConnect {
		p.handleConnect(w, r, ctx, host, port)
		return
	}
	p.handleHTTP(w, r, ctx, host, port)
}

func (p *HTTPProxy) contextFromRequest(r *http.Request) (dynamic.Context, bool) {
	if p.noAuth != nil {
		return p.noAuth(r)
	}
	header := r.Header.Get("Proxy-Authorization")
	if header == "" {
		return dynamic.Context{}, false
	}
	const prefix = "Basic "
	if !strings.HasPrefix(header, prefix) {
		return dynamic.Context{}, false
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(header[len(prefix):]))
	if err != nil {
		return dynamic.Context{}, false
	}
	parts := strings.SplitN(string(raw), ":", 2)
	if len(parts) != 2 {
		return dynamic.Context{}, false
	}
	return p.store.Authenticate(parts[0], parts[1])
}

func (p *HTTPProxy) handleConnect(w http.ResponseWriter, r *http.Request, ctx dynamic.Context, host string, port int) {
	upstream, err := DialViaSOCKS5(p.cfg, ctx, host, port)
	if err != nil {
		log.Printf("http connect upstream failed target=%s:%d err=%v", host, port, err)
		p.store.RecordForwardFailure(ctx, err.Error())
		p.stats.Fail(ctx.UID)
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return
	}
	p.store.RecordForwardSuccess(ctx)
	hj, ok := w.(http.Hijacker)
	if !ok {
		upstream.Close()
		http.Error(w, "hijack unsupported", http.StatusInternalServerError)
		return
	}
	client, rw, err := hj.Hijack()
	if err != nil {
		upstream.Close()
		return
	}
	_, _ = rw.WriteString("HTTP/1.1 200 OK\r\n\r\n")
	_ = rw.Flush()
	p.stats.Success(ctx.UID)
	Relay(client, upstream, func(n int64) { p.stats.Sent(ctx.UID, n) }, func(n int64) { p.stats.Recv(ctx.UID, n) })
}

func (p *HTTPProxy) handleHTTP(w http.ResponseWriter, r *http.Request, ctx dynamic.Context, host string, port int) {
	upstream, err := DialViaSOCKS5(p.cfg, ctx, host, port)
	if err != nil {
		log.Printf("http upstream failed target=%s:%d err=%v", host, port, err)
		p.store.RecordForwardFailure(ctx, err.Error())
		p.stats.Fail(ctx.UID)
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return
	}
	p.store.RecordForwardSuccess(ctx)
	defer upstream.Close()
	req := new(http.Request)
	*req = *r
	req.RequestURI = ""
	req.URL.Scheme = "http"
	req.URL.Host = net.JoinHostPort(host, strconv.Itoa(port))
	req.Host = r.Host
	req.Header = r.Header.Clone()
	req.Header.Del("Proxy-Authorization")
	req.Header.Del("Proxy-Connection")
	if req.Body != nil {
		req.Body = countingReadCloser{ReadCloser: req.Body, cb: func(n int64) { p.stats.Sent(ctx.UID, n) }}
	}
	if p.cfg.ForwardWriteTimeout > 0 {
		_ = upstream.SetWriteDeadline(time.Now().Add(p.cfg.ForwardWriteTimeout))
	}
	if err := req.Write(upstream); err != nil {
		p.stats.Fail(ctx.UID)
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return
	}
	if p.cfg.ForwardReadTimeout > 0 {
		_ = upstream.SetReadDeadline(time.Now().Add(p.cfg.ForwardReadTimeout))
	}
	resp, err := http.ReadResponse(bufio.NewReader(upstream), req)
	if err != nil {
		p.stats.Fail(ctx.UID)
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	for k, vals := range resp.Header {
		for _, v := range vals {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	n, _ := io.Copy(countingWriter{w: w, cb: func(n int64) { p.stats.Recv(ctx.UID, n) }}, resp.Body)
	if n >= 0 {
		p.stats.Success(ctx.UID)
	}
}

func targetFromRequest(r *http.Request) (string, int, bool) {
	target := r.Host
	if r.Method == http.MethodConnect {
		target = r.RequestURI
	}
	host, portStr, err := net.SplitHostPort(target)
	if err == nil {
		port, err := strconv.Atoi(portStr)
		return host, port, err == nil && port > 0 && port <= 65535
	}
	if strings.Contains(err.Error(), "missing port in address") {
		return target, 80, target != ""
	}
	return "", 0, false
}

func WhiteListHTTPContext(store *dynamic.Store, bindPort int, remoteIP string) (dynamic.Context, bool) {
	snap := store.Snapshot()
	wl, ok := snap.WhiteList[remoteIP]
	if !ok {
		return dynamic.Context{}, false
	}
	info, ok := wl.Ports[bindPort]
	if !ok || !strings.EqualFold(info.Protocol, dynamic.ProtocolHTTP) {
		return dynamic.Context{}, false
	}
	user, ok := snap.UsersByID[wl.UID]
	if !ok {
		return dynamic.Context{}, false
	}
	return dynamic.ContextForWhiteList(user, info, bindPort), true
}

func RemoteIP(addr string) string {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	return host
}

func ParseBindPort(addr string) (int, error) {
	_, port, err := net.SplitHostPort(addr)
	if err != nil {
		return 0, err
	}
	p, err := strconv.Atoi(port)
	if err != nil {
		return 0, fmt.Errorf("bad port %q", port)
	}
	return p, nil
}

func IdleTimeout(cfg config.Config) time.Duration {
	if cfg.ReadIdleTimeout > cfg.WriteIdleTimeout {
		return cfg.ReadIdleTimeout
	}
	return cfg.WriteIdleTimeout
}

type countingReadCloser struct {
	io.ReadCloser
	cb func(int64)
}

func (r countingReadCloser) Read(p []byte) (int, error) {
	n, err := r.ReadCloser.Read(p)
	if n > 0 && r.cb != nil {
		r.cb(int64(n))
	}
	return n, err
}
