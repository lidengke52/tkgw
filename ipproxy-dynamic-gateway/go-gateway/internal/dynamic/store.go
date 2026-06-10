package dynamic

import (
	"bytes"
	"compress/gzip"
	"encoding/base64"
	"encoding/binary"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"hash/fnv"
	"io"
	"log"
	"math/rand"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"ipproxy-dynamic-gateway-go/internal/config"
	"ipproxy-dynamic-gateway-go/internal/health"
	"ipproxy-dynamic-gateway-go/internal/session"
)

const (
	ProtocolHTTP   = "http"
	ProtocolSOCKS5 = "socks5"
	CountryAny     = "ANY"

	AffinityCache         = "cache"
	AffinityDeterministic = "deterministic"
)

const mergePoolInfatica = 16

var mergePool = map[int]bool{16: true, 17: true}

type Store struct {
	cfg      config.Config
	client   *http.Client
	sessions *session.Store
	health   *health.Store
	snapshot atomic.Value
	userMu   sync.Mutex
	userConn map[string]int
}

type Context struct {
	RawConnectUser string
	UID            int
	AuthUser       string
	AuthPass       string
	Country        string
	State          string
	City           string
	SessionID      string
	KeepTime       time.Duration
	ForwardHost    string
	ForwardPort    string
	ForwardUser    string
	ForwardPass    string
	SupplierID     int
}

type candidate struct {
	supplierID int
	endpoint   Endpoint
}

func NewStore(cfg config.Config) *Store {
	s := &Store{
		cfg:      cfg,
		client:   &http.Client{Timeout: 30 * time.Second},
		sessions: session.NewStore(cfg.MaxSessionSize),
		health: health.New(health.Config{
			Enabled:              cfg.SupplierHealthEnabled,
			MinSamples:           cfg.SupplierHealthMinSamples,
			FailureRateThreshold: cfg.SupplierHealthFailureRate,
			ConsecutiveFailures:  cfg.SupplierHealthConsecutiveFailures,
			Cooldown:             cfg.SupplierHealthCooldown,
			Window:               cfg.SupplierHealthWindow,
		}),
		userConn: make(map[string]int),
	}
	s.snapshot.Store(Snapshot{
		UsersByName: map[string]UserConfig{},
		UsersByID:   map[int]UserConfig{},
		Suppliers:   map[int]SupplierConfig{},
		WhiteList:   map[string]WhiteListConfig{},
		AreaMapping: map[int]AreaMappingConfig{},
	})
	return s
}

func (s *Store) Snapshot() Snapshot { return s.snapshot.Load().(Snapshot) }

func (s *Store) Health() *health.Store { return s.health }

func (s *Store) RecordForwardSuccess(ctx Context) {
	if s.health != nil {
		s.health.RecordSuccess(forwardHealthKey(ctx))
	}
}

func (s *Store) RecordForwardFailure(ctx Context, reason string) {
	if s.health != nil {
		s.health.RecordFailure(forwardHealthKey(ctx), reason)
	}
	if ctx.SessionID != "" {
		s.sessions.Delete(ctx.AuthUser, ctx.SessionID)
	}
}

func forwardHealthKey(ctx Context) health.Key {
	return health.Key{
		SupplierID: ctx.SupplierID,
		Endpoint:   ctx.ForwardHost + ":" + ctx.ForwardPort,
		Country:    strings.ToUpper(ctx.Country),
	}
}

func (s *Store) Run() {
	if err := s.Refresh(); err != nil {
		log.Printf("initial dynamic config load failed: %v", err)
	}
	if s.cfg.ConfigUpdateInterval > 0 {
		go func() {
			t := time.NewTicker(s.cfg.ConfigUpdateInterval)
			for range t.C {
				if err := s.Refresh(); err != nil {
					log.Printf("dynamic config refresh failed: %v", err)
				}
			}
		}()
	}
	if s.cfg.SessionExpireCheckInterval > 0 {
		go func() {
			t := time.NewTicker(s.cfg.SessionExpireCheckInterval)
			for range t.C {
				s.sessions.Sweep()
			}
		}()
	}
}

