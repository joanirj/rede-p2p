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

	// Origem da internet do cliente (verificação contínua).
	originOK  bool
	originMsg string

	// Notebook como compartilhador.
	server     *hostServer
	sharing    bool
	sharingMsg string
}

func newApp() *app {
	a := &app{id: loadIdentity(), proxy: &localProxy{}}
	a.led = loadLedger()
	a.server = newHostServer(a.id, a.led)
	return a
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
	if a.sharing {
		a.mu.Unlock()
		logf("ERRO: pare de compartilhar antes de usar a internet de outro aparelho")
		return
	}
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
	go a.watchOrigin(b)
}

// watchOrigin confere a cada 5 s se o notebook continua na rede do compartilhador.
// Se o Windows voltar para o Wi-Fi de casa, a navegação não passa mais pela Rede P2P.
func (a *app) watchOrigin(b *billingClient) {
	for {
		a.mu.Lock()
		still := a.connected && a.billing == b
		host, ssid := a.host, a.ssid
		a.mu.Unlock()
		if !still {
			return
		}
		w := currentWifi()
		ok, msg := true, "Navegação pela Rede P2P ("+ssid+")"
		switch {
		case w.SSID != "" && !strings.HasPrefix(w.SSID, ssidPrefix):
			ok, msg = false, "O Windows saiu da Rede P2P e está no Wi-Fi \""+w.SSID+"\": a navegação NÃO passa pelo compartilhador"
		default:
			if _, err := queryInfo(host); err != nil {
				ok, msg = false, "O compartilhador não responde: a navegação pode não estar funcionando"
			}
		}
		a.mu.Lock()
		changed := ok != a.originOK || msg != a.originMsg
		a.originOK, a.originMsg = ok, msg
		a.mu.Unlock()
		if changed {
			if ok {
				logf("Origem confirmada: %s", msg)
			} else {
				logf("ERRO: %s", msg)
			}
		}
		time.Sleep(5 * time.Second)
	}
}

func queryUsage(host string) (int64, string, error) {
	c := http.Client{Timeout: 4 * time.Second, Transport: &http.Transport{Proxy: nil}}
	resp, err := c.Get("http://" + net.JoinHostPort(host, strconv.Itoa(httpPort)) + usagePath)
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return 0, "", errors.New("o compartilhador não tem a verificação (atualize o app dele)")
	}
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	var bytes int64 = -1
	var hostID string
	for _, line := range strings.Split(string(body), "\n") {
		if v, ok := strings.CutPrefix(line, "bytes="); ok {
			bytes, _ = strconv.ParseInt(strings.TrimSpace(v), 10, 64)
		}
		if v, ok := strings.CutPrefix(line, "host="); ok {
			hostID = strings.TrimSpace(v)
		}
	}
	if bytes < 0 {
		return 0, "", errors.New("resposta inválida do compartilhador")
	}
	return bytes, hostID, nil
}

// --------------------------------------------------------- compartilhar

func (a *app) startSharing() {
	a.mu.Lock()
	if a.connected || a.connecting {
		a.mu.Unlock()
		logf("ERRO: desconecte da Rede P2P antes de compartilhar a internet deste notebook")
		return
	}
	if a.sharing {
		a.mu.Unlock()
		return
	}
	a.sharing = true
	a.sharingMsg = "Ligando o Hotspot Móvel…"
	a.mu.Unlock()
	if err := a.server.start(); err != nil {
		logf("ERRO: %v", err)
		a.mu.Lock()
		a.sharing, a.sharingMsg = false, ""
		a.mu.Unlock()
		return
	}
	msg := fmt.Sprintf("Compartilhando pela rede %s (senha %s)", hostSSID, passphrase)
	if err := hotspot("start"); err != nil {
		logf("Não consegui ligar o Hotspot Móvel sozinho: %v", err)
		logf("Ligue à mão: Configurações → Rede e Internet → Hotspot Móvel → Editar: nome %s, senha %s, banda 2,4 GHz → ligar.", hostSSID, passphrase)
		msg = "Ligue o Hotspot Móvel à mão (instruções no registro abaixo). O compartilhador já está pronto."
	}
	a.mu.Lock()
	a.sharingMsg = msg
	a.mu.Unlock()
	logf("Pronto. No celular ou tablet, toque em \"Usar internet de um notebook\".")
	logf("Se o Windows perguntar sobre o firewall, permita o acesso em redes privadas e públicas.")
}

