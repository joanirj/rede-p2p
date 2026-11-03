//go:build windows

package main

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"unsafe"
)

const createNoWindow = 0x08000000

func run(name string, args ...string) (string, error) {
	cmd := exec.Command(name, args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	out, err := cmd.CombinedOutput()
	return string(out), err
}

var (
	reNetSSID   = regexp.MustCompile(`(?m)^SSID \d+ : (.*?)\r?$`)
	reSignal    = regexp.MustCompile(`(?m)^\s*(?:Signal|Sinal)\s*:\s*(\d+)%`)
	reIfSSID    = regexp.MustCompile(`(?m)^\s*SSID\s*:\s*(.*?)\r?$`)
	reChannel   = regexp.MustCompile(`(?m)^\s*(?:Channel|Canal)\s*:\s*(\d+)`)
	reRxRate    = regexp.MustCompile(`(?m)^\s*(?:Receive rate|Taxa de recep\S*)\s*\(Mbps\)\s*:\s*([\d.,]+)`)
	reLocationM = regexp.MustCompile(`(?i)location|localiza`)
)

// scanNetworks lista as redes DIRECT-RP-… visíveis, com o sinal (0–100%).
func scanNetworks() (map[string]int, error) {
	out, err := run("netsh", "wlan", "show", "networks", "mode=bssid")
	if err != nil {
		if reLocationM.MatchString(out) {
			return nil, errors.New("o Windows exige a localização ligada para listar redes Wi-Fi (Configurações → Privacidade → Localização)")
		}
		return nil, fmt.Errorf("netsh: %v %s", err, strings.TrimSpace(out))
	}
	nets := map[string]int{}
	blocks := reNetSSID.FindAllStringSubmatchIndex(out, -1)
	for i, m := range blocks {
		name := strings.TrimSpace(out[m[2]:m[3]])
		end := len(out)
		if i+1 < len(blocks) {
			end = blocks[i+1][0]
		}
		best := 0
		for _, s := range reSignal.FindAllStringSubmatch(out[m[1]:end], -1) {
			if v, _ := strconv.Atoi(s[1]); v > best {
				best = v
			}
		}
		if strings.HasPrefix(name, ssidPrefix) {
			nets[name] = best
		}
	}
	return nets, nil
}

func profileXML(ssid string) string {
	return `<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
  <name>` + ssid + `</name>
  <SSIDConfig><SSID><name>` + ssid + `</name></SSID></SSIDConfig>
  <connectionType>ESS</connectionType>
  <connectionMode>manual</connectionMode>
  <MSM><security>
    <authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption><useOneX>false</useOneX></authEncryption>
    <sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>` + passphrase + `</keyMaterial></sharedKey>
  </security></MSM>
</WLANProfile>`
}

// joinWifi cria o perfil da rede (só para este usuário) e conecta.
func joinWifi(ssid string) error {
	path := filepath.Join(dataDir(), "perfil-wifi.xml")
	if err := os.WriteFile(path, []byte(profileXML(ssid)), 0o600); err != nil {
		return err
	}
	if out, err := run("netsh", "wlan", "add", "profile", "filename="+path, "user=current"); err != nil {
		return fmt.Errorf("não consegui criar o perfil da rede: %s", strings.TrimSpace(out))
	}
	_ = os.Remove(path)
	// Conexão limitada: o Windows pausa atualizações automáticas e parte da sincronização.
	if out, err := run("netsh", "wlan", "set", "profileparameter", "name="+ssid, "cost=Fixed"); err != nil {
		logf("Aviso: não consegui marcar a rede como conexão limitada (%s)", strings.TrimSpace(out))
	}
	if out, err := run("netsh", "wlan", "connect", "name="+ssid, "ssid="+ssid); err != nil {
		return fmt.Errorf("não consegui conectar: %s", strings.TrimSpace(out))
	}
	return nil
}

func leaveWifi() {
	_, _ = run("netsh", "wlan", "disconnect")
}

type wifiStatus struct {
	SSID    string `json:"ssid"`
	Signal  int    `json:"signal"`
	Channel int    `json:"channel"`
	RxMbps  string `json:"rxMbps"`
	Note    string `json:"note,omitempty"`
}

func currentWifi() wifiStatus {
	out, err := run("netsh", "wlan", "show", "interfaces")
	st := wifiStatus{Signal: -1}
	if err != nil {
		return st
	}
	if m := reIfSSID.FindStringSubmatch(out); m != nil {
		st.SSID = strings.TrimSpace(m[1])
	}
	if m := reSignal.FindStringSubmatch(out); m != nil {
		st.Signal, _ = strconv.Atoi(m[1])
	}
	if m := reChannel.FindStringSubmatch(out); m != nil {
		st.Channel, _ = strconv.Atoi(m[1])
	}
	if m := reRxRate.FindStringSubmatch(out); m != nil {
		st.RxMbps = m[1]
	}
	if st.SSID == "" && reLocationM.MatchString(out) {
		st.Note = "Ligue a localização do Windows para ver o sinal"
	}
	return st
}

func openBrowser(url string) {
	_, _ = run("rundll32", "url.dll,FileProtocolHandler", url)
}

// ------------------------------------------------- proxy do sistema (PAC)

const inetKey = `HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings`

var reRegSZ = regexp.MustCompile(`AutoConfigURL\s+REG_SZ\s+(.*?)\r?$`)

func getAutoConfigURL() string {
	out, err := run("reg", "query", inetKey, "/v", "AutoConfigURL")
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(out, "\n") {
		if m := reRegSZ.FindStringSubmatch(strings.TrimSpace(line)); m != nil {
			return strings.TrimSpace(m[1])
		}
	}
	return ""
}

func setAutoConfigURL(url string) error {
	if url == "" {
		// Se o valor já não existir, o reg devolve erro: não importa.
		_, _ = run("reg", "delete", inetKey, "/v", "AutoConfigURL", "/f")
		refreshInternetSettings()
		return nil
	}
	out, err := run("reg", "add", inetKey, "/v", "AutoConfigURL", "/t", "REG_SZ", "/d", url, "/f")
	refreshInternetSettings()
	if err != nil {
		return fmt.Errorf("%v %s", err, strings.TrimSpace(out))
	}
	return nil
}

func refreshInternetSettings() {
	dll := syscall.NewLazyDLL("wininet.dll")
	proc := dll.NewProc("InternetSetOptionW")
	if proc.Find() != nil {
		return
	}
	const settingsChanged, refresh = 39, 37
	_, _, _ = proc.Call(0, settingsChanged, uintptr(unsafe.Pointer(nil)), 0)
	_, _, _ = proc.Call(0, refresh, uintptr(unsafe.Pointer(nil)), 0)
}
