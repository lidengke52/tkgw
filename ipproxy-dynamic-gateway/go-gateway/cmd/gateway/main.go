package main

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"ipproxy-dynamic-gateway-go/internal/config"
	"ipproxy-dynamic-gateway-go/internal/dynamic"
	"ipproxy-dynamic-gateway-go/internal/filter"
	httpproxy "ipproxy-dynamic-gateway-go/internal/proxy"
	"ipproxy-dynamic-gateway-go/internal/resource"
	"ipproxy-dynamic-gateway-go/internal/socks"
	"ipproxy-dynamic-gateway-go/internal/stats"
)

func main() {
	cfg, err := config.Load(os.Args[1:])
	if err != nil {
		log.Fatalf("config error: %v", err)
	}
	store := dynamic.NewStore(cfg)
	store.Run()
	collector := stats.New(cfg.Endpoint, cfg.Token, cfg.GatewayHostname)
	go collector.Run(cfg.TrafficReportInterval)
	go resource.Run(cfg.ResourceUsageLogInterval, collector)
	ipf := filter.NewIPFilter("")

	socksServer := socks.New(cfg, store, collector, ipf)
	httpServer := httpproxy.NewHTTPProxy(cfg, store, collector, ipf, nil)

	errCh := make(chan error, 8)
	go func() { errCh <- socksServer.ListenAndServe(cfg.Addr(cfg.ListenSocks5Port), nil) }()
	go func() { errCh <- httpServer.ListenAndServe(cfg.Addr(cfg.ListenHTTPPort)) }()
	for port := cfg.WhiteListPortRangeStart; port <= cfg.WhiteListPortRangeEnd; port++ {
		port := port
		go func() { errCh <- serveWhiteListPort(cfg, store, collector, socksServer, ipf, port) }()
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	select {
	case sig := <-sigCh:
		log.Printf("shutdown signal: %s", sig)
	case err := <-errCh:
		if err != nil && !errors.Is(err, net.ErrClosed) && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("server error: %v", err)
		}
	}
}

func serveWhiteListPort(cfg config.Config, store *dynamic.Store, collector *stats.Collector, socksServer *socks.Server, ipf *filter.IPFilter, port int) error {
	ln, err := net.Listen("tcp", cfg.Addr(port))
	if err != nil {
		return err
	}
	log.Printf("white list routing listening on %s", cfg.Addr(port))
	for {
		conn, err := ln.Accept()
		if err != nil {
			return err
		}
		go dispatchWhiteListConn(cfg, store, collector, socksServer, ipf, conn, port)
	}
}

func dispatchWhiteListConn(cfg config.Config, store *dynamic.Store, collector *stats.Collector, socksServer *socks.Server, ipf *filter.IPFilter, conn net.Conn, port int) {
	var first [1]byte
	if cfg.ReadTimeout > 0 {
		_ = conn.SetReadDeadline(time.Now().Add(cfg.ReadTimeout))
	}
	if _, err := conn.Read(first[:]); err != nil {
		conn.Close()
		return
	}
	wrapped := &prefixConn{Conn: conn, prefix: first[:]}
	if first[0] == 0x05 {
		socksServer.ServeConn(wrapped, func(c net.Conn) (dynamic.Context, bool) {
			return whiteListContext(store, c.RemoteAddr().String(), port, dynamic.ProtocolSOCKS5)
		})
		return
	}
	proxy := httpproxy.NewHTTPProxy(cfg, store, collector, ipf, func(r *http.Request) (dynamic.Context, bool) {
		return whiteListContext(store, r.RemoteAddr, port, dynamic.ProtocolHTTP)
	})
	srv := &http.Server{Handler: proxy, ReadTimeout: cfg.ReadTimeout, WriteTimeout: cfg.WriteTimeout, IdleTimeout: httpproxy.IdleTimeout(cfg)}
	_ = srv.Serve(&oneConnListener{conn: wrapped})
	_ = srv.Shutdown(context.Background())
}

func whiteListContext(store *dynamic.Store, remoteAddr string, bindPort int, protocol string) (dynamic.Context, bool) {
	remoteIP := httpproxy.RemoteIP(remoteAddr)
	snap := store.Snapshot()
	wl, ok := snap.WhiteList[remoteIP]
	if !ok {
		return dynamic.Context{}, false
	}
	info, ok := wl.Ports[bindPort]
	if !ok || !equalFold(info.Protocol, protocol) {
		return dynamic.Context{}, false
	}
	user, ok := snap.UsersByID[wl.UID]
	if !ok {
		return dynamic.Context{}, false
	}
	return dynamic.ContextForWhiteList(user, info, bindPort), true
}

func equalFold(a, b string) bool {
	if a == b {
		return true
	}
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		ca, cb := a[i], b[i]
		if ca >= 'A' && ca <= 'Z' {
			ca += 'a' - 'A'
		}
		if cb >= 'A' && cb <= 'Z' {
			cb += 'a' - 'A'
		}
		if ca != cb {
			return false
		}
	}
	return true
}

type prefixConn struct {
	net.Conn
	prefix []byte
}

func (c *prefixConn) Read(p []byte) (int, error) {
	if len(c.prefix) > 0 {
		n := copy(p, c.prefix)
		c.prefix = c.prefix[n:]
		return n, nil
	}
	return c.Conn.Read(p)
}

type oneConnListener struct {
	conn net.Conn
	done bool
}

func (l *oneConnListener) Accept() (net.Conn, error) {
	if l.done {
		return nil, net.ErrClosed
	}
	l.done = true
	return l.conn, nil
}

func (l *oneConnListener) Close() error { return nil }
func (l *oneConnListener) Addr() net.Addr {
	if l.conn != nil {
		return l.conn.LocalAddr()
	}
	return dummyAddr("")
}

type dummyAddr string

func (d dummyAddr) Network() string { return "tcp" }
func (d dummyAddr) String() string  { return string(d) }
