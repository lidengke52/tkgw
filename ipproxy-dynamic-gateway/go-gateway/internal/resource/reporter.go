package resource

import (
	"log"
	"runtime"
	"time"

	"ipproxy-dynamic-gateway-go/internal/stats"
)

func Run(interval time.Duration, collector *stats.Collector) {
	if interval <= 0 {
		log.Printf("resource usage reporter disabled, interval=%s", interval)
		return
	}
	ticker := time.NewTicker(interval)
	for range ticker.C {
		report(collector)
	}
}

func report(collector *stats.Collector) {
	var mem runtime.MemStats
	runtime.ReadMemStats(&mem)
	log.Printf(
		"resource usage heapAlloc=%s heapSys=%s stackSys=%s goroutines=%d gc=%d activeConnections=%d",
		formatBytes(mem.HeapAlloc),
		formatBytes(mem.HeapSys),
		formatBytes(mem.StackSys),
		runtime.NumGoroutine(),
		mem.NumGC,
		collector.ActiveConnections(),
	)
}

func formatBytes(bytes uint64) string {
	const unit = 1024
	if bytes < unit {
		return stringInt(bytes) + "B"
	}
	div, exp := uint64(unit), 0
	for n := bytes / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return stringFloat(float64(bytes)/float64(div)) + " " + string("KMGTPE"[exp]) + "B"
}

func stringInt(v uint64) string {
	if v == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	return string(buf[i:])
}

func stringFloat(v float64) string {
	x := uint64(v*100 + 0.5)
	return stringInt(x/100) + "." + twoDigits(x%100)
}

func twoDigits(v uint64) string {
	return string([]byte{byte('0' + v/10), byte('0' + v%10)})
}