func (s *Store) Refresh() error {
	if s.cfg.LocalConfigFile {
		s.snapshot.Store(localSnapshot())
		log.Printf("loaded local demo dynamic config")
		return nil
	}
	users, err := s.fetchUsers()
	if err != nil {
		return err
	}
	suppliers, err := s.fetchSuppliers()
	if err != nil {
		return err
	}
	white, err := s.fetchWhiteList()
	if err != nil {
		log.Printf("white list fetch failed: %v", err)
	}
	snap := Snapshot{
		UsersByName: make(map[string]UserConfig),
		UsersByID:   make(map[int]UserConfig),
		Suppliers:   make(map[int]SupplierConfig),
		WhiteList:   make(map[string]WhiteListConfig),
		AreaMapping: make(map[int]AreaMappingConfig),
	}
	for _, u := range users {
		snap.UsersByName[u.AuthUser] = u
		snap.UsersByID[u.UserID] = u
	}
	for _, sup := range suppliers {
		sup.AvailableGateway = preferEndpoints(sup.AvailableGateway, s.cfg.PreferenceEndpointArea)
		snap.Suppliers[sup.SupplierID] = sup
	}
	for _, w := range white {
		snap.WhiteList[w.IP] = w
	}
	snap.AreaMapping = loadAreaMappings(s.cfg)
	s.snapshot.Store(snap)
	log.Printf("dynamic config refreshed users=%d suppliers=%d whitelist=%d areaMappings=%d", len(snap.UsersByName), len(snap.Suppliers), len(snap.WhiteList), len(snap.AreaMapping))
	return nil
}

func (s *Store) Authenticate(username, password string) (Context, bool) {
	auth, ok := ParseAuth(username, password)
	if !ok {
		return Context{}, false
	}
	u, ok := s.Snapshot().UsersByName[auth.AuthUser]
	if !ok || !u.TrafficEnable || u.AuthPass != auth.AuthPass {
		return Context{}, false
	}
	auth.UID = u.UserID
	return auth, true
}

func (s *Store) BindForward(ctx *Context) bool {
	snap := s.Snapshot()
	user, ok := snap.UsersByName[ctx.AuthUser]
	if !ok || !user.TrafficEnable || len(user.AvailableSupplier) == 0 {
		return false
	}
	if ctx.SessionID != "" {
		if len(ctx.SessionID) >= 20 || !isNumeric(ctx.SessionID) || ctx.KeepTime <= 0 {
			return false
		}
		if info, ok := s.sessions.Get(ctx.AuthUser, ctx.SessionID); ok {
			if s.shouldUseCachedSession(snap, user, ctx, info) {
				applySession(ctx, info)
				return true
			}
			s.sessions.Delete(ctx.AuthUser, ctx.SessionID)
		}
		info, ok := s.allocate(snap, user, *ctx, true)
		if !ok {
			return false
		}
		s.sessions.Put(info)
		applySession(ctx, info)
		return true
	}
	info, ok := s.allocate(snap, user, *ctx, false)
	if !ok {
		return false
	}
	applySession(ctx, info)
	return true
}

func (s *Store) TryAcquireUser(user string) bool {
	if user == "" || s.cfg.UserMaxConcurrentConns <= 0 {
		return true
	}
	s.userMu.Lock()
	defer s.userMu.Unlock()
	if s.userConn[user] >= s.cfg.UserMaxConcurrentConns {
		return false
	}
	s.userConn[user]++
	return true
}

func (s *Store) ReleaseUser(user string) {
	if user == "" || s.cfg.UserMaxConcurrentConns <= 0 {
		return
	}
	s.userMu.Lock()
	if s.userConn[user] <= 1 {
		delete(s.userConn, user)
	} else {
		s.userConn[user]--
	}
	s.userMu.Unlock()
}

