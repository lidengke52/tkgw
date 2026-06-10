package stats

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"sync/atomic"
	"time"
)

type counters struct {
	sent    atomic.Int64
	recv    atomic.Int64
	total   atomic.Int64
	success atomic.Int64
	fail    atomic.Int64
}

type UserTraffic struct {
	ID       int   `json:"id"`
	UpLink   int64 `json:"upLink"`
	DownLink int64 `json:"downLink"`
}

type Payload struct {
	Hostname     string `json:"hostname"`
	TotalTraffic struct {
		UpLink   int64 `json:"upLink"`
		DownLink int64 `json:"downLink"`
	} `json:"totalTraffic"`
	UserTraffic []UserTraffic `json:"userTraffic"`
}

type requestSnapshot struct {
	uid     int
	total   int64
	success int64
	fail    int64
}

type trafficSnapshot struct {
	payload  Payload
	requests []requestSnapshot
}

type Collector struct {
	mu        sync.Mutex
	byUID     map[int]*counters
	endpoint  string
	token     string
	hostname  string
	spoolFile string
	client    *http.Client
	active    atomic.Int64
}

func New(endpoint, token, hostname, spoolFile string) *Collector {
	return &Collector{
		byUID:     make(map[int]*counters),
		endpoint:  endpoint,
		token:     token,
		hostname:  hostname,
		spoolFile: spoolFile,
		client:    &http.Client{Timeout: 30 * time.Second},
	}
}

func (c *Collector) get(uid int) *counters {
	c.mu.Lock()
	cc := c.byUID[uid]
	if cc == nil {
		cc = &counters{}
		c.byUID[uid] = cc
	}
	c.mu.Unlock()
	return cc
}

func (c *Collector) Sent(uid int, n int64) { c.get(uid).sent.Add(n) }
func (c *Collector) Recv(uid int, n int64) { c.get(uid).recv.Add(n) }
func (c *Collector) Total(uid int)         { c.get(uid).total.Add(1) }
func (c *Collector) Success(uid int)       { c.get(uid).success.Add(1) }
func (c *Collector) Fail(uid int)          { c.get(uid).fail.Add(1) }
func (c *Collector) ConnOpen()             { c.active.Add(1) }
func (c *Collector) ConnClose()            { c.active.Add(-1) }
func (c *Collector) ActiveConnections() int64 {
	return c.active.Load()
}

func (c *Collector) Run(interval time.Duration) {
	if interval <= 0 {
		return
	}
	t := time.NewTicker(interval)
	defer t.Stop()
	for range t.C {
		c.ReportNow()
	}
}

func (c *Collector) ReportNow() {
	if c.endpoint == "" {
		return
	}
	c.flushSpool()
	snap := c.snapshotAndReset()
	if snap.empty() {
		return
	}
	if err := c.send(snap.payload); err != nil {
		log.Printf("traffic report failed: %v", err)
		if spoolErr := c.appendSpool(snap.payload); spoolErr != nil {
			log.Printf("traffic spool failed: %v", spoolErr)
			c.restore(snap)
		}
		return
	}
	log.Printf("traffic stats up=%d down=%d reportStatus=200", snap.payload.TotalTraffic.UpLink, snap.payload.TotalTraffic.DownLink)
}

func (c *Collector) snapshotAndReset() trafficSnapshot {
	snap := trafficSnapshot{payload: Payload{Hostname: c.hostname}}
	c.mu.Lock()
	for uid, cc := range c.byUID {
		up := cc.sent.Swap(0)
		down := cc.recv.Swap(0)
		total := cc.total.Swap(0)
		success := cc.success.Swap(0)
		fail := cc.fail.Swap(0)
		if total+success+fail > 0 {
			snap.requests = append(snap.requests, requestSnapshot{uid: uid, total: total, success: success, fail: fail})
			log.Printf("request stats uid=%d total=%d success=%d fail=%d", uid, total, success, fail)
		}
		if up+down > 0 {
			snap.payload.TotalTraffic.UpLink += up
			snap.payload.TotalTraffic.DownLink += down
			snap.payload.UserTraffic = append(snap.payload.UserTraffic, UserTraffic{ID: uid, UpLink: up, DownLink: down})
		}
	}
	c.mu.Unlock()
	return snap
}

func (s trafficSnapshot) empty() bool {
	return s.payload.TotalTraffic.UpLink+s.payload.TotalTraffic.DownLink == 0
}

func (c *Collector) restore(snap trafficSnapshot) {
	c.mu.Lock()
	for _, u := range snap.payload.UserTraffic {
		cc := c.byUID[u.ID]
		if cc == nil {
			cc = &counters{}
			c.byUID[u.ID] = cc
		}
		cc.sent.Add(u.UpLink)
		cc.recv.Add(u.DownLink)
	}
	for _, r := range snap.requests {
		cc := c.byUID[r.uid]
		if cc == nil {
			cc = &counters{}
			c.byUID[r.uid] = cc
		}
		cc.total.Add(r.total)
		cc.success.Add(r.success)
		cc.fail.Add(r.fail)
	}
	c.mu.Unlock()
}

func (c *Collector) send(payload Payload) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPost, c.endpoint+"/api/admin/dynamic/trafficReport", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.token)
	resp, err := c.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("traffic report status=%d", resp.StatusCode)
	}
	return nil
}

func (c *Collector) appendSpool(payload Payload) error {
	if c.spoolFile == "" {
		return errors.New("trafficReportSpoolFile is empty")
	}
	f, err := os.OpenFile(c.spoolFile, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()
	enc := json.NewEncoder(f)
	return enc.Encode(payload)
}

func (c *Collector) flushSpool() {
	if c.spoolFile == "" {
		return
	}
	f, err := os.Open(c.spoolFile)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			log.Printf("traffic spool open failed: %v", err)
		}
		return
	}
	defer f.Close()

	var remaining []Payload
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 64*1024), 16*1024*1024)
	for scanner.Scan() {
		var payload Payload
		if err := json.Unmarshal(scanner.Bytes(), &payload); err != nil {
			log.Printf("traffic spool decode failed: %v", err)
			continue
		}
		if err := c.send(payload); err != nil {
			remaining = append(remaining, payload)
			log.Printf("traffic spool resend failed: %v", err)
			break
		}
		log.Printf("traffic spool resent up=%d down=%d", payload.TotalTraffic.UpLink, payload.TotalTraffic.DownLink)
	}
	if err := scanner.Err(); err != nil {
		log.Printf("traffic spool scan failed: %v", err)
		return
	}
	for scanner.Scan() {
		var payload Payload
		if err := json.Unmarshal(scanner.Bytes(), &payload); err == nil {
			remaining = append(remaining, payload)
		}
	}
	if len(remaining) == 0 {
		if err := os.Remove(c.spoolFile); err != nil && !errors.Is(err, os.ErrNotExist) {
			log.Printf("traffic spool remove failed: %v", err)
		}
		return
	}
	if err := c.rewriteSpool(remaining); err != nil {
		log.Printf("traffic spool rewrite failed: %v", err)
	}
}

func (c *Collector) rewriteSpool(payloads []Payload) error {
	tmp := c.spoolFile + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	enc := json.NewEncoder(f)
	for _, payload := range payloads {
		if err := enc.Encode(payload); err != nil {
			f.Close()
			return err
		}
	}
	if err := f.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, c.spoolFile)
}
