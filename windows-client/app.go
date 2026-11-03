package main

import (
	"encoding/csv"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const pacPrefix = "http://" + uiAddr + "/proxy.pac"

type app struct {
	id    *identity
	led   *ledger
	proxy *localProxy

	mu         sync.Mutex
	connecting bool
	connected  bool
	ssid       string
	host       string
	hops       int
	billing    *billingClient
	results    []measurement
	measuring  bool
}

func newApp() *app {
	return &app{id: loadIdentity(), led: loadLedger(), proxy: &localProxy{}}
}

// ----------------------------------------------------------- proxy do sistema

func prevPACPath() string { return filepath.Join(dataDir(), "pac-anterior.txt") }

// recoverSystemProxy desfaz a configuração de proxy se o app fechou sem desconectar.
func recoverSystemProxy() {
	cur := getAutoConfigURL()
	if strings.HasPrefix(cur, pacPrefix) {
		prev, _ := os.ReadFile(prevPACPath())
		_ = setAutoConfigURL(strings.TrimSpace(string(prev)))
		logf("Configuração de proxy da sessão anterior desfeita")
	}
}

func useSystemProxy() error {
	cur := getAutoConfigURL()
	if !strings.HasPrefix(cur, pacPrefix) {
		_ = os.WriteFile(prevPACPath(), []byte(cur), 0o600)
	}
	return setAutoConfigURL(fmt.Sprintf("%s?t=%d", pacPrefix, time.Now().Unix()))
}

// restoreSystemProxy só mexe no proxy do Windows se a configuração atual for a nossa.
func restoreSystemProxy() {
	if !strings.HasPrefix(getAutoConfigURL(), pacPrefix) {
		return
	}
	prev, _ := os.ReadFile(prevPACPath())
	_ = setAutoConfigURL(strings.TrimSpace(string(prev)))
}

const pacScript = `function FindProxyForURL(url, host) {
  if (isPlainHostName(host) || host == "127.0.0.1" || host == "localhost") return "DIRECT";
  return "PROXY ` + localProxyAddr + `";
}
`

// --------------------------------------------------------------- conexão

func findLocalIPv4(prefix string) string {
	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok {
				if ip4 := ipn.IP.To4(); ip4 != nil && strings.HasPrefix(ip4.String(), prefix) {
					return ip4.String()
				}
			}
		}
	}
	return ""
}

func queryInfo(host string) (int, error) {
	c := http.Client{Timeout: 5 * time.Second, Transport: &http.Transport{Proxy: nil}}
	resp, err := c.Get("http://" + net.JoinHostPort(host, strconv.Itoa(httpPort)) + infoPath)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	for _, line := range strings.Split(string(body), "\n") {
		if strings.HasPrefix(line, "hops=") {
			return strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(line, "hops=")))
		}
	}
	return 0, errors.New("o aparelho não respondeu como compartilhador da Rede P2P")
}

func (a *app) connect() {
	a.mu.Lock()
	if a.connecting || a.connected {
		a.mu.Unlock()
		return
	}
	a.connecting = true
	a.mu.Unlock()
	defer func() {
		a.mu.Lock()
		a.connecting = false
		a.mu.Unlock()
	}()

	ssid := ssidPrefix + "RedeP2P"
	if nets, err := scanNetworks(); err != nil {
		logf("Aviso: %v", err)
	} else if len(nets) == 0 {
		logf("Nenhuma rede DIRECT-RP-… à vista. O tablet está compartilhando e perto?")
	} else {
		names := make([]string, 0, len(nets))
		for n := range nets {
			names = append(names, n)
		}
		sort.Slice(names, func(i, j int) bool { return nets[names[i]] > nets[names[j]] })
		ssid = names[0]
		logf("Redes da Rede P2P à vista: %v; escolhida %s (%d%%)", nets, ssid, nets[ssid])
	}
	logf("Conectando à rede %s…", ssid)
	if err := joinWifi(ssid); err != nil {
		logf("ERRO: %v", err)
		return
	}
	var local string
	for i := 0; i < 25 && local == ""; i++ {
		time.Sleep(time.Second)
		local = findLocalIPv4("192.168.49.")
	}
	if local == "" {
		logf("ERRO: o Windows não recebeu endereço da rede %s em 25 s", ssid)
		return
	}
	host := "192.168.49.1"
	hops, err := queryInfo(host)
	if err != nil {
		logf("ERRO: não falei com o compartilhador em %s — %v", host, err)
		return
	}
	a.proxy.setHost(host)
	if err := useSystemProxy(); err != nil {
		logf("ERRO: não consegui configurar o proxy do Windows — %v", err)
	}
	b := newBillingClient(host, a.id, a.led, trafficBytes.Load)
	b.start()
	a.mu.Lock()
	a.connected, a.ssid, a.host, a.hops, a.billing = true, ssid, host, hops, b
	a.mu.Unlock()
	logf("Conectado ao compartilhador %s pela rede %s (endereço deste notebook %s, %d salto(s) do compartilhador até a internet)", host, ssid, local, hops)
	logf("Navegadores e apps que usam o proxy do Windows já passam pela Rede P2P.")
}

