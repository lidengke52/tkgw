package health

import (
	"log"
	"sync"
	"time"
)

type Config struct {
	Enabled                  bool
	MinSamples               int
	FailureRateThreshold     float64
	ConsecutiveFailures      int
	Cooldown                 time.Duration
	Window                   time.Duration
	HalfOpenSuccessThreshold int
}

func DefaultConfig() Config {
	return Config{
		Enabled:                  true,
		MinSamples:               20,
		FailureRateThreshold:     0.50,
		ConsecutiveFailures:      5,
		Cooldown:                 60 * time.Second,
		Window:                   time.Minute,
		HalfOpenSuccessThreshold: 3,
	}
}

type Key struct {
	SupplierID int
	Endpoint   string
	Country    string
}

type Store struct {
	mu     sync.Mutex
	cfg    Config
	states map[Key]*state
}

type state struct {
	events              []event
	consecutiveFailures int
	unhealthyUntil      time.Time
	halfOpenSuccesses   int
	lastReason          string
}

type event struct {
	at      time.Time
	success bool
}

func New(cfg Config) *Store {
	if cfg.MinSamples <= 0 {
		cfg.MinSamples = 20
	}
	if cfg.FailureRateThreshold <= 0 || cfg.FailureRateThreshold > 1 {
		cfg.FailureRateThreshold = 0.50
	}
	if cfg.ConsecutiveFailures <= 0 {
		cfg.ConsecutiveFailures = 5
	}
	if cfg.Cooldown <= 0 {
		cfg.Cooldown = 60 * time.Second
	}
	if cfg.Window <= 0 {
		cfg.Window = time.Minute
	}
	if cfg.HalfOpenSuccessThreshold <= 0 {
		cfg.HalfOpenSuccessThreshold = 3
	}
	return &Store{cfg: cfg, states: make(map[Key]*state)}
}

func (s *Store) Enabled() bool {
	return s != nil && s.cfg.Enabled
}

func (s *Store) Healthy(key Key) bool {
	if !s.Enabled() {
		return true
	}
	now := time.Now()
	s.mu.Lock()
	st := s.states[key]
	if st == nil {
		s.mu.Unlock()
		return true
	}
	healthy := !now.Before(st.unhealthyUntil)
	s.mu.Unlock()
	return healthy
}

func (s *Store) RecordSuccess(key Key) {
	if !s.Enabled() {
		return
	}
	now := time.Now()
	s.mu.Lock()
	st := s.get(key)
	st.events = append(st.events, event{at: now, success: true})
	st.trim(now, s.cfg.Window)
	st.consecutiveFailures = 0
	if !st.unhealthyUntil.IsZero() && !now.Before(st.unhealthyUntil) {
		st.halfOpenSuccesses++
		if st.halfOpenSuccesses >= s.cfg.HalfOpenSuccessThreshold {
			st.unhealthyUntil = time.Time{}
			st.halfOpenSuccesses = 0
			st.lastReason = ""
			log.Printf("supplier recovered supplierId=%d endpoint=%s country=%s", key.SupplierID, key.Endpoint, key.Country)
		}
	}
	s.mu.Unlock()
}

func (s *Store) RecordFailure(key Key, reason string) {
	if !s.Enabled() {
		return
	}
	now := time.Now()
	s.mu.Lock()
	st := s.get(key)
	st.events = append(st.events, event{at: now, success: false})
	st.trim(now, s.cfg.Window)
	st.consecutiveFailures++
	st.halfOpenSuccesses = 0
	st.lastReason = reason
	failures, total := st.failureStats()
	failureRate := 0.0
	if total > 0 {
		failureRate = float64(failures) / float64(total)
	}
	if st.consecutiveFailures >= s.cfg.ConsecutiveFailures ||
		(total >= s.cfg.MinSamples && failureRate >= s.cfg.FailureRateThreshold) {
		until := now.Add(s.cfg.Cooldown)
		if until.After(st.unhealthyUntil) {
			st.unhealthyUntil = until
			log.Printf("supplier unhealthy supplierId=%d endpoint=%s country=%s consecutiveFailures=%d failureRate=%.2f samples=%d reason=%s cooldown=%s",
				key.SupplierID, key.Endpoint, key.Country, st.consecutiveFailures, failureRate, total, reason, s.cfg.Cooldown)
		}
	}
	s.mu.Unlock()
}

func (s *Store) Snapshot() []Status {
	if !s.Enabled() {
		return nil
	}
	now := time.Now()
	s.mu.Lock()
	out := make([]Status, 0, len(s.states))
	for key, st := range s.states {
		st.trim(now, s.cfg.Window)
		failures, total := st.failureStats()
		out = append(out, Status{
			Key:                 key,
			Samples:             total,
			Failures:            failures,
			ConsecutiveFailures: st.consecutiveFailures,
			Healthy:             !now.Before(st.unhealthyUntil),
			UnhealthyUntil:      st.unhealthyUntil,
			LastReason:          st.lastReason,
		})
	}
	s.mu.Unlock()
	return out
}

type Status struct {
	Key                 Key
	Samples             int
	Failures            int
	ConsecutiveFailures int
	Healthy             bool
	UnhealthyUntil      time.Time
	LastReason          string
}

func (s *Store) get(key Key) *state {
	st := s.states[key]
	if st == nil {
		st = &state{}
		s.states[key] = st
	}
	return st
}

func (s *state) trim(now time.Time, window time.Duration) {
	cutoff := now.Add(-window)
	idx := 0
	for idx < len(s.events) && s.events[idx].at.Before(cutoff) {
		idx++
	}
	if idx > 0 {
		copy(s.events, s.events[idx:])
		s.events = s.events[:len(s.events)-idx]
	}
}

func (s *state) failureStats() (failures, total int) {
	total = len(s.events)
	for _, e := range s.events {
		if !e.success {
			failures++
		}
	}
	return failures, total
}
