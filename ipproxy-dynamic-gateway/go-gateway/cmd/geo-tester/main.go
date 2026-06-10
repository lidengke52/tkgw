package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/csv"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"math"
	"math/big"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	defaultListen       = "127.0.0.1:8099"
	defaultTimeout      = 20 * time.Second
	defaultIPAPIDelayMS = 1400
)

type proxyConfig struct {
	Host     string `json:"host"`
	Port     string `json:"port"`
	Username string `json:"username"`
	Password string `json:"password"`
}

type testRequest struct {
	Proxy           string `json:"proxy"`
	Provider        string `json:"provider"`
	Total           int    `json:"total"`
	Concurrency     int    `json:"concurrency"`
	DelayMS         int    `json:"delayMs"`
	ExpectedCity    string `json:"expectedCity"`
	IPInfoToken     string `json:"ipinfoToken"`
	RandomizeUser   bool   `json:"randomizeUser"`
	RandomizeSuffix string `json:"randomizeSuffix"`
}

type testResult struct {
	Index      int    `json:"index"`
	OK         bool   `json:"ok"`
	IP         string `json:"ip"`
	Country    string `json:"country"`
	Region     string `json:"region"`
	City       string `json:"city"`
	Provider   string `json:"provider"`
	Matched    bool   `json:"matched"`
	LatencyMS  int64  `json:"latencyMs"`
	Error      string `json:"error,omitempty"`
	Raw        string `json:"raw,omitempty"`
	StartedAt  string `json:"startedAt"`
	FinishedAt string `json:"finishedAt"`
}

type runSummary struct {
	Total        int            `json:"total"`
	Success      int            `json:"success"`
	Failed       int            `json:"failed"`
	Matched      int            `json:"matched"`
	UniqueIPs    int            `json:"uniqueIps"`
	CityCounts   map[string]int `json:"cityCounts"`
	ErrorCounts  map[string]int `json:"errorCounts"`
	ElapsedMS    int64          `json:"elapsedMs"`
	ExpectedCity string         `json:"expectedCity"`
}

type runResponse struct {
	Summary runSummary   `json:"summary"`
	Results []testResult `json:"results"`
}

func main() {
	listen := flag.String("listen", defaultListen, "HTTP listen address")
	flag.Parse()

	mux := http.NewServeMux()
	mux.HandleFunc("/", indexHandler)
	mux.HandleFunc("/api/parse-proxy", parseProxyHandler)
	mux.HandleFunc("/api/test", testHandler)
	mux.HandleFunc("/api/export.csv", exportCSVHandler)

	server := &http.Server{
		Addr:              *listen,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}

	log.Printf("geo tester listening on http://%s", *listen)
	log.Fatal(server.ListenAndServe())
}

func indexHandler(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = io.WriteString(w, indexHTML)
}

func parseProxyHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var body struct {
		Proxy string `json:"proxy"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	cfg, err := parseProxy(body.Proxy)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, cfg)
}

func testHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req testRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	resp, status, err := runTest(r.Context(), req)
	if err != nil {
		writeJSON(w, status, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

func exportCSVHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var results []testResult
	if err := json.NewDecoder(r.Body).Decode(&results); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="geo-test-results.csv"`)
	cw := csv.NewWriter(w)
	_ = cw.Write([]string{"index", "ok", "matched", "ip", "country", "region", "city", "provider", "latency_ms", "started_at", "finished_at", "error", "raw"})
	for _, item := range results {
		_ = cw.Write([]string{
			strconv.Itoa(item.Index),
			strconv.FormatBool(item.OK),
			strconv.FormatBool(item.Matched),
			item.IP,
			item.Country,
			item.Region,
			item.City,
			item.Provider,
			strconv.FormatInt(item.LatencyMS, 10),
			item.StartedAt,
			item.FinishedAt,
			item.Error,
			item.Raw,
		})
	}
	cw.Flush()
}

