package dynamic

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"ipproxy-dynamic-gateway-go/internal/config"
)

func TestLoadAreaMappingsHeaderWithLiang(t *testing.T) {
	path := writeAreaMappingCSV(t, "\ufeffarea,region,city,infatica_area,infatica_region,infatica_city,infatica_area_support,infatica_region_support,infatica_city_support,netnut_area,netnut_region,netnut_city,netnut_area_support,netnut_region_support,netnut_city_support,liang_area,liang_region,liang_city,liang_area_support,liang_region_support,liang_city_support\nUS,Missouri,Bernie,US,Missouri,Bernie,1,0,1,,,,0,0,0,US,MO,,1,1,0\n")
	mappings := loadAreaMappings(config.Config{LocalAreaMappingFile: path})

	liangCountry, ok := lookupArea(mappings[supplierLiang], "US", "", "")
	if !ok {
		t.Fatal("expected liang country mapping")
	}
	if liangCountry.Country != "US" {
		t.Fatalf("liang country = %q, want US", liangCountry.Country)
	}
	liangState, ok := lookupArea(mappings[supplierLiang], "US", "Missouri", "")
	if !ok {
		t.Fatal("expected liang state mapping")
	}
	if liangState.State != "MO" {
		t.Fatalf("liang state = %q, want MO", liangState.State)
	}
	if _, ok := mappings[supplierLiang].Mapping[areaKey("US", "Missouri", "Bernie")]; ok {
		t.Fatal("did not expect liang city mapping when city support is 0")
	}
}

func TestLoadAreaMappingsSkipsBlankSupportedLiangValues(t *testing.T) {
	path := writeAreaMappingCSV(t, "\ufeffarea,region,city,infatica_area,infatica_region,infatica_city,infatica_area_support,infatica_region_support,infatica_city_support,netnut_area,netnut_region,netnut_city,netnut_area_support,netnut_region_support,netnut_city_support,liang_area,liang_region,liang_city,liang_area_support,liang_region_support,liang_city_support\nIN,Maharashtra,Mumbai,IN,Maharashtra,Mumbai,1,1,1,IN,Maharashtra,Mumbai,1,1,1,IN,MH,mumbai,1,1,1\nIN,Delhi,Hashtsal,IN,,,1,0,0,IN,Delhi,Hashtsal,1,1,1,,,,1,0,0\n")
	mappings := loadAreaMappings(config.Config{LocalAreaMappingFile: path})

	liangCountry, ok := lookupArea(mappings[supplierLiang], "IN", "", "")
	if !ok {
		t.Fatal("expected liang country mapping")
	}
	if liangCountry.Country != "IN" {
		t.Fatalf("liang country = %q, want IN", liangCountry.Country)
	}
	liangCity, ok := lookupArea(mappings[supplierLiang], "IN", "Maharashtra", "Mumbai")
	if !ok {
		t.Fatal("expected liang city mapping")
	}
	if liangCity.Country != "IN" || liangCity.State != "MH" || liangCity.City != "mumbai" {
		t.Fatalf("liang city mapping = %+v, want IN/MH/mumbai", liangCity)
	}
}

func TestLoadAreaMappingsPositional22WithLiang(t *testing.T) {
	path := writeAreaMappingCSV(t, "1,IN,Delhi,New Delhi,IN,Delhi,New Delhi,in,dl,new-delhi,IN,DL,NDEL,1,1,1,1,1,1,1,1,1\n")
	mappings := loadAreaMappings(config.Config{LocalAreaMappingFile: path})

	liangCity, ok := lookupArea(mappings[supplierLiang], "IN", "Delhi", "New Delhi")
	if !ok {
		t.Fatal("expected liang city mapping")
	}
	if liangCity.Country != "IN" || liangCity.State != "DL" || liangCity.City != "NDEL" {
		t.Fatalf("liang city mapping = %+v, want IN/DL/NDEL", liangCity)
	}
	netnutCity, ok := lookupArea(mappings[supplierNetNut], "IN", "Delhi", "New Delhi")
	if !ok {
		t.Fatal("expected netnut city mapping")
	}
	if netnutCity.Country != "in" || netnutCity.State != "dl" || netnutCity.City != "new-delhi" {
		t.Fatalf("netnut city mapping = %+v, want in/dl/new-delhi", netnutCity)
	}
}

func TestParseSupplierWeights(t *testing.T) {
	weights := parseSupplierWeights("16=70, 17:30, bad, 18=nope")
	if weights[16] != 70 {
		t.Fatalf("weight 16 = %v, want 70", weights[16])
	}
	if weights[17] != 30 {
		t.Fatalf("weight 17 = %v, want 30", weights[17])
	}
	if _, ok := weights[18]; ok {
		t.Fatal("did not expect invalid supplier 18 weight")
	}
}

func TestIsSafeSessionID(t *testing.T) {
	valid := []string{"12345678", "abc123XYZ", "sid_001", "sid.001"}
	for _, sid := range valid {
		if !isSafeSessionID(sid) {
			t.Fatalf("sid %q should be valid", sid)
		}
	}

	invalid := []string{"", "sid-001", "sid/001", "会话"}
	for _, sid := range invalid {
		if isSafeSessionID(sid) {
			t.Fatalf("sid %q should be invalid", sid)
		}
	}

	if isSafeSessionID("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklm") {
		t.Fatal("overlong sid should be invalid")
	}
}

func TestPickCandidateHonorsZeroWeight(t *testing.T) {
	store := NewStore(config.Config{SupplierWeights: "16=1,17=0"})
	snap := weightedPickSnapshot()
	user := UserConfig{AuthUser: "u", TrafficEnable: true, AvailableSupplier: []int{16, 17}}
	ctx := Context{AuthUser: "u", Country: "US", SessionID: "abc", KeepTime: 10 * time.Minute}

	for i := 0; i < 20; i++ {
		supplierID, _, ok := store.pickCandidate(snap, user, ctx, true)
		if !ok {
			t.Fatal("expected candidate")
		}
		if supplierID != supplierInfatica {
			t.Fatalf("supplier = %d, want %d", supplierID, supplierInfatica)
		}
	}
}

func TestPickCandidateLongStickyKeeptimeStillPrefersInfatica(t *testing.T) {
	store := NewStore(config.Config{SupplierWeights: "16=1,17=100"})
	snap := weightedPickSnapshot()
	user := UserConfig{AuthUser: "u", TrafficEnable: true, AvailableSupplier: []int{16, 17}}
	ctx := Context{AuthUser: "u", Country: "US", SessionID: "abc", KeepTime: 31 * time.Minute}

	supplierID, _, ok := store.pickCandidate(snap, user, ctx, true)
	if !ok {
		t.Fatal("expected candidate")
	}
	if supplierID != supplierInfatica {
		t.Fatalf("supplier = %d, want %d", supplierID, supplierInfatica)
	}
}

func weightedPickSnapshot() Snapshot {
	return Snapshot{
		Suppliers: map[int]SupplierConfig{
			supplierInfatica: {SupplierID: supplierInfatica, AvailableGateway: []Endpoint{{Host: "gw16a", Port: "29999"}, {Host: "gw16b", Port: "29999"}}},
			supplierNetNut:   {SupplierID: supplierNetNut, AvailableGateway: []Endpoint{{Host: "gw17", Port: "29999"}}},
		},
	}
}

func writeAreaMappingCSV(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "areaMapping.csv")
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write area mapping csv: %v", err)
	}
	return path
}