func (a *app) stopSharing() {
	a.mu.Lock()
	was := a.sharing
	a.sharing, a.sharingMsg = false, ""
	a.mu.Unlock()
	if !was {
		return
	}
	a.server.stop()
	if err := hotspot("stop"); err != nil {
		logf("Não consegui desligar o Hotspot Móvel sozinho (%v). Desligue em Configurações → Hotspot Móvel.", err)
	}
	logf("Compartilhamento encerrado")
}

func (a *app) disconnect() {
	a.mu.Lock()
	b := a.billing
	was := a.connected
	a.connected, a.billing, a.host = false, nil, ""
	a.originOK, a.originMsg = false, ""
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
	DownBytes int64   `json:"downBytes"`
	HostBytes int64   `json:"hostBytes"`
	Origin    string  `json:"origin"`
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

	// Verificação de origem: quantos bytes o compartilhador contou para este notebook.
	usage0, _, usageErr := queryUsage(host)

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
			m.DownBytes = n
			m.Error = ""
			break
		}
	}
	m.OK = m.Error == ""
	switch {
	case usageErr != nil:
		m.Origin = "sem verificação: " + usageErr.Error()
	case !m.OK:
		m.Origin = "—"
	default:
		usage1, _, err := queryUsage(host)
		if err != nil {
			m.Origin = "sem verificação: " + err.Error()
			break
		}
		m.HostBytes = usage1 - usage0
		if m.HostBytes >= m.DownBytes*9/10 {
			m.Origin = "confirmada"
		} else {
			m.Origin = fmt.Sprintf("NÃO confirmada: o compartilhador contou %s e o notebook baixou %s", megabytes(m.HostBytes), megabytes(m.DownBytes))
		}
	}
	logf("Medição %s %d m: sinal %d%%, latência %d ms, %.1f Mbps, origem %s %s", scenario, distance, m.Signal, m.LatencyMs, m.Mbps, m.Origin, m.Error)
	return m
}

func (a *app) saveResults() {
	f, err := os.Create(filepath.Join(dataDir(), "medicoes.csv"))
	if err != nil {
		return
	}
	defer f.Close()
	w := csv.NewWriter(f)
	_ = w.Write([]string{"cenario", "distancia_m", "hora", "rede", "sinal_pct", "canal", "taxa_wifi_mbps", "latencia_ms", "velocidade_mbps", "origem", "ok", "erro"})
	for _, m := range a.results {
		_ = w.Write([]string{m.Scenario, strconv.Itoa(m.Distance), m.Time, m.SSID, strconv.Itoa(m.Signal), strconv.Itoa(m.Channel),
			m.RxMbps, strconv.Itoa(m.LatencyMs), fmt.Sprintf("%.2f", m.Mbps), m.Origin, strconv.FormatBool(m.OK), m.Error})
	}
	w.Flush()
}

func (a *app) resultsCSV() string {
	var sb strings.Builder
	sb.WriteString("cenario,distancia_m,sinal_pct,latencia_ms,velocidade_mbps,origem,erro\n")
	for _, m := range a.results {
		fmt.Fprintf(&sb, "%s,%d,%d,%d,%.1f,%s,%s\n", m.Scenario, m.Distance, m.Signal, m.LatencyMs, m.Mbps, m.Origin, m.Error)
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
			"originOK": a.originOK, "originMsg": a.originMsg,
			"sharing": a.sharing, "sharingMsg": a.sharingMsg,
		}
		if a.sharing {
			st["clients"] = a.server.billing.Summary()
			st["served"] = megabytes(a.server.sent.Load())
			st["hotspotIP"] = findLocalIPv4("192.168.137.")
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
	post("/api/share/start", func(w http.ResponseWriter, r *http.Request) {
		go a.startSharing()
		writeJSON(w, map[string]bool{"ok": true})
	})
	post("/api/share/stop", func(w http.ResponseWriter, r *http.Request) {
		a.stopSharing()
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