func (a *app) disconnect() {
	a.mu.Lock()
	b := a.billing
	was := a.connected
	a.connected, a.billing, a.host = false, nil, ""
	a.mu.Unlock()
	if b != nil {
		b.stop()
	}
	a.proxy.setHost("")
	restoreSystemProxy()
	if was {
		leaveWifi()
		logf("Desconectado. O Windows volta sozinho para o Wi-Fi de antes.")
	}
}

// ------------------------------------------------------------- medição

type measurement struct {
	Scenario  string  `json:"scenario"`
	Distance  int     `json:"distance"`
	Time      string  `json:"time"`
	SSID      string  `json:"ssid"`
	Signal    int     `json:"signal"`
	Channel   int     `json:"channel"`
	RxMbps    string  `json:"rxMbps"`
	LatencyMs int     `json:"latencyMs"`
	Mbps      float64 `json:"mbps"`
	OK        bool    `json:"ok"`
	Error     string  `json:"error,omitempty"`
}

var downloadURLs = []string{
	"https://speed.cloudflare.com/__down?bytes=3000000",
	"https://proof.ovh.net/files/1Mb.dat",
}

func (a *app) measure(scenario string, distance int) measurement {
	a.mu.Lock()
	host := a.host
	ssid := a.ssid
	a.mu.Unlock()
	m := measurement{Scenario: scenario, Distance: distance, Time: time.Now().Format("15:04:05"), LatencyMs: -1}
	if host == "" {
		m.Error = "desconectado"
		return m
	}
	w := currentWifi()
	if !strings.HasPrefix(w.SSID, ssidPrefix) && w.Note == "" {
		logf("O Windows saiu da rede %s (está em \"%s\"); reconectando…", ssid, w.SSID)
		_ = joinWifi(ssid)
		time.Sleep(8 * time.Second)
		w = currentWifi()
	}
	m.SSID, m.Signal, m.Channel, m.RxMbps = w.SSID, w.Signal, w.Channel, w.RxMbps

	// Latência: tempo para abrir uma conexão até 1.1.1.1:443 através do compartilhador.
	var lat []int
	for i := 0; i < 3; i++ {
		t0 := time.Now()
		c, err := socksDial(host, "1.1.1.1:443", 5*time.Second)
		if err != nil {
			continue
		}
		lat = append(lat, int(time.Since(t0).Milliseconds()))
		c.Close()
	}
	if len(lat) == 0 {
		m.Error = "sem conexão com o compartilhador"
		logf("Medição %s %d m: sem conexão", scenario, distance)
		return m
	}
	sort.Ints(lat)
	m.LatencyMs = lat[len(lat)/2]

	// Velocidade: download curto pelo proxy local (passa pelo compartilhador e é cobrado).
	proxyURL, _ := url.Parse("http://" + localProxyAddr)
	client := http.Client{Timeout: 15 * time.Second, Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}}
	for _, u := range downloadURLs {
		t0 := time.Now()
		resp, err := client.Get(u)
		if err != nil {
			m.Error = "download falhou: " + err.Error()
			continue
		}
		n, _ := io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		secs := time.Since(t0).Seconds()
		if n > 100_000 && secs > 0 {
			m.Mbps = float64(n) * 8 / secs / 1e6
			m.Error = ""
			break
		}
	}
	m.OK = m.Error == ""
	logf("Medição %s %d m: sinal %d%%, latência %d ms, %.1f Mbps %s", scenario, distance, m.Signal, m.LatencyMs, m.Mbps, m.Error)
	return m
}

