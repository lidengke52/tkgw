package dynamic

type UserConfig struct {
	UserID            int
	AuthUser          string
	AuthPass          string
	TrafficEnable     bool
	AvailableSupplier []int
}

type SupplierConfig struct {
	SupplierID                    int
	AuthUser                      string
	AuthPass                      string
	SessionMinKeepTime            int
	SessionMaxKeepTime            int
	AvailableGateway              []Endpoint
	AreaCaseType                  int
	TmpSessionAuthFormat          string
	StickSessionAuthFormat        string
	CountryTmpSessionAuthFormat   string
	CountryStickSessionAuthFormat string
	StateTmpSessionAuthFormat     string
	StateStickSessionAuthFormat   string
	CityTmpSessionAuthFormat      string
	CityStickSessionAuthFormat    string
}

type Endpoint struct {
	Host string
	Port string
}

type WhiteListConfig struct {
	IP    string
	UID   int
	Ports map[int]PortDynamicProxyInfo
}

type PortDynamicProxyInfo struct {
	Protocol string
	Area     string
	Region   string
	City     string
	Minutes  int
}

type AreaMappingConfig struct {
	SupplierID int
	Mapping    map[string]AreaInfo
}

type AreaInfo struct {
	Country string
	State   string
	City    string
}

type Snapshot struct {
	UsersByName map[string]UserConfig
	UsersByID   map[int]UserConfig
	Suppliers   map[int]SupplierConfig
	WhiteList   map[string]WhiteListConfig
	AreaMapping map[int]AreaMappingConfig
}