func (s *Store) allocate(snap Snapshot, user UserConfig, ctx Context, sticky bool) (session.Info, bool) {
	supplierID, ep, ok := s.pickCandidate(snap, user, ctx, sticky)
	if !ok {
		return session.Info{}, false
	}
	supplier, ok := snap.Suppliers[supplierID]
	if !ok {
		return session.Info{}, false
	}
	country, state, city := ctx.Country, ctx.State, ctx.City
	if !strings.EqualFold(country, CountryAny) && mergePool[supplierID] {
		areaInfo, ok := lookupArea(snap.AreaMapping[supplierID], country, state, city)
		if !ok {
			return session.Info{}, false
		}
		country, state, city = areaInfo.Country, areaInfo.State, areaInfo.City
	}
	format := selectAuthFormat(supplier, country, state, city, sticky)
	if format == "" || !strings.Contains(format, ":") {
		return session.Info{}, false
	}
	country = mapCountryCase(country, supplier.AreaCaseType)
	auth := strings.NewReplacer(
		"{user}", supplier.AuthUser,
		"{password}", supplier.AuthPass,
		"{session}", sessionToken(ctx, supplierID, sticky, s.deterministicAffinity()),
		"{country}", country,
		"{state}", state,
		"{city}", city,
	).Replace(format)
	idx := strings.IndexByte(auth, ':')
	if idx <= 0 {
		return session.Info{}, false
	}
	return session.Info{
		AuthUser:    ctx.AuthUser,
		SessionID:   ctx.SessionID,
		ForwardHost: ep.Host,
		ForwardPort: ep.Port,
		ForwardUser: auth[:idx],
		ForwardPass: auth[idx+1:],
		SupplierID:  supplierID,
		KeepTime:    ctx.KeepTime,
		BindTime:    time.Now(),
	}, true
}

func (s *Store) pickCandidate(snap Snapshot, user UserConfig, ctx Context, sticky bool) (int, Endpoint, bool) {
	allowed := user.AvailableSupplier
	if sticky && ctx.KeepTime > 30*time.Minute {
		for _, id := range user.AvailableSupplier {
			if id == mergePoolInfatica {
				allowed = []int{mergePoolInfatica}
				break
			}
		}
	}
	var all []candidate
	var healthy []candidate
	for _, supplierID := range allowed {
		supplier, ok := snap.Suppliers[supplierID]
		if !ok {
			continue
		}
		for _, ep := range supplier.AvailableGateway {
			c := candidate{supplierID: supplierID, endpoint: ep}
			all = append(all, c)
			key := health.Key{SupplierID: supplierID, Endpoint: ep.Host + ":" + ep.Port, Country: strings.ToUpper(ctx.Country)}
			if s.health == nil || s.health.Healthy(key) {
				healthy = append(healthy, c)
			}
		}
	}
	pool := healthy
	if len(pool) == 0 {
		pool = all
	}
	if len(pool) == 0 {
		return 0, Endpoint{}, false
	}
	if sticky && s.deterministicAffinity() {
		picked := rendezvousPick(pool, ctx)
		return picked.supplierID, picked.endpoint, true
	}
	picked := pool[rand.Intn(len(pool))]
	return picked.supplierID, picked.endpoint, true
}

func (s *Store) deterministicAffinity() bool {
	return strings.EqualFold(s.cfg.SessionAffinityMode, AffinityDeterministic)
}

func (s *Store) shouldUseCachedSession(snap Snapshot, user UserConfig, ctx *Context, info session.Info) bool {
	if !s.deterministicAffinity() {
		return true
	}
	if s.health == nil {
		return true
	}
	key := health.Key{
		SupplierID: info.SupplierID,
		Endpoint:   info.ForwardHost + ":" + info.ForwardPort,
		Country:    strings.ToUpper(ctx.Country),
	}
	if !s.health.Healthy(key) {
		return false
	}
	if !s.cfg.SupplierRecoverAffectsExisting {
		return true
	}
	supplierID, ep, ok := s.pickCandidate(snap, user, *ctx, true)
	if !ok {
		return true
	}
	return supplierID == info.SupplierID && ep.Host == info.ForwardHost && ep.Port == info.ForwardPort
}