func runTest(ctx context.Context, req testRequest) (runResponse, int, error) {
	cfg, err := parseProxy(req.Proxy)
	if err != nil {
		return runResponse{}, http.StatusBadRequest, err
	}
	req.Provider = strings.ToLower(strings.TrimSpace(req.Provider))
	if req.Provider == "" {
		req.Provider = "ip-api"
	}
	if req.Provider != "ip-api" && req.Provider != "ipinfo" {
		return runResponse{}, http.StatusBadRequest, errors.New("provider must be ip-api or ipinfo")
	}
	if req.Total <= 0 {
		req.Total = 1000
	}
	if req.Total > 5000 {
		return runResponse{}, http.StatusBadRequest, errors.New("total cannot exceed 5000")
	}
	if req.Concurrency <= 0 {
		req.Concurrency = 1
	}
	if req.Concurrency > 50 {
		return runResponse{}, http.StatusBadRequest, errors.New("concurrency cannot exceed 50")
	}
	if req.DelayMS < 0 {
		req.DelayMS = 0
	}
	if req.Provider == "ip-api" && req.DelayMS == 0 {
		req.DelayMS = defaultIPAPIDelayMS
	}
	if req.ExpectedCity == "" {
		req.ExpectedCity = "Delhi"
	}

	started := time.Now()
	results := make([]testResult, req.Total)
	jobs := make(chan int)
	var wg sync.WaitGroup

	for worker := 0; worker < req.Concurrency; worker++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for index := range jobs {
				select {
				case <-ctx.Done():
					results[index-1] = failedResult(index, req.Provider, "request canceled")
					continue
				default:
				}
				proxy := cfg
				if req.RandomizeUser {
					proxy.Username = randomizeUsername(proxy.Username, req.RandomizeSuffix)
				}
				results[index-1] = fetchLocation(ctx, proxy, req, index)
			}
		}()
	}

	for i := 1; i <= req.Total; i++ {
		if req.DelayMS > 0 && i > 1 {
			timer := time.NewTimer(time.Duration(req.DelayMS) * time.Millisecond)
			select {
			case <-ctx.Done():
				timer.Stop()
				close(jobs)
				wg.Wait()
				return summarize(results, req.ExpectedCity, started), http.StatusOK, nil
			case <-timer.C:
			}
		}
		jobs <- i
	}
	close(jobs)
	wg.Wait()

	return summarize(results, req.ExpectedCity, started), http.StatusOK, nil
}

func parseProxy(input string) (proxyConfig, error) {
	input = strings.TrimSpace(input)
	if input == "" {
		return proxyConfig{}, errors.New("proxy is required")
	}
	hostPort, auth, ok := strings.Cut(input, "@")
	if !ok {
		return proxyConfig{}, errors.New("proxy format must be host:port@username:password")
	}
	host, port, err := net.SplitHostPort(hostPort)
	if err != nil {
		lastColon := strings.LastIndex(hostPort, ":")
		if lastColon <= 0 || lastColon == len(hostPort)-1 {
			return proxyConfig{}, errors.New("proxy host:port is invalid")
		}
		host = hostPort[:lastColon]
		port = hostPort[lastColon+1:]
	}
	username, password, ok := strings.Cut(auth, ":")
	if !ok {
		return proxyConfig{}, errors.New("proxy auth must be username:password")
	}
	if host == "" || port == "" || username == "" || password == "" {
		return proxyConfig{}, errors.New("proxy host, port, username, and password are required")
	}
	if _, err := strconv.Atoi(port); err != nil {
		return proxyConfig{}, errors.New("proxy port must be a number")
	}
	return proxyConfig{Host: host, Port: port, Username: username, Password: password}, nil
}

func fetchLocation(ctx context.Context, proxy proxyConfig, req testRequest, index int) testResult {
	started := time.Now()
	result := testResult{
		Index:     index,
		Provider:  req.Provider,
		StartedAt: started.Format(time.RFC3339),
	}

	client := &http.Client{
		Timeout: defaultTimeout,
		Transport: &http.Transport{
			Proxy:               nil,
			DialContext:         socks5DialContext(proxy, defaultTimeout),
			TLSHandshakeTimeout: 10 * time.Second,
			DisableKeepAlives:   true,
		},
	}

	url := providerURL(req)
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return finishError(result, started, err)
	}
	httpReq.Header.Set("User-Agent", "ipproxy-geo-tester/1.0")
	if req.Provider == "ipinfo" && strings.TrimSpace(req.IPInfoToken) != "" {
		httpReq.Header.Set("Authorization", "Bearer "+strings.TrimSpace(req.IPInfoToken))
	}

	resp, err := client.Do(httpReq)
	if err != nil {
		return finishError(result, started, err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return finishError(result, started, err)
	}
	result.Raw = string(body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return finishError(result, started, fmt.Errorf("provider returned HTTP %d", resp.StatusCode))
	}

	if err := decodeLocation(req.Provider, body, &result); err != nil {
		return finishError(result, started, err)
	}
	result.OK = true
	result.Matched = cityMatches(result.City, req.ExpectedCity)
	result.LatencyMS = time.Since(started).Milliseconds()
	result.FinishedAt = time.Now().Format(time.RFC3339)
	return result
}

