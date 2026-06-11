package dynamic

import (
	"os"
	"path/filepath"
	"testing"

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

func writeAreaMappingCSV(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "areaMapping.csv")
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write area mapping csv: %v", err)
	}
	return path
}