func rendezvousPick(pool []candidate, ctx Context) candidate {
	best := pool[0]
	bestScore := rendezvousScore(ctx, best)
	for _, c := range pool[1:] {
		score := rendezvousScore(ctx, c)
		if score > bestScore {
			best, bestScore = c, score
		}
	}
	return best
}

func rendezvousScore(ctx Context, c candidate) uint64 {
	h := fnv.New64a()
	writeHashPart(h, ctx.AuthUser)
	writeHashPart(h, ctx.SessionID)
	writeHashPart(h, strings.ToUpper(ctx.Country))
	writeHashPart(h, strings.ToUpper(ctx.State))
	writeHashPart(h, strings.ToUpper(ctx.City))
	writeHashPart(h, strconv.Itoa(c.supplierID))
	writeHashPart(h, c.endpoint.Host)
	writeHashPart(h, c.endpoint.Port)
	return h.Sum64()
}

func sessionToken(ctx Context, supplierID int, sticky bool, deterministic bool) string {
	if !sticky || !deterministic || ctx.SessionID == "" {
		return strconv.Itoa(rand.Intn(999999999))
	}
	h := fnv.New64a()
	writeHashPart(h, ctx.AuthUser)
	writeHashPart(h, ctx.SessionID)
	writeHashPart(h, strings.ToUpper(ctx.Country))
	writeHashPart(h, strings.ToUpper(ctx.State))
	writeHashPart(h, strings.ToUpper(ctx.City))
	writeHashPart(h, strconv.Itoa(supplierID))
	sum := h.Sum64() % 999999999
	if sum == 0 {
		sum = 1
	}
	return strconv.FormatUint(sum, 10)
}

func writeHashPart(h io.Writer, value string) {
	var length [8]byte
	binary.BigEndian.PutUint64(length[:], uint64(len(value)))
	_, _ = h.Write(length[:])
	_, _ = h.Write([]byte(value))
}

func ParseAuth(username, password string) (Context, bool) {
	resIdx := strings.Index(username, "-res-")
	sidIdx := strings.Index(username, "-sid-")
	keepIdx := strings.Index(username, "-keeptime-")
	if resIdx < 0 || password == "" {
		return Context{}, false
	}
	ctx := Context{RawConnectUser: username + ":" + password, AuthPass: password}
	if sidIdx < 0 && keepIdx < 0 {
		ctx.AuthUser = username[:resIdx]
		if !parseArea(username[resIdx+5:], &ctx) {
			return Context{}, false
		}
		return ctx, true
	}
	if sidIdx < 0 || keepIdx < 0 || !(keepIdx > sidIdx && sidIdx > resIdx) {
		return Context{}, false
	}
	ctx.AuthUser = username[:resIdx]
	if !parseArea(username[resIdx+5:sidIdx], &ctx) {
		return Context{}, false
	}
	ctx.SessionID = username[sidIdx+5 : keepIdx]
	minutes, err := strconv.Atoi(username[keepIdx+10:])
	if err != nil {
		return Context{}, false
	}
	ctx.KeepTime = time.Duration(minutes) * time.Minute
	return ctx, true
}

func ContextForWhiteList(user UserConfig, info PortDynamicProxyInfo, bindPort int) Context {
	ctx := Context{
		RawConnectUser: ":",
		UID:            user.UserID,
		AuthUser:       user.AuthUser,
		AuthPass:       user.AuthPass,
		Country:        info.Area,
		State:          info.Region,
		City:           info.City,
	}
	if info.Minutes > 0 {
		ctx.SessionID = strconv.Itoa(bindPort)
		ctx.KeepTime = time.Duration(info.Minutes) * time.Minute
	}
	return ctx
}