func providerURL(req testRequest) string {
	if req.Provider == "ipinfo" {
		return "https://ipinfo.io/json"
	}
	return "http://ip-api.com/json/?fields=status,message,query,country,countryCode,regionName,city,isp,org,as,proxy,hosting"
}

func decodeLocation(provider string, body []byte, result *testResult) error {
	if provider == "ipinfo" {
		var payload struct {
			IP      string `json:"ip"`
			City    string `json:"city"`
			Region  string `json:"region"`
			Country string `json:"country"`
			Error   any    `json:"error"`
		}
		if err := json.Unmarshal(body, &payload); err != nil {
			return err
		}
		if payload.Error != nil {
			return fmt.Errorf("ipinfo error: %v", payload.Error)
		}
		result.IP = payload.IP
		result.City = payload.City
		result.Region = payload.Region
		result.Country = payload.Country
		return nil
	}

	var payload struct {
		Status     string `json:"status"`
		Message    string `json:"message"`
		Query      string `json:"query"`
		Country    string `json:"country"`
		RegionName string `json:"regionName"`
		City       string `json:"city"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		return err
	}
	if payload.Status != "success" {
		if payload.Message == "" {
			payload.Message = "ip-api status is not success"
		}
		return errors.New(payload.Message)
	}
	result.IP = payload.Query
	result.City = payload.City
	result.Region = payload.RegionName
	result.Country = payload.Country
	return nil
}

func socks5DialContext(proxy proxyConfig, timeout time.Duration) func(context.Context, string, string) (net.Conn, error) {
	return func(ctx context.Context, network, address string) (net.Conn, error) {
		dialer := &net.Dialer{Timeout: timeout}
		conn, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(proxy.Host, proxy.Port))
		if err != nil {
			return nil, err
		}
		if err := socks5Handshake(conn, proxy.Username, proxy.Password, address); err != nil {
			_ = conn.Close()
			return nil, err
		}
		return conn, nil
	}
}

func socks5Handshake(conn net.Conn, username, password, target string) error {
	_ = conn.SetDeadline(time.Now().Add(defaultTimeout))
	defer conn.SetDeadline(time.Time{})

	if len(username) > 255 || len(password) > 255 {
		return errors.New("SOCKS5 username/password cannot exceed 255 bytes")
	}
	if _, err := conn.Write([]byte{0x05, 0x01, 0x02}); err != nil {
		return err
	}
	header := make([]byte, 2)
	if _, err := io.ReadFull(conn, header); err != nil {
		return err
	}
	if header[0] != 0x05 || header[1] != 0x02 {
		return fmt.Errorf("SOCKS5 auth method rejected: version=%d method=%d", header[0], header[1])
	}

	auth := []byte{0x01, byte(len(username))}
	auth = append(auth, username...)
	auth = append(auth, byte(len(password)))
	auth = append(auth, password...)
	if _, err := conn.Write(auth); err != nil {
		return err
	}
	if _, err := io.ReadFull(conn, header); err != nil {
		return err
	}
	if header[1] != 0x00 {
		return fmt.Errorf("SOCKS5 auth failed: status=%d", header[1])
	}

	host, portText, err := net.SplitHostPort(target)
	if err != nil {
		return err
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > math.MaxUint16 {
		return fmt.Errorf("invalid target port: %s", portText)
	}
	req := []byte{0x05, 0x01, 0x00}
	if ip := net.ParseIP(host); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			req = append(req, 0x01)
			req = append(req, ip4...)
		} else {
			req = append(req, 0x04)
			req = append(req, ip.To16()...)
		}
	} else {
		if len(host) > 255 {
			return errors.New("target host is too long")
		}
		req = append(req, 0x03, byte(len(host)))
		req = append(req, host...)
	}
	req = append(req, byte(port>>8), byte(port))
	if _, err := conn.Write(req); err != nil {
		return err
	}

	reader := bufio.NewReader(conn)
	resp, err := reader.Peek(4)
	if err != nil {
		return err
	}
	if resp[0] != 0x05 || resp[1] != 0x00 {
		return fmt.Errorf("SOCKS5 connect failed: status=%d", resp[1])
	}
	_, _ = reader.Discard(4)
	switch resp[3] {
	case 0x01:
		_, err = reader.Discard(4 + 2)
	case 0x03:
		length, err := reader.ReadByte()
		if err != nil {
			return err
		}
		_, err = reader.Discard(int(length) + 2)
	case 0x04:
		_, err = reader.Discard(16 + 2)
	default:
		err = fmt.Errorf("SOCKS5 returned unknown address type: %d", resp[3])
	}
	return err
}

func randomizeUsername(username, suffix string) string {
	suffix = strings.TrimSpace(suffix)
	if suffix == "" {
		suffix = "-s"
	}
	buf := make([]byte, 6)
	if _, err := rand.Read(buf); err != nil {
		n, _ := rand.Int(rand.Reader, big.NewInt(999999))
		return username + suffix + n.String()
	}
	return username + suffix + base64.RawURLEncoding.EncodeToString(buf)
}

func summarize(results []testResult, expectedCity string, started time.Time) runResponse {
	summary := runSummary{
		Total:        len(results),
		CityCounts:   map[string]int{},
		ErrorCounts:  map[string]int{},
		ExpectedCity: expectedCity,
		ElapsedMS:    time.Since(started).Milliseconds(),
	}
	ips := map[string]struct{}{}
	for _, result := range results {
		if result.Index == 0 {
			continue
		}
		if result.OK {
			summary.Success++
			city := strings.TrimSpace(result.City)
			if city == "" {
				city = "(empty)"
			}
			summary.CityCounts[city]++
			if result.IP != "" {
				ips[result.IP] = struct{}{}
			}
			if result.Matched {
				summary.Matched++
			}
			continue
		}
		summary.Failed++
		errText := strings.TrimSpace(result.Error)
		if errText == "" {
			errText = "unknown error"
		}
		summary.ErrorCounts[errText]++
	}
	summary.UniqueIPs = len(ips)
	return runResponse{Summary: summary, Results: results}
}

func cityMatches(actual, expected string) bool {
	actual = strings.ToLower(strings.TrimSpace(actual))
	expected = strings.ToLower(strings.TrimSpace(expected))
	return actual != "" && expected != "" && strings.Contains(actual, expected)
}

func finishError(result testResult, started time.Time, err error) testResult {
	result.OK = false
	result.Error = err.Error()
	result.LatencyMS = time.Since(started).Milliseconds()
	result.FinishedAt = time.Now().Format(time.RFC3339)
	return result
}

func failedResult(index int, provider, msg string) testResult {
	now := time.Now().Format(time.RFC3339)
	return testResult{
		Index:      index,
		Provider:   provider,
		Error:      msg,
		StartedAt:  now,
		FinishedAt: now,
	}
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

const indexHTML = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>融合池 IP 定位测试</title>
  <style>
    :root {
      color-scheme: light;
      --bg: #f7f8fa;
      --panel: #ffffff;
      --text: #1f2933;
      --muted: #697586;
      --line: #d9dee7;
      --accent: #0b6bcb;
      --accent-dark: #074f97;
      --good: #16885a;
      --bad: #c23b3b;
      --warn: #b7791f;
      --shadow: 0 12px 28px rgba(32, 42, 54, .08);
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      background: var(--bg);
      color: var(--text);
      font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    header {
      padding: 28px 32px 18px;
      border-bottom: 1px solid var(--line);
      background: var(--panel);
    }
    h1 {
      margin: 0 0 8px;
      font-size: 26px;
      line-height: 1.2;
      font-weight: 760;
      letter-spacing: 0;
    }
    .sub {
      margin: 0;
      color: var(--muted);
      font-size: 14px;
      line-height: 1.6;
      max-width: 980px;
    }
    main {
      width: min(1480px, 100%);
      margin: 0 auto;
      padding: 22px 24px 36px;
    }
    .grid {
      display: grid;
      grid-template-columns: 380px minmax(0, 1fr);
      gap: 18px;
      align-items: start;
    }
    .panel {
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 8px;
      box-shadow: var(--shadow);
    }
    form.panel { padding: 18px; }
    label {
      display: block;
      margin: 0 0 6px;
      font-size: 12px;
      color: var(--muted);
      font-weight: 700;
    }
    input, select, textarea {
      width: 100%;
      min-height: 38px;
      padding: 8px 10px;
      border: 1px solid #cdd5df;
      border-radius: 6px;
      background: #fff;
      color: var(--text);
      font-size: 13px;
      outline: none;
    }
    textarea {
      min-height: 92px;
      resize: vertical;
      line-height: 1.45;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    }
    input:focus, select:focus, textarea:focus {
      border-color: var(--accent);
      box-shadow: 0 0 0 3px rgba(11, 107, 203, .12);
    }
    .field { margin-bottom: 13px; }
    .row {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 10px;
    }
    .check {
      display: flex;
      align-items: center;
      gap: 9px;
      margin: 10px 0 14px;
      color: var(--text);
      font-size: 13px;
    }
    .check input {
      width: 16px;
      min-height: 16px;
    }
    .actions {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
    }
    .pool-tools {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto;
      gap: 8px;
      align-items: end;
      margin-bottom: 12px;
    }
    .pool-tools .field {
      margin-bottom: 0;
    }
    .pool-help {
      margin: -4px 0 12px;
      color: var(--muted);
      font-size: 12px;
      line-height: 1.55;
    }
    button {
      min-height: 38px;
      border: 1px solid var(--accent);
      border-radius: 6px;
      padding: 8px 14px;
      background: var(--accent);
      color: white;
      font-weight: 750;
      font-size: 13px;
      cursor: pointer;
    }
    button.compact {
      padding-inline: 10px;
      white-space: nowrap;
    }
    button.secondary {
      background: #fff;
      color: var(--accent);
    }
    button:disabled {
      opacity: .55;
      cursor: not-allowed;
    }
    .status {
      padding: 13px 14px;
      border-bottom: 1px solid var(--line);
      display: flex;
      justify-content: space-between;
      gap: 14px;
      align-items: center;
    }
    .status strong {
      font-size: 14px;
    }
    .progress {
      width: 190px;
      height: 9px;
      border-radius: 999px;
      background: #e7ebf0;
      overflow: hidden;
    }
    .bar {
      width: 0%;
      height: 100%;
      background: var(--accent);
      transition: width .2s ease;
    }
    .metrics {
      display: grid;
      grid-template-columns: repeat(5, minmax(118px, 1fr));
      gap: 10px;
      padding: 14px;
      border-bottom: 1px solid var(--line);
    }
    .metric {
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: 12px;
      min-height: 74px;
      background: #fbfcfe;
    }
    .metric span {
      display: block;
      color: var(--muted);
      font-size: 12px;
      font-weight: 700;
    }
    .metric b {
      display: block;
      margin-top: 6px;
      font-size: 24px;
      line-height: 1.1;
      letter-spacing: 0;
    }
    .metric.good b { color: var(--good); }
    .metric.bad b { color: var(--bad); }
    .metric.warn b { color: var(--warn); }
    .split {
      display: grid;
      grid-template-columns: 300px minmax(0, 1fr);
      min-height: 560px;
    }
    .side {
      border-right: 1px solid var(--line);
      padding: 14px;
    }
    .side h2, .table-wrap h2 {
      margin: 0 0 10px;
      font-size: 15px;
      line-height: 1.3;
    }
    .counts {
      display: grid;
      gap: 8px;
      margin-bottom: 18px;
    }
    .count-row {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      padding: 9px 10px;
      border: 1px solid var(--line);
      border-radius: 6px;
      font-size: 13px;
      background: #fff;
    }
    .count-row span:first-child {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .table-wrap {
      padding: 14px;
      overflow: auto;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 12px;
      min-width: 900px;
    }
    th, td {
      padding: 8px 9px;
      border-bottom: 1px solid var(--line);
      text-align: left;
      vertical-align: top;
      line-height: 1.35;
    }
    th {
      position: sticky;
      top: 0;
      z-index: 1;
      background: #f3f6fa;
      color: #405162;
      font-size: 11px;
      text-transform: uppercase;
    }
    .ok { color: var(--good); font-weight: 750; }
    .miss { color: var(--bad); font-weight: 750; }
    .muted { color: var(--muted); }
    .note {
      margin-top: 12px;
      color: var(--muted);
      font-size: 12px;
      line-height: 1.55;
    }
    @media (max-width: 1040px) {
      header { padding: 22px 18px 16px; }
      main { padding: 16px; }
      .grid, .split { grid-template-columns: 1fr; }
      .side { border-right: 0; border-bottom: 1px solid var(--line); }
      .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .status { align-items: flex-start; flex-direction: column; }
      .progress { width: 100%; }
      .pool-tools { grid-template-columns: 1fr 1fr; }
      .pool-tools .field { grid-column: 1 / -1; }
    }
  </style>
</head>
<body>
  <header>
    <h1>融合池 IP 定位测试</h1>
    <p class="sub">通过本地后端走 SOCKS5 代理请求定位接口，适合批量验证 IN / Delhi / Delhi 这类城市定向是否稳定。ip-api 免费接口有频率限制，默认按约 45 次/分钟节流。</p>
  </header>
  <main>
    <div class="grid">
      <form class="panel" id="form">
        <div class="pool-tools">
          <div class="field">
            <label for="poolSelect">选择测试池</label>
            <select id="poolSelect">
              <option value="">未配置测试池</option>
            </select>
          </div>
          <button type="button" class="secondary compact" id="savePools">保存池</button>
          <button type="button" class="secondary compact" id="togglePools">编辑池</button>
        </div>
        <div class="field" id="poolEditor" hidden>
          <label for="poolText">测试池列表</label>
          <textarea id="poolText" spellcheck="false" placeholder="融合池-Delhi=host:port@username:password&#10;普通池-any=host:port@username:password"></textarea>
        </div>
        <p class="pool-help">池列表只保存在当前浏览器 localStorage；格式为每行一个：池名称=SOCKS5连接串。</p>
        <div class="field">
          <label for="proxy">SOCKS5 连接串</label>
          <input id="proxy" name="proxy" autocomplete="off" placeholder="host:port@username:password">
        </div>
        <div class="row">
          <div class="field">
            <label for="provider">定位接口</label>
            <select id="provider" name="provider">
              <option value="ip-api">ip-api.com</option>
              <option value="ipinfo">ipinfo.io</option>
            </select>
          </div>
          <div class="field">
            <label for="ipinfoToken">IPinfo Token</label>
            <input id="ipinfoToken" name="ipinfoToken" placeholder="可选">
          </div>
        </div>
        <div class="row">
          <div class="field">
            <label for="total">测试条数</label>
            <input id="total" name="total" type="number" min="1" max="5000" value="1000">
          </div>
          <div class="field">
            <label for="expectedCity">期望城市</label>
            <input id="expectedCity" name="expectedCity" value="Delhi">
          </div>
        </div>
        <div class="row">
          <div class="field">
            <label for="concurrency">并发</label>
            <input id="concurrency" name="concurrency" type="number" min="1" max="50" value="1">
          </div>
          <div class="field">
            <label for="delayMs">请求间隔(ms)</label>
            <input id="delayMs" name="delayMs" type="number" min="0" value="1400">
          </div>
        </div>
        <label class="check">
          <input id="randomizeUser" name="randomizeUser" type="checkbox">
          每次请求给用户名追加随机后缀
        </label>
        <div class="field">
          <label for="randomizeSuffix">随机后缀前缀</label>
          <input id="randomizeSuffix" name="randomizeSuffix" value="-s">
        </div>
        <div class="actions">
          <button type="submit" id="startBtn">开始测试</button>
          <button type="button" class="secondary" id="exportJson" disabled>导出 JSON</button>
          <button type="button" class="secondary" id="exportCsv" disabled>导出 CSV</button>
        </div>
        <p class="note">如果供应商连接串本身使用固定会话，可能会重复拿到同一个出口。随机后缀只适合后端供应商支持“用户名内会话后缀”的格式时使用。</p>
      </form>

      <section class="panel">
        <div class="status">
          <strong id="statusText">等待开始</strong>
          <div class="progress"><div class="bar" id="bar"></div></div>
        </div>
        <div class="metrics">
          <div class="metric"><span>总数</span><b id="mTotal">0</b></div>
          <div class="metric good"><span>成功</span><b id="mSuccess">0</b></div>
          <div class="metric bad"><span>失败</span><b id="mFailed">0</b></div>
          <div class="metric warn"><span>Delhi 命中</span><b id="mMatched">0</b></div>
          <div class="metric"><span>唯一 IP</span><b id="mUnique">0</b></div>
        </div>
        <div class="split">
          <aside class="side">
            <h2>城市分布</h2>
            <div class="counts" id="cityCounts"><div class="muted">暂无数据</div></div>
            <h2>错误分布</h2>
            <div class="counts" id="errorCounts"><div class="muted">暂无数据</div></div>
          </aside>
          <div class="table-wrap">
            <h2>明细</h2>
            <table>
              <thead>
                <tr>
                  <th>#</th>
                  <th>状态</th>
                  <th>IP</th>
                  <th>国家</th>
                  <th>地区</th>
                  <th>城市</th>
                  <th>耗时</th>
                  <th>错误</th>
                </tr>
              </thead>
              <tbody id="rows">
                <tr><td colspan="8" class="muted">运行后显示结果</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>
    </div>
  </main>
  <script>
    const form = document.querySelector('#form');
    const startBtn = document.querySelector('#startBtn');
    const exportJson = document.querySelector('#exportJson');
    const exportCsv = document.querySelector('#exportCsv');
    const poolSelect = document.querySelector('#poolSelect');
    const poolText = document.querySelector('#poolText');
    const poolEditor = document.querySelector('#poolEditor');
    const proxyInput = document.querySelector('#proxy');
    const rows = document.querySelector('#rows');
    const statusText = document.querySelector('#statusText');
    const bar = document.querySelector('#bar');
    let lastResults = [];
    const poolStorageKey = 'ipproxy.geoTester.pools.v1';

    function formPayload() {
      const data = new FormData(form);
      return {
        proxy: data.get('proxy'),
        provider: data.get('provider'),
        total: Number(data.get('total') || 1000),
        concurrency: Number(data.get('concurrency') || 1),
        delayMs: Number(data.get('delayMs') || 0),
        expectedCity: data.get('expectedCity') || 'Delhi',
        ipinfoToken: data.get('ipinfoToken') || '',
        randomizeUser: document.querySelector('#randomizeUser').checked,
        randomizeSuffix: data.get('randomizeSuffix') || '-s'
      };
    }

    function loadPoolText() {
      const saved = localStorage.getItem(poolStorageKey);
      poolText.value = saved || '';
      renderPoolSelect();
    }

    function parsePools() {
      return poolText.value.split('\n')
        .map(line => line.trim())
        .filter(Boolean)
        .map(line => {
          const eq = line.indexOf('=');
          if (eq < 1) return null;
          return {
            name: line.slice(0, eq).trim(),
            proxy: line.slice(eq + 1).trim()
          };
        })
        .filter(pool => pool && pool.name && pool.proxy);
    }

    function renderPoolSelect() {
      const pools = parsePools();
      poolSelect.innerHTML = '';
      if (!pools.length) {
        poolSelect.innerHTML = '<option value="">未配置测试池</option>';
        return;
      }
      for (const pool of pools) {
        const option = document.createElement('option');
        option.value = pool.proxy;
        option.textContent = pool.name;
        poolSelect.appendChild(option);
      }
      proxyInput.value = poolSelect.value;
    }

    function setMetrics(summary) {
      document.querySelector('#mTotal').textContent = summary.total || 0;
      document.querySelector('#mSuccess').textContent = summary.success || 0;
      document.querySelector('#mFailed').textContent = summary.failed || 0;
      document.querySelector('#mMatched').textContent = summary.matched || 0;
      document.querySelector('#mUnique').textContent = summary.uniqueIps || 0;
      const done = (summary.success || 0) + (summary.failed || 0);
      const total = summary.total || 1;
      bar.style.width = Math.min(100, Math.round(done / total * 100)) + '%';
    }

    function renderCounts(target, counts) {
      const box = document.querySelector(target);
      const entries = Object.entries(counts || {}).sort((a, b) => b[1] - a[1]);
      if (!entries.length) {
        box.innerHTML = '<div class="muted">暂无数据</div>';
        return;
      }
      box.innerHTML = entries.slice(0, 20).map(([name, count]) =>
        '<div class="count-row"><span title="' + escapeHTML(name) + '">' + escapeHTML(name) + '</span><b>' + count + '</b></div>'
      ).join('');
    }

    function renderRows(results) {
      if (!results.length) {
        rows.innerHTML = '<tr><td colspan="8" class="muted">运行后显示结果</td></tr>';
        return;
      }
      rows.innerHTML = results.map(item => {
        const status = item.ok ? (item.matched ? '<span class="ok">命中</span>' : '<span class="miss">偏差</span>') : '<span class="miss">失败</span>';
        return '<tr>' +
          '<td>' + item.index + '</td>' +
          '<td>' + status + '</td>' +
          '<td>' + escapeHTML(item.ip || '') + '</td>' +
          '<td>' + escapeHTML(item.country || '') + '</td>' +
          '<td>' + escapeHTML(item.region || '') + '</td>' +
          '<td>' + escapeHTML(item.city || '') + '</td>' +
          '<td>' + (item.latencyMs || 0) + ' ms</td>' +
          '<td>' + escapeHTML(item.error || '') + '</td>' +
        '</tr>';
      }).join('');
    }

    function escapeHTML(value) {
      return String(value).replace(/[&<>"']/g, ch => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;'
      }[ch]));
    }

    poolSelect.addEventListener('change', () => {
      proxyInput.value = poolSelect.value;
    });

    document.querySelector('#togglePools').addEventListener('click', () => {
      poolEditor.hidden = !poolEditor.hidden;
    });

    document.querySelector('#savePools').addEventListener('click', () => {
      localStorage.setItem(poolStorageKey, poolText.value);
      renderPoolSelect();
      statusText.textContent = '测试池已保存到当前浏览器';
    });

    poolText.addEventListener('input', renderPoolSelect);

    form.addEventListener('submit', async event => {
      event.preventDefault();
      startBtn.disabled = true;
      exportJson.disabled = true;
      exportCsv.disabled = true;
      statusText.textContent = '测试运行中，请保持页面打开';
      bar.style.width = '2%';
      rows.innerHTML = '<tr><td colspan="8" class="muted">正在请求定位接口...</td></tr>';
      try {
        const res = await fetch('/api/test', {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify(formPayload())
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || 'request failed');
        lastResults = data.results || [];
        setMetrics(data.summary || {});
        renderCounts('#cityCounts', data.summary.cityCounts || {});
        renderCounts('#errorCounts', data.summary.errorCounts || {});
        renderRows(lastResults);
        const rate = data.summary.total ? Math.round(data.summary.matched / data.summary.total * 10000) / 100 : 0;
        statusText.textContent = '完成：' + rate + '% 命中 ' + (data.summary.expectedCity || 'Delhi') + '，耗时 ' + Math.round((data.summary.elapsedMs || 0) / 1000) + ' 秒';
        exportJson.disabled = false;
        exportCsv.disabled = false;
      } catch (error) {
        statusText.textContent = '测试失败：' + error.message;
      } finally {
        startBtn.disabled = false;
      }
    });

    exportJson.addEventListener('click', () => {
      const blob = new Blob([JSON.stringify(lastResults, null, 2)], {type: 'application/json'});
      downloadBlob(blob, 'geo-test-results.json');
    });

    exportCsv.addEventListener('click', async () => {
      const res = await fetch('/api/export.csv', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(lastResults)
      });
      downloadBlob(await res.blob(), 'geo-test-results.csv');
    });

    function downloadBlob(blob, filename) {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    }

    loadPoolText();
  </script>
</body>
</html>`
