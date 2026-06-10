package stats

import (
	"bytes"
	"encoding/json"
	"log"
	"net/http"
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

type Collector struct {
	mu       sync.Mutex
	byUID    map[int]*counters
	endpoint string
	token    string
	hostname string
	client   *http.Client
	active   atomic.Int64
}

func New(endpoint, token, hostname string) *Collector {
	return &Collector{
		byUID:    make(map[int]*counters),
		endpoint: endpoint,
		token:    token,
		hostname: hostname,
		client:   &http.Client{Timeout: 30 * time.Second},
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
	for range t.C {
		c.report()
	}
}

func (c *Collector) report() {
	type userTraffic struct {
		ID       int   `json:"id"`
		UpLink   int64 `json:"upLink"`
		DownLink int64 `json:"downLink"`
	}
	var totalUp, totalDown int64
	var users []userTraffic
	c.mu.Lock()
	for uid, cc := range c.byUID {
		up := cc.sent.Swap(0)
		down := cc.recv.Swap(0)
		total := cc.total.Swap(0)
		success := cc.success.Swap(0)
		fail := cc.fail.Swap(0)
		if total+success+fail > 0 {
			log.Printf("request stats uid=%d total=%d success=%d fail=%d", uid, total, success, fail)
		}
		if up+down > 0 {
			totalUp += up
			totalDown += down
			users = append(users, userTraffic{ID: uid, UpLink: up, DownLink: down})
		}
	}
	c.mu.Unlock()
	if totalUp+totalDown == 0 || c.endpoint == "" {
		return
	}
	payload := map[string]any{
		"hostname": c.hostname,
		"totalTraffic": map[string]int64{
			"upLink":   totalUp,
			"downLink": totalDown,
		},
		"userTraffic": users,
	}
	body, _ := json.Marshal(payload)
	req, err := http.NewRequest(http.MethodPost, c.endpoint+"/api/admin/dynamic/trafficReport", bytes.NewReader(body))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.token)
	resp, err := c.client.Do(req)
	if err != nil {
		log.Printf("traffic report failed: %v", err)
		return
	}
	resp.Body.Close()
	log.Printf("traffic stats up=%d down=%d reportStatus=%d", totalUp, totalDown, resp.StatusCode)
}