func parseArea(area string, ctx *Context) bool {
	parts := strings.Split(area, "-")
	if len(parts) < 1 || len(parts) > 3 || parts[0] == "" {
		return false
	}
	ctx.Country = parts[0]
	if len(parts) > 1 {
		ctx.State = parts[1]
	}
	if len(parts) > 2 {
		ctx.City = parts[2]
	}
	return true
}

func applySession(ctx *Context, info session.Info) {
	ctx.ForwardHost = info.ForwardHost
	ctx.ForwardPort = info.ForwardPort
	ctx.ForwardUser = info.ForwardUser
	ctx.ForwardPass = info.ForwardPass
	ctx.SupplierID = info.SupplierID
}

func selectAuthFormat(s SupplierConfig, country, state, city string, sticky bool) string {
	any := strings.EqualFold(country, CountryAny) || country == ""
	if sticky {
		if any {
			return s.StickSessionAuthFormat
		}
		if state != "" && city != "" {
			return s.CityStickSessionAuthFormat
		}
		if state != "" {
			return s.StateStickSessionAuthFormat
		}
		return s.CountryStickSessionAuthFormat
	}
	if any {
		return s.TmpSessionAuthFormat
	}
	if state != "" && city != "" {
		return s.CityTmpSessionAuthFormat
	}
	if state != "" {
		return s.StateTmpSessionAuthFormat
	}
	return s.CountryTmpSessionAuthFormat
}

func mapCountryCase(country string, caseType int) string {
	switch caseType {
	case 1:
		return strings.ToUpper(country)
	case 0:
		return strings.ToLower(country)
	default:
		return country
	}
}

func preferEndpoints(in []Endpoint, area string) []Endpoint {
	if area == "" {
		return in
	}
	var out []Endpoint
	for _, ep := range in {
		if ep.Host != "" {
			out = append(out, ep)
		}
	}
	if len(out) == 0 {
		return in
	}
	return out
}

func isNumeric(s string) bool {
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return s != ""
}

func (s *Store) get(path string) ([]byte, error) {
	req, err := http.NewRequest(http.MethodGet, strings.TrimRight(s.cfg.Endpoint, "/")+path, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+s.cfg.Token)
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%s returned %d", path, resp.StatusCode)
	}
	return io.ReadAll(resp.Body)
}

type apiEnvelope struct {
	Code int             `json:"code"`
	Data json.RawMessage `json:"data"`
}

func decodeData[T any](body []byte) ([]T, error) {
	var env apiEnvelope
	if err := json.Unmarshal(body, &env); err != nil {
		return nil, err
	}
	if env.Code != 200 {
		return nil, fmt.Errorf("api code %d", env.Code)
	}
	var data []T
	return data, json.Unmarshal(env.Data, &data)
}

func (s *Store) fetchUsers() ([]UserConfig, error) {
	body, err := s.get("/api/admin/dynamic/getAccountConfig")
	if err != nil {
		return nil, err
	}
	var raw []struct {
		UID               int             `json:"uid"`
		TrafficEnableFlag json.RawMessage `json:"trafficEnable"`
		AuthUser          string          `json:"authUser"`
		AuthPass          string          `json:"authPass"`
		AvailableSupplier []int           `json:"availableSupplier"`
	}
	if raw, err = decodeData[struct {
		UID               int             `json:"uid"`
		TrafficEnableFlag json.RawMessage `json:"trafficEnable"`
		AuthUser          string          `json:"authUser"`
		AuthPass          string          `json:"authPass"`
		AvailableSupplier []int           `json:"availableSupplier"`
	}](body); err != nil {
		return nil, err
	}
	users := make([]UserConfig, 0, len(raw))
	for _, r := range raw {
		enabled := !truthyTrafficDisabled(r.TrafficEnableFlag)
		if !enabled {
			continue
		}
		users = append(users, UserConfig{UserID: r.UID, AuthUser: r.AuthUser, AuthPass: r.AuthPass, TrafficEnable: enabled, AvailableSupplier: r.AvailableSupplier})
	}
	return users, nil
}

