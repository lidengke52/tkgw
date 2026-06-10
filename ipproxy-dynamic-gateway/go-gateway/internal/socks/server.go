package socks

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"time"

	"ipproxy-dynamic-gateway-go/internal/config"
	"ipproxy-dynamic-gateway-go/internal/dynamic"
	"ipproxy-dynamic-gateway-go/internal/filter"
	"ipproxy-dynamic-gateway-go/internal/proxy"
	"ipproxy-dynamic-gateway-go/internal/stats"
)

type Server struct {
	cfg   config.Config
	store *dynamic.Store
	stats *stats.Collector
	ipf   *filter.IPFilter
}

func New(cfg config.Config, store *dynamic.Store, stats *stats.Collector, ipf *filter.IPFilter) *Server {
	return &Server{cfg: cfg, store: store, stats: stats, ipf: ipf}
}

func (s *Server) ListenAndServe(addr string, noAuthContext func(net.Conn) (dynamic.Context, bool)) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	log.Printf("socks5 listening on %s", addr)
	for {
		conn, err := ln.Accept()
		if err != nil {
			return err
		}
		go s.handle(conn, noAuthContext)
	}
}

func (s *Server) ServeConn(conn net.Conn, noAuthContext func(net.Conn) (dynamic.Context, bool)) {
	s.handle(conn, noAuthContext)
}

func (s *Server) handle(conn net.Conn, noAuthContext func(net.Conn) (dynamic.Context, bool)) {
	s.stats.ConnOpen()
	defer s.stats.ConnClose()
	defer conn.Close()
	if s.ipf != nil && s.ipf.Blocked(conn.RemoteAddr().String()) {
		return
	}
	if s.cfg.ReadTimeout > 0 {
		_ = conn.SetReadDeadline(time.Now().Add(s.cfg.ReadTimeout))
	}
	r := bufio.NewReader(conn)
	ctx, targetHost, targetPort, err := s.handshake(r, conn, noAuthContext)
	if err != nil {
		return
	}
	s.stats.Total(ctx.UID)
	if !s.store.BindForward(&ctx) {
		s.stats.Fail(ctx.UID)
		writeReply(conn, 0x01)
		return
	}
	if !s.store.TryAcquireUser(ctx.AuthUser) {
		s.stats.Fail(ctx.UID)
		writeReply(conn, 0x01)
		return
	}
	defer s.store.ReleaseUser(ctx.AuthUser)
	upstream, err := proxy.DialViaSOCKS5(s.cfg, ctx, targetHost, targetPort)
	if err != nil {
		log.Printf("socks upstream connect failed target=%s:%d err=%v", targetHost, targetPort, err)
		s.store.RecordForwardFailure(ctx, err.Error())
		s.stats.Fail(ctx.UID)
		writeReply(conn, 0x01)
		return
	}
	s.store.RecordForwardSuccess(ctx)
	defer upstream.Close()
	if err := writeReply(conn, 0x00); err != nil {
		s.stats.Fail(ctx.UID)
		return
	}
	s.stats.Success(ctx.UID)
	_ = conn.SetDeadline(time.Time{})
	proxy.Relay(conn, upstream, func(n int64) { s.stats.Sent(ctx.UID, n) }, func(n int64) { s.stats.Recv(ctx.UID, n) })
}

func (s *Server) handshake(r *bufio.Reader, conn net.Conn, noAuthContext func(net.Conn) (dynamic.Context, bool)) (dynamic.Context, string, int, error) {
	head := make([]byte, 2)
	if _, err := io.ReadFull(r, head); err != nil {
		return dynamic.Context{}, "", 0, err
	}
	if head[0] != 0x05 {
		return dynamic.Context{}, "", 0, fmt.Errorf("not socks5")
	}
	methods := make([]byte, int(head[1]))
	if _, err := io.ReadFull(r, methods); err != nil {
		return dynamic.Context{}, "", 0, err
	}
	var ctx dynamic.Context
	if noAuthContext != nil {
		var ok bool
		ctx, ok = noAuthContext(conn)
		if !ok || !hasMethod(methods, 0x00) {
			conn.Write([]byte{0x05, 0xff})
			return dynamic.Context{}, "", 0, fmt.Errorf("no auth rejected")
		}
		conn.Write([]byte{0x05, 0x00})
	} else {
		if !hasMethod(methods, 0x02) {
			conn.Write([]byte{0x05, 0xff})
			return dynamic.Context{}, "", 0, fmt.Errorf("password auth required")
		}
		conn.Write([]byte{0x05, 0x02})
		auth, err := readPasswordAuth(r)
		if err != nil {
			return dynamic.Context{}, "", 0, err
		}
		var ok bool
		ctx, ok = s.store.Authenticate(auth.user, auth.pass)
		if !ok {
			conn.Write([]byte{0x01, 0x01})
			return dynamic.Context{}, "", 0, fmt.Errorf("auth failed")
		}
		conn.Write([]byte{0x01, 0x00})
	}
	host, port, err := readConnectRequest(r)
	return ctx, host, port, err
}

type passwordAuth struct{ user, pass string }

func readPasswordAuth(r *bufio.Reader) (passwordAuth, error) {
	v, err := r.ReadByte()
	if err != nil || v != 0x01 {
		return passwordAuth{}, fmt.Errorf("bad auth version")
	}
	ulen, err := r.ReadByte()
	if err != nil {
		return passwordAuth{}, err
	}
	u := make([]byte, int(ulen))
	if _, err := io.ReadFull(r, u); err != nil {
		return passwordAuth{}, err
	}
	plen, err := r.ReadByte()
	if err != nil {
		return passwordAuth{}, err
	}
	p := make([]byte, int(plen))
	if _, err := io.ReadFull(r, p); err != nil {
		return passwordAuth{}, err
	}
	return passwordAuth{user: string(u), pass: string(p)}, nil
}

func readConnectRequest(r *bufio.Reader) (string, int, error) {
	h := make([]byte, 4)
	if _, err := io.ReadFull(r, h); err != nil {
		return "", 0, err
	}
	if h[0] != 0x05 || h[1] != 0x01 {
		return "", 0, fmt.Errorf("unsupported socks command")
	}
	host, err := readAddr(r, h[3])
	if err != nil {
		return "", 0, err
	}
	var p [2]byte
	if _, err := io.ReadFull(r, p[:]); err != nil {
		return "", 0, err
	}
	return host, int(binary.BigEndian.Uint16(p[:])), nil
}

func readAddr(r *bufio.Reader, atyp byte) (string, error) {
	switch atyp {
	case 0x01:
		b := make([]byte, 4)
		_, err := io.ReadFull(r, b)
		return net.IP(b).String(), err
	case 0x04:
		b := make([]byte, 16)
		_, err := io.ReadFull(r, b)
		return net.IP(b).String(), err
	case 0x03:
		n, err := r.ReadByte()
		if err != nil {
			return "", err
		}
		b := make([]byte, int(n))
		_, err = io.ReadFull(r, b)
		return string(b), err
	default:
		return "", fmt.Errorf("bad address type")
	}
}

func writeReply(conn net.Conn, status byte) error {
	_, err := conn.Write([]byte{0x05, status, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	return err
}

func hasMethod(methods []byte, method byte) bool {
	for _, m := range methods {
		if m == method {
			return true
		}
	}
	return false
}

func PortFromConn(c net.Conn) (int, bool) {
	addr, ok := c.LocalAddr().(*net.TCPAddr)
	if !ok {
		return 0, false
	}
	return addr.Port, true
}

func Join(host string, port int) string {
	return net.JoinHostPort(host, strconv.Itoa(port))
}
