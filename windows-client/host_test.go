package main

import (
	"crypto/rand"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"testing"
	"time"
)

func testIdentity(t *testing.T, first byte) *identity {
	sk := make([]byte, 32)
	rand.Read(sk)
	sk[0] = first
	pk, err := schnorrPublicKey(sk)
	if err != nil {
		t.Fatal(err)
	}
	return &identity{sk, pk}
}

// O notebook como compartilhador atende um cliente: proxy, cobrança, verificação de
// origem e UDP (usado pelo DNS do celular).
func TestHostServer(t *testing.T) {
	billLoopback = true
	defer func() { billLoopback = false }()
	hostLed := &ledger{path: t.TempDir() + "/h.json"}
	h := newHostServer(testIdentity(t, 3), hostLed)
	if err := h.start(); err != nil {
		t.Skip("portas ocupadas:", err)
	}
	defer h.stop()

	if hops, err := queryInfo("127.0.0.1"); err != nil || hops != 0 {
		t.Fatalf("info: %v %d", err, hops)
	}

	// Cliente: proxy local + cobrança, como o notebook cliente faria.
	p := &localProxy{}
	if err := p.serve(); err != nil {
		t.Skip("porta do proxy local ocupada:", err)
	}
	defer p.close()
	p.setHost("127.0.0.1")
	clientLed := &ledger{Balance: 100000, path: t.TempDir() + "/c.json"}
	bc := newBillingClient("127.0.0.1", testIdentity(t, 4), clientLed, trafficBytes.Load)
	bc.start()
	defer bc.stop()
	time.Sleep(300 * time.Millisecond)

	u0, hostID, err := queryUsage("127.0.0.1")
	if err != nil || hostID != h.id.hex() {
		t.Fatalf("usage: %v %s", err, hostID)
	}
	payload := make([]byte, 3*mb)
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(payload) }))
	defer target.Close()
	pu, _ := url.Parse("http://" + localProxyAddr)
	c := http.Client{Timeout: 10 * time.Second, Transport: &http.Transport{Proxy: http.ProxyURL(pu)}}
	resp, err := c.Get(target.URL)
	if err != nil {
		t.Fatal(err)
	}
	n, _ := io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	u1, _, _ := queryUsage("127.0.0.1")
	if n != int64(len(payload)) || u1-u0 < n {
		t.Fatalf("baixou %d, compartilhador contou %d", n, u1-u0)
	}

	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		if _, earned, _ := hostLed.snapshot(); earned >= 2000 {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	_, earned, _ := hostLed.snapshot()
	_, _, spent := clientLed.snapshot()
	if earned < 2000 || earned != spent {
		t.Fatalf("cobrança: compartilhador recebeu %d, cliente pagou %d", earned, spent)
	}

	// UDP pelo SOCKS5: servidor de eco no lugar do DNS.
	echo, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	defer echo.Close()
	go func() {
		buf := make([]byte, 2048)
		for {
			k, from, err := echo.ReadFromUDP(buf)
			if err != nil {
				return
			}
			echo.WriteToUDP(buf[:k], from)
		}
	}()
	ctl, err := net.Dial("tcp", "127.0.0.1:"+strconv.Itoa(socksPort))
	if err != nil {
		t.Fatal(err)
	}
	defer ctl.Close()
	ctl.Write([]byte{5, 1, 0})
	io.ReadFull(ctl, make([]byte, 2))
	ctl.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
	rep := make([]byte, 10)
	io.ReadFull(ctl, rep)
	relayPort := int(binary.BigEndian.Uint16(rep[8:]))
	uc, _ := net.DialUDP("udp", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: relayPort})
	defer uc.Close()
	ep := echo.LocalAddr().(*net.UDPAddr)
	pkt := append([]byte{0, 0, 0, 1, 127, 0, 0, 1}, byte(ep.Port>>8), byte(ep.Port))
	pkt = append(pkt, []byte("consulta-dns")...)
	uc.Write(pkt)
	uc.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 2048)
	k, err := uc.Read(buf)
	if err != nil || string(buf[10:k]) != "consulta-dns" {
		t.Fatalf("UDP: %v %q", err, buf[:k])
	}
}
