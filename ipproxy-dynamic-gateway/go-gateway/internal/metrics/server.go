package metrics

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"runtime"
	"strconv"
	"strings"
	"time"

	"ipproxy-dynamic-gateway-go/internal/config"
	"ipproxy-dynamic-gateway-go/internal/dynamic"
	"ipproxy-dynamic-gateway-go/internal/stats"
)

type Server struct {
	cfg   config.Config
	store *dynamic.Store
	stats *stats.Collector
	start time.Time
}

func New(cfg config.Config, store *dynamic.Store, stats *stats.Collector) *Server {
	return &Server{cfg: cfg, store: store, stats: stats, start: time.Now()}
}

func (s *Server) ListenAndServe() error {
	if !s.cfg.MetricsEnabled {
		return nil
	}
	path := s.cfg.MetricsPath
	if path == "" {
		path = "/metrics"
	}
	mux := http.NewServeMux()
	mux.HandleFunc(path, s.handle)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok\n"))
	})
	addr := fmt.Sprintf("%s:%d", s.cfg.MetricsHost, s.cfg.MetricsPort)
	log.Printf("metrics listening on %s path=%s", addr, path)
	return http.ListenAndServe(addr, mux)
}

func (s *Server) handle(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	var b strings.Builder
	s.writeRuntime(&b)
	s.writeStats(&b)
	s.writeHealth(&b)
	s.writeConfig(&b)
	_, _ = w.Write([]byte(b.String()))
}

func (s *Server) writeRuntime(b *strings.Builder) {
	var mem runtime.MemStats
	runtime.ReadMemStats(&mem)
	writeGauge(b, "gateway_uptime_seconds", nil, time.Since(s.start).Seconds())
	writeGauge(b, "gateway_goroutines", nil, float64(runtime.NumGoroutine()))
	writeGauge(b, "gateway_heap_alloc_bytes", nil, float64(mem.HeapAlloc))
	writeGauge(b, "gateway_heap_sys_bytes", nil, float64(mem.HeapSys))
	writeGauge(b, "gateway_stack_sys_bytes", nil, float64(mem.StackSys))
	writeCounter(b, "gateway_gc_cycles_total", nil, float64(mem.NumGC))
	if n, ok := openFDs(); ok {
		writeGauge(b, "gateway_open_fds", nil, float64(n))
	}
}

func (s *Server) writeStats(b *strings.Builder) {
	snap := s.stats.MetricsSnapshot()
	writeGauge(b, "gateway_active_connections", nil, float64(snap.ActiveConnections))
	for _, u := range snap.Users {
		uid := strconv.Itoa(u.UID)
		writeCounter(b, "gateway_requests_total", map[string]string{"uid": uid, "status": "total"}, float64(u.Total))
		writeCounter(b, "gateway_requests_total", map[string]string{"uid": uid, "status": "success"}, float64(u.Success))
		writeCounter(b, "gateway_requests_total", map[string]string{"uid": uid, "status": "fail"}, float64(u.Fail))
		writeCounter(b, "gateway_traffic_bytes_total", map[string]string{"uid": uid, "direction": "up"}, float64(u.Sent))
		writeCounter(b, "gateway_traffic_bytes_total", map[string]string{"uid": uid, "direction": "down"}, float64(u.Recv))
	}
	if s.cfg.TrafficReportSpoolFile != "" {
		if st, err := os.Stat(s.cfg.TrafficReportSpoolFile); err == nil {
			writeGauge(b, "gateway_traffic_spool_bytes", nil, float64(st.Size()))
		} else {
			writeGauge(b, "gateway_traffic_spool_bytes", nil, 0)
		}
	}
}

func (s *Server) writeHealth(b *strings.Builder) {
	for _, st := range s.store.Health().Snapshot() {
		labels := map[string]string{
			"supplier": strconv.Itoa(st.Key.SupplierID),
			"endpoint": st.Key.Endpoint,
			"country":  st.Key.Country,
		}
		healthy := 0.0
		if st.Healthy {
			healthy = 1
		}
		writeGauge(b, "gateway_supplier_healthy", labels, healthy)
		writeGauge(b, "gateway_supplier_health_samples", labels, float64(st.Samples))
		writeGauge(b, "gateway_supplier_health_failures", labels, float64(st.Failures))
		writeGauge(b, "gateway_supplier_consecutive_failures", labels, float64(st.ConsecutiveFailures))
	}
}

func (s *Server) writeConfig(b *strings.Builder) {
	snap := s.store.Snapshot()
	writeGauge(b, "gateway_dynamic_users", nil, float64(len(snap.UsersByName)))
	writeGauge(b, "gateway_dynamic_suppliers", nil, float64(len(snap.Suppliers)))
	writeGauge(b, "gateway_dynamic_whitelist_ips", nil, float64(len(snap.WhiteList)))
	writeGauge(b, "gateway_dynamic_area_mappings", nil, float64(len(snap.AreaMapping)))
}

func writeGauge(b *strings.Builder, name string, labels map[string]string, value float64) {
	writeMetric(b, name, labels, value)
}

func writeCounter(b *strings.Builder, name string, labels map[string]string, value float64) {
	writeMetric(b, name, labels, value)
}

func writeMetric(b *strings.Builder, name string, labels map[string]string, value float64) {
	b.WriteString(name)
	if len(labels) > 0 {
		b.WriteByte('{')
		i := 0
		for k, v := range labels {
			if i > 0 {
				b.WriteByte(',')
			}
			b.WriteString(k)
			b.WriteString(`="`)
			b.WriteString(escapeLabel(v))
			b.WriteByte('"')
			i++
		}
		b.WriteByte('}')
	}
	b.WriteByte(' ')
	b.WriteString(strconv.FormatFloat(value, 'f', -1, 64))
	b.WriteByte('\n')
}

func escapeLabel(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	s = strings.ReplaceAll(s, "\n", `\n`)
	return strings.ReplaceAll(s, `"`, `\"`)
}

func openFDs() (int, bool) {
	entries, err := os.ReadDir("/proc/self/fd")
	if err == nil {
		return len(entries), true
	}
	entries, err = os.ReadDir("/dev/fd")
	if err == nil {
		return len(entries), true
	}
	return 0, false
}