func truthyTrafficDisabled(raw json.RawMessage) bool {
	var b bool
	if err := json.Unmarshal(raw, &b); err == nil {
		return b
	}
	var n int
	if err := json.Unmarshal(raw, &n); err == nil {
		return n != 0
	}
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		return strings.EqualFold(s, "true") || s == "1"
	}
	return false
}

func (s *Store) fetchSuppliers() ([]SupplierConfig, error) {
	body, err := s.get("/api/admin/dynamic/getAllSuppliers")
	if err != nil {
		return nil, err
	}
	var raw []struct {
		SupplierID int `json:"supplierId"`
		Auth       struct {
			User string `json:"user"`
			Pass string `json:"pass"`
		} `json:"auth"`
		SessionKeepTime struct {
			Min int `json:"min"`
			Max int `json:"max"`
		} `json:"sessionKeepTime"`
		AvailableGateway     []string `json:"availableGateway"`
		AuthFormatOnce       string   `json:"authFormatOnce"`
		AuthFormat           string   `json:"authFormat"`
		AuthFormatAreaOnce   string   `json:"authFormatAreaOnce"`
		AuthFormatArea       string   `json:"authFormatArea"`
		AuthFormatRegionOnce string   `json:"authFormatRegionOnce"`
		AuthFormatRegion     string   `json:"authFormatRegion"`
		AuthFormatCityOnce   string   `json:"authFormatCityOnce"`
		AuthFormatCity       string   `json:"authFormatCity"`
		AreaCaseType         int      `json:"areaCaseType"`
	}
	if raw, err = decodeData[struct {
		SupplierID int `json:"supplierId"`
		Auth       struct {
			User string `json:"user"`
			Pass string `json:"pass"`
		} `json:"auth"`
		SessionKeepTime struct {
			Min int `json:"min"`
			Max int `json:"max"`
		} `json:"sessionKeepTime"`
		AvailableGateway     []string `json:"availableGateway"`
		AuthFormatOnce       string   `json:"authFormatOnce"`
		AuthFormat           string   `json:"authFormat"`
		AuthFormatAreaOnce   string   `json:"authFormatAreaOnce"`
		AuthFormatArea       string   `json:"authFormatArea"`
		AuthFormatRegionOnce string   `json:"authFormatRegionOnce"`
		AuthFormatRegion     string   `json:"authFormatRegion"`
		AuthFormatCityOnce   string   `json:"authFormatCityOnce"`
		AuthFormatCity       string   `json:"authFormatCity"`
		AreaCaseType         int      `json:"areaCaseType"`
	}](body); err != nil {
		return nil, err
	}
	out := make([]SupplierConfig, 0, len(raw))
	for _, r := range raw {
		var eps []Endpoint
		for _, rawEP := range r.AvailableGateway {
			if ep, ok := parseEndpoint(rawEP, s.cfg.PreferenceEndpointArea); ok {
				eps = append(eps, ep)
			}
		}
		out = append(out, SupplierConfig{
			SupplierID: r.SupplierID, AuthUser: r.Auth.User, AuthPass: r.Auth.Pass,
			SessionMinKeepTime: r.SessionKeepTime.Min, SessionMaxKeepTime: r.SessionKeepTime.Max,
			AvailableGateway: eps, AreaCaseType: r.AreaCaseType,
			TmpSessionAuthFormat: r.AuthFormatOnce, StickSessionAuthFormat: r.AuthFormat,
			CountryTmpSessionAuthFormat: r.AuthFormatAreaOnce, CountryStickSessionAuthFormat: r.AuthFormatArea,
			StateTmpSessionAuthFormat: r.AuthFormatRegionOnce, StateStickSessionAuthFormat: r.AuthFormatRegion,
			CityTmpSessionAuthFormat: r.AuthFormatCityOnce, CityStickSessionAuthFormat: r.AuthFormatCity,
		})
	}
	return out, nil
}