func (a *app) saveResults() {
	f, err := os.Create(filepath.Join(dataDir(), "medicoes.csv"))
	if err != nil {
		return
	}
	defer f.Close()
	w := csv.NewWriter(f)
	_ = w.Write([]string{"cenario", "distancia_m", "hora", "rede", "sinal_pct", "canal", "taxa_wifi_mbps", "latencia_ms", "velocidade_mbps", "ok", "erro"})
	for _, m := range a.results {
		_ = w.Write([]string{m.Scenario, strconv.Itoa(m.Distance), m.Time, m.SSID, strconv.Itoa(m.Signal), strconv.Itoa(m.Channel),
			m.RxMbps, strconv.Itoa(m.LatencyMs), fmt.Sprintf("%.2f", m.Mbps), strconv.FormatBool(m.OK), m.Error})
	}
	w.Flush()
}

func (a *app) resultsCSV() string {
	var sb strings.Builder
	sb.WriteString("cenario,distancia_m,sinal_pct,latencia_ms,velocidade_mbps,erro\n")
	for _, m := range a.results {
		fmt.Fprintf(&sb, "%s,%d,%d,%d,%.1f,%s\n", m.Scenario, m.Distance, m.Signal, m.LatencyMs, m.Mbps, m.Error)
	}
	return sb.String()
}

// ----------------------------------------------------------------- HTTP

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(v)
}

func (a *app) routes(page []byte) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(page)
	})
	mux.HandleFunc("/proxy.pac", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/x-ns-proxy-autoconfig")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = io.WriteString(w, pacScript)
	})
	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		st := map[string]any{
			"version": appVersion, "connecting": a.connecting, "connected": a.connected,
			"ssid": a.ssid, "host": a.host, "hops": a.hops, "measuring": a.measuring,
		}
		b := a.billing
		a.mu.Unlock()
		bal, earned, spent := a.led.snapshot()
		st["balance"], st["earned"], st["spent"] = credits(bal), credits(earned), credits(spent)
		st["traffic"] = megabytes(trafficBytes.Load())
		st["identity"] = a.id.short()
		st["wifi"] = currentWifi()
		if b != nil {
			st["billing"] = b.Summary()
		}
		writeJSON(w, st)
	})
	post := func(path string, f func(w http.ResponseWriter, r *http.Request)) {
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodPost {
				http.Error(w, "use POST", http.StatusMethodNotAllowed)
				return
			}
			f(w, r)
		})
	}
	post("/api/connect", func(w http.ResponseWriter, r *http.Request) {
		go a.connect()
		writeJSON(w, map[string]bool{"ok": true})
	})
	post("/api/disconnect", func(w http.ResponseWriter, r *http.Request) {
		a.disconnect()
		writeJSON(w, map[string]bool{"ok": true})
	})
	post("/api/topup", func(w http.ResponseWriter, r *http.Request) {
		a.led.topUp(testTopUp)
		logf("Saldo de teste recarregado")
		writeJSON(w, map[string]bool{"ok": true})
	})
	post("/api/clearlog", func(w http.ResponseWriter, r *http.Request) {
		clearLog()
		writeJSON(w, map[string]bool{"ok": true})
	})
	mux.HandleFunc("/api/log", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = io.WriteString(w, logText())
	})
	post("/api/measure", func(w http.ResponseWriter, r *http.Request) {
		scenario := r.URL.Query().Get("scenario")
		distance, _ := strconv.Atoi(r.URL.Query().Get("distance"))
		a.mu.Lock()
		if a.measuring {
			a.mu.Unlock()
			http.Error(w, "medição em andamento", http.StatusConflict)
			return
		}
		a.measuring = true
		a.mu.Unlock()
		m := a.measure(scenario, distance)
		a.mu.Lock()
		a.measuring = false
		a.results = append(a.results, m)
		a.saveResults()
		a.mu.Unlock()
		writeJSON(w, m)
	})
	mux.HandleFunc("/api/results", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		defer a.mu.Unlock()
		if r.Method == http.MethodPost && r.URL.Query().Get("clear") == "1" {
			a.results = nil
			a.saveResults()
		}
		writeJSON(w, map[string]any{"results": a.results, "csv": a.resultsCSV()})
	})
	return mux
}
