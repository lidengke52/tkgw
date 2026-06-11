package session

import (
	"sync"
	"time"
)

type Info struct {
	AuthUser    string
	SessionID   string
	ForwardHost string
	ForwardPort string
	ForwardUser string
	ForwardPass string
	Country     string
	State       string
	City        string
	SupplierID  int
	KeepTime    time.Duration
	BindTime    time.Time
}

type Store struct {
	mu   sync.RWMutex
	data map[string]Info
	max  int
}

func NewStore(max int) *Store {
	if max <= 0 {
		max = 50000
	}
	return &Store{data: make(map[string]Info), max: max}
}

func key(user, sid string) string { return user + "_" + sid }

func (s *Store) Get(user, sid string) (Info, bool) {
	s.mu.RLock()
	info, ok := s.data[key(user, sid)]
	s.mu.RUnlock()
	if !ok {
		return Info{}, false
	}
	if info.KeepTime > 0 && time.Since(info.BindTime) > info.KeepTime {
		s.Delete(user, sid)
		return Info{}, false
	}
	return info, true
}

func (s *Store) Put(info Info) {
	if info.SessionID == "" || info.KeepTime <= 0 {
		return
	}
	s.mu.Lock()
	if len(s.data) >= s.max {
		for k := range s.data {
			delete(s.data, k)
			break
		}
	}
	s.data[key(info.AuthUser, info.SessionID)] = info
	s.mu.Unlock()
}

func (s *Store) Delete(user, sid string) {
	s.mu.Lock()
	delete(s.data, key(user, sid))
	s.mu.Unlock()
}

func (s *Store) Sweep() {
	now := time.Now()
	s.mu.Lock()
	for k, info := range s.data {
		if info.KeepTime > 0 && now.Sub(info.BindTime) > info.KeepTime {
			delete(s.data, k)
		}
	}
	s.mu.Unlock()
}