func (s *Store) fetchWhiteList() ([]WhiteListConfig, error) {
	body, err := s.get("/api/admin/agent/getWhiteConfig?gatewayHostname=" + s.cfg.GatewayHostname)
	if err != nil {
		return nil, err
	}
	var raw []struct {
		UID      int      `json:"uid"`
		WhiteIPs []string `json:"whiteIps"`
		Links    []struct {
			Area       string `json:"area"`
			Region     string `json:"region"`
			City       string `json:"city"`
			BitsetPort string `json:"bitsetPort"`
			Protocol   int    `json:"protocol"`
			Minutes    int    `json:"minutes"`
		} `json:"links"`
	}
	if raw, err = decodeData[struct {
		UID      int      `json:"uid"`
		WhiteIPs []string `json:"whiteIps"`
		Links    []struct {
			Area       string `json:"area"`
			Region     string `json:"region"`
			City       string `json:"city"`
			BitsetPort string `json:"bitsetPort"`
			Protocol   int    `json:"protocol"`
			Minutes    int    `json:"minutes"`
		} `json:"links"`
	}](body); err != nil {
		return nil, err
	}
	var out []WhiteListConfig
	for _, r := range raw {
		ports := make(map[int]PortDynamicProxyInfo)
		for _, link := range r.Links {
			portList, err := bitsetPorts(s.cfg.WhiteListPortRangeStart, link.BitsetPort)
			if err != nil {
				continue
			}
			protocol := ProtocolHTTP
			if link.Protocol == 1 {
				protocol = ProtocolSOCKS5
			}
			for _, p := range portList {
				ports[p] = PortDynamicProxyInfo{
					Protocol: protocol,
					Area:     link.Area,
					Region:   link.Region,
					City:     link.City,
					Minutes:  link.Minutes,
				}
			}
		}
		for _, ip := range r.WhiteIPs {
			out = append(out, WhiteListConfig{IP: ip, UID: r.UID, Ports: ports})
		}
	}
	return out, nil
}

func bitsetPorts(offset int, encoded string) ([]int, error) {
	compressed, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, err
	}
	zr, err := gzip.NewReader(bytes.NewReader(compressed))
	if err != nil {
		return nil, err
	}
	defer zr.Close()
	raw, err := io.ReadAll(zr)
	if err != nil {
		return nil, err
	}
	var ports []int
	for byteIdx, b := range raw {
		for bit := 0; bit < 8; bit++ {
			if b&(1<<bit) != 0 {
				ports = append(ports, offset+byteIdx*8+bit)
			}
		}
	}
	return ports, nil
}

func loadAreaMappings(cfg config.Config) map[int]AreaMappingConfig {
	path := cfg.LocalAreaMappingFile
	if path == "" {
		path = filepath.Join("resources", "areaMapping.csv")
	}
	f, err := os.Open(path)
	if err != nil {
		log.Printf("area mapping file open failed path=%s err=%v", path, err)
		return map[int]AreaMappingConfig{}
	}
	defer f.Close()
	rows, err := csv.NewReader(f).ReadAll()
	if err != nil {
		log.Printf("area mapping csv read failed: %v", err)
		return map[int]AreaMappingConfig{}
	}
	infatica := AreaMappingConfig{SupplierID: 16, Mapping: make(map[string]AreaInfo)}
	netnut := AreaMappingConfig{SupplierID: 17, Mapping: make(map[string]AreaInfo)}
	for _, row := range rows {
		if len(row) != 16 || !isNumeric(row[0]) {
			continue
		}
		standardCountry, standardState, standardCity := row[1], row[2], row[3]
		if row[10] == "1" {
			addAreaMapping(&infatica, standardCountry, "", "", row[4], "", "")
		}
		if row[11] == "1" {
			addAreaMapping(&infatica, standardCountry, standardState, "", row[4], row[5], "")
		}
		if row[12] == "1" {
			addAreaMapping(&infatica, standardCountry, standardState, standardCity, row[4], row[5], row[6])
		}
		if row[13] == "1" {
			addAreaMapping(&netnut, standardCountry, "", "", row[7], "", "")
		}
		if row[14] == "1" {
			addAreaMapping(&netnut, standardCountry, standardState, "", row[7], row[8], "")
		}
		if row[15] == "1" {
			addAreaMapping(&netnut, standardCountry, standardState, standardCity, row[7], row[8], row[9])
		}
	}
	return map[int]AreaMappingConfig{
		infatica.SupplierID: infatica,
		netnut.SupplierID:   netnut,
	}
}

