package proxy

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strconv"
	"time"

	"ipproxy-dynamic-gateway-go/internal/config"
	"ipproxy-dynamic-gateway-go/internal/dynamic"
)

func DialViaSOCKS5(cfg config.Config, ctx dynamic.Context, targetHost string, targetPort int) (net.Conn, error) {
	dialer := net.Dialer{Timeout: cfg.ForwardConnectTimeout, KeepAlive: 30 * time.Second}
	upstream, err := dialer.Dial("tcp", net.JoinHostPort(ctx.ForwardHost, ctx.ForwardPort))
	if err != nil {
		return nil, err
	}
	if cfg.ForwardConnectTimeout > 0 {
		_ = upstream.SetDeadline(time.Now().Add(cfg.ForwardConnectTimeout))
	}
	if err := socks5Handshake(upstream, ctx.ForwardUser, ctx.ForwardPass, cfg.DNSRemote, targetHost, targetPort); err != nil {
		upstream.Close()
		return nil, err
	}
	_ = upstream.SetDeadline(time.Time{})
	return upstream, nil
}

func socks5Handshake(conn net.Conn, user, pass string, remoteDNS bool, host string, port int) error {
	r := bufio.NewReader(conn)
	if _, err := conn.Write([]byte{0x05, 0x01, 0x02}); err != nil {
		return err
	}
	resp := make([]byte, 2)
	if _, err := io.ReadFull(r, resp); err != nil {
		return err
	}
	if resp[0] != 0x05 || resp[1] != 0x02 {
		return fmt.Errorf("upstream auth method rejected")
	}
	if len(user) > 255 || len(pass) > 255 {
		return fmt.Errorf("upstream credentials too long")
	}
	auth := []byte{0x01, byte(len(user))}
	auth = append(auth, user...)
	auth = append(auth, byte(len(pass)))
	auth = append(auth, pass...)
	if _, err := conn.Write(auth); err != nil {
		return err
	}
	if _, err := io.ReadFull(r, resp); err != nil {
		return err
	}
	if resp[1] != 0x00 {
		return fmt.Errorf("upstream auth failed")
	}
	req, err := buildConnectRequest(remoteDNS, host, port)
	if err != nil {
		return err
	}
	if _, err := conn.Write(req); err != nil {
		return err
	}
	head := make([]byte, 4)
	if _, err := io.ReadFull(r, head); err != nil {
		return err
	}
	if head[1] != 0x00 {
		return fmt.Errorf("upstream connect failed status=%d", head[1])
	}
	if err := discardAddr(r, head[3]); err != nil {
		return err
	}
	_, err = r.Discard(2)
	return err
}

func buildConnectRequest(remoteDNS bool, host string, port int) ([]byte, error) {
	req := []byte{0x05, 0x01, 0x00}
	if !remoteDNS {
		if ips, err := net.LookupIP(host); err == nil && len(ips) > 0 {
			ip := ips[0]
			if v4 := ip.To4(); v4 != nil {
				req = append(req, 0x01)
				req = append(req, v4...)
				return appendPort(req, port), nil
			}
			if v6 := ip.To16(); v6 != nil {
				req = append(req, 0x04)
				req = append(req, v6...)
				return appendPort(req, port), nil
			}
		}
	}
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			req = append(req, 0x01)
			req = append(req, v4...)
			return appendPort(req, port), nil
		}
		req = append(req, 0x04)
		req = append(req, ip.To16()...)
		return appendPort(req, port), nil
	}
	if len(host) > 255 {
		return nil, fmt.Errorf("host too long")
	}
	req = append(req, 0x03, byte(len(host)))
	req = append(req, host...)
	return appendPort(req, port), nil
}

func appendPort(req []byte, port int) []byte {
	var p [2]byte
	binary.BigEndian.PutUint16(p[:], uint16(port))
	return append(req, p[:]...)
}

func discardAddr(r *bufio.Reader, atyp byte) error {
	switch atyp {
	case 0x01:
		_, err := r.Discard(4)
		return err
	case 0x04:
		_, err := r.Discard(16)
		return err
	case 0x03:
		n, err := r.ReadByte()
		if err != nil {
			return err
		}
		_, err = r.Discard(int(n))
		return err
	default:
		return fmt.Errorf("bad atyp %d", atyp)
	}
}

func PortStringToInt(port string) (int, error) {
	p, err := strconv.Atoi(port)
	if err != nil || p <= 0 || p > 65535 {
		return 0, fmt.Errorf("invalid port %q", port)
	}
	return p, nil
}
