package filter

import (
	"log"
	"net"
	"net/netip"
	"path/filepath"
	"strings"

	"github.com/oschwald/geoip2-golang/v2"
)

type IPFilter struct {
	db *geoip2.Reader
}

func NewIPFilter(path string) *IPFilter {
	if path == "" {
		path = filepath.Join("resources", "Country-only-cn-private.mmdb")
	}
	db, err := geoip2.Open(path)
	if err != nil {
		log.Printf("ip geo filter disabled, open mmdb failed path=%s err=%v", path, err)
		return &IPFilter{}
	}
	log.Printf("ip geo filter loaded path=%s", path)
	return &IPFilter{db: db}
}

func (f *IPFilter) Blocked(remoteAddr string) bool {
	if f == nil || f.db == nil {
		return false
	}
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		host = remoteAddr
	}
	addr, err := netip.ParseAddr(host)
	if err != nil {
		return true
	}
	resp, err := f.db.Country(addr)
	if err != nil {
		log.Printf("ip geo lookup failed ip=%s err=%v", addr, err)
		return true
	}
	iso := resp.Country.ISOCode
	if strings.EqualFold(iso, "CN") {
		log.Printf("blocked connect from CN ip=%s", addr)
		return true
	}
	return false
}