func addAreaMapping(cfg *AreaMappingConfig, country, state, city, mappedCountry, mappedState, mappedCity string) {
	if cfg.Mapping == nil {
		cfg.Mapping = make(map[string]AreaInfo)
	}
	cfg.Mapping[areaKey(country, state, city)] = AreaInfo{Country: mappedCountry, State: mappedState, City: mappedCity}
}

func lookupArea(cfg AreaMappingConfig, country, state, city string) (AreaInfo, bool) {
	if cfg.Mapping == nil {
		return AreaInfo{}, false
	}
	if info, ok := cfg.Mapping[areaKey(country, state, city)]; ok {
		return info, true
	}
	if city != "" {
		if info, ok := cfg.Mapping[areaKey(country, state, "")]; ok {
			return info, true
		}
	}
	if state != "" {
		if info, ok := cfg.Mapping[areaKey(country, "", "")]; ok {
			return info, true
		}
	}
	return AreaInfo{}, false
}

func areaKey(country, state, city string) string {
	var b strings.Builder
	b.WriteString(country)
	if state != "" {
		b.WriteByte(':')
		b.WriteString(state)
		if city != "" {
			b.WriteByte(':')
			b.WriteString(city)
		}
	}
	return strings.ToLower(b.String())
}

func parseEndpoint(raw, prefer string) (Endpoint, bool) {
	parts := strings.Split(raw, ":")
	if len(parts) == 2 {
		return Endpoint{Host: parts[0], Port: parts[1]}, true
	}
	if len(parts) == 3 && (prefer == "" || parts[0] == prefer) {
		return Endpoint{Host: parts[1], Port: parts[2]}, true
	}
	return Endpoint{}, false
}

func localSnapshot() Snapshot {
	user := UserConfig{UserID: 1, AuthUser: "IPTNSJSN", AuthPass: "V219Lce1", TrafficEnable: true, AvailableSupplier: []int{16}}
	supplier := SupplierConfig{
		SupplierID: 16, AuthUser: "dfdfd", AuthPass: "ggdd", AvailableGateway: []Endpoint{{Host: "gate.smartproxy.com", Port: "7000"}},
		TmpSessionAuthFormat: "user-{user}:{password}", StickSessionAuthFormat: "{user}_s_{session}_ttl_120m:{password}",
		CountryTmpSessionAuthFormat: "user-{user}-country-{country}:{password}", CountryStickSessionAuthFormat: "{user}_c_{country}_s_{session}_ttl_120m:{password}",
		StateTmpSessionAuthFormat: "user-{user}-country-{country}-{state}:{password}", StateStickSessionAuthFormat: "{user}_c_{country}_s_{session}_ttl_120m:{password}",
		CityTmpSessionAuthFormat: "user-{user}-country-{country}-{state}-{city}:{password}", CityStickSessionAuthFormat: "{user}_c_{country}_city_{city}_s_{session}_ttl_120m:{password}",
	}
	return Snapshot{
		UsersByName: map[string]UserConfig{user.AuthUser: user},
		UsersByID:   map[int]UserConfig{user.UserID: user},
		Suppliers:   map[int]SupplierConfig{supplier.SupplierID: supplier},
		WhiteList:   map[string]WhiteListConfig{},
		AreaMapping: map[int]AreaMappingConfig{},
	}
}
