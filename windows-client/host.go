package main

// Notebook como compartilhador: proxy SOCKS5 (TCP e UDP) na porta 1080, proxy HTTP na
// 8080 (com /redep2p/info e /redep2p/usage) e cobrança na 8090. Mesmo comportamento
// do app Android (ProxyServer.kt e Billing.kt).

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const paymentWait = 15 * time.Second

// meter conta os bytes de cada cliente (por IP) e diz se ele ainda pode usar.
type meter struct {
	mu     sync.Mutex
	used   map[string]int64
	policy func(ip string, used int64) bool
}

func newMeter() *meter { return &meter{used: map[string]int64{}} }

func (m *meter) add(ip string, n int64) {
	m.mu.Lock()
	m.used[ip] += n
	m.mu.Unlock()
}

func (m *meter) get(ip string) int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.used[ip]
}

func (m *meter) allowed(ip string) bool {
	m.mu.Lock()
	p := m.policy
	u := m.used[ip]
	m.mu.Unlock()
	return p == nil || p(ip, u)
}

// waitForPayment pausa a conexão até o cliente pagar (no máximo 15 s).
func (m *meter) waitForPayment(ip string) bool {
	deadline := time.Now().Add(paymentWait)
	for time.Now().Before(deadline) {
		if m.allowed(ip) {
			return true
		}
		time.Sleep(100 * time.Millisecond)
	}
	return false
}

func ipOf(addr net.Addr) string {
	host, _, err := net.SplitHostPort(addr.String())
	if err != nil {
		return addr.String()
	}
	if i := strings.IndexByte(host, '%'); i >= 0 {
		host = host[:i]
	}
	return host
}

// clientAllowed aceita a rede do Hotspot Móvel do Windows (192.168.137.x), a rede
// Wi-Fi Direct do Android (192.168.49.x), IPv6 local e o próprio notebook.
func clientAllowed(addr net.Addr) bool {
	ip := net.ParseIP(ipOf(addr))
	if ip == nil {
		return false
	}
	if ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return true
	}
	s := ip.String()
	return strings.HasPrefix(s, "192.168.137.") || strings.HasPrefix(s, "192.168.49.")
}

type hostServer struct {
	id      *identity
	led     *ledger
	meter   *meter
	billing *billingServer

	mu        sync.Mutex
	listeners []net.Listener
	running   atomic.Bool
	sent      atomic.Int64 // bytes enviados aos clientes
	conns     atomic.Int64
}

func newHostServer(id *identity, led *ledger) *hostServer {
	m := newMeter()
	h := &hostServer{id: id, led: led, meter: m}
	h.billing = newBillingServer(id, led, m)
	return h
}

func (h *hostServer) start() error {
	h.running.Store(true)
	type svc struct {
		port    int
		handler func(net.Conn)
	}
	for _, s := range []svc{{socksPort, h.handleSocks}, {httpPort, h.handleHTTP}, {controlPort, h.billing.handle}} {
		ln, err := net.Listen("tcp", ":"+strconv.Itoa(s.port))
		if err != nil {
			h.stop()
			return fmt.Errorf("porta %d ocupada: %v", s.port, err)
		}
		h.mu.Lock()
		h.listeners = append(h.listeners, ln)
		h.mu.Unlock()
		go h.acceptLoop(ln, s.handler)
	}
	h.billing.start()
	logf("Compartilhador ativo: SOCKS5 na porta %d, HTTP na %d, cobrança na %d", socksPort, httpPort, controlPort)
	return nil
}

func (h *hostServer) stop() {
	h.running.Store(false)
	h.mu.Lock()
	for _, ln := range h.listeners {
		ln.Close()
	}
	h.listeners = nil
	h.mu.Unlock()
	h.billing.stop()
}

func (h *hostServer) acceptLoop(ln net.Listener, handler func(net.Conn)) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		if !clientAllowed(c.RemoteAddr()) {
			logf("Conexão recusada de %s (fora da rede do hotspot)", ipOf(c.RemoteAddr()))
			c.Close()
			continue
		}
		go func() {
			h.conns.Add(1)
			defer h.conns.Add(-1)
			defer c.Close()
			handler(c)
		}()
	}
}

// billLoopback só é ligado nos testes automáticos.
var billLoopback = false

// billedIP: o próprio notebook (loopback) não é cobrado.
func billedIP(c net.Conn) string {
	ip := net.ParseIP(ipOf(c.RemoteAddr()))
	if ip == nil || (ip.IsLoopback() && !billLoopback) {
		return ""
	}
	return ip.String()
}

func (h *hostServer) dial(target string) (net.Conn, error) {
	c, err := net.DialTimeout("tcp", target, 10*time.Second)
	if err != nil {
		return nil, err
	}
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	return c, nil
}

// pipe copia nos dois sentidos, contando e cobrando os bytes do cliente.
func (h *hostServer) pipe(client net.Conn, clientR io.Reader, remote net.Conn, ip string) {
	if ip != "" && !h.meter.allowed(ip) && !h.meter.waitForPayment(ip) {
		return
	}
	done := make(chan struct{}, 2)
	cp := func(dst io.Writer, src io.Reader, toClient bool) {
		buf := make([]byte, 16*1024)
		for {
			n, err := src.Read(buf)
			if n > 0 {
				if _, werr := dst.Write(buf[:n]); werr != nil {
					break
				}
				if toClient {
					h.sent.Add(int64(n))
				}
				if ip != "" {
					h.meter.add(ip, int64(n))
					if !h.meter.allowed(ip) && !h.meter.waitForPayment(ip) {
						break
					}
				}
			}
			if err != nil {
				break
			}
		}
		done <- struct{}{}
	}
	go cp(remote, clientR, false)
	go cp(client, remote, true)
	<-done
	client.Close()
	remote.Close()
	<-done
}

// ---------------------------------------------------------------- SOCKS5

func socksReply(w io.Writer, rep byte, addr net.Addr) {
	ip := net.IPv4zero.To4()
	port := 0
	if ta, ok := addr.(*net.TCPAddr); ok && ta.IP.To4() != nil {
		ip, port = ta.IP.To4(), ta.Port
	} else if ua, ok := addr.(*net.UDPAddr); ok && ua.IP.To4() != nil {
		ip, port = ua.IP.To4(), ua.Port
	}
	msg := append([]byte{5, rep, 0, 1}, ip...)
	msg = binary.BigEndian.AppendUint16(msg, uint16(port))
	_, _ = w.Write(msg)
}

func readSocksAddr(r *bufio.Reader) (string, error) {
	atyp, err := r.ReadByte()
	if err != nil {
		return "", err
	}
	var host string
	switch atyp {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	case 3:
		l, err := r.ReadByte()
		if err != nil {
			return "", err
		}
		b := make([]byte, l)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		host = string(b)
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	default:
		return "", errors.New("endereço SOCKS inválido")
	}
	pb := make([]byte, 2)
	if _, err := io.ReadFull(r, pb); err != nil {
		return "", err
	}
	return net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(pb)))), nil
}

func (h *hostServer) handleSocks(c net.Conn) {
	r := bufio.NewReader(c)
	head := make([]byte, 2)
	if _, err := io.ReadFull(r, head); err != nil || head[0] != 5 {
		return
	}
	if _, err := io.ReadFull(r, make([]byte, head[1])); err != nil {
		return
	}
	_, _ = c.Write([]byte{5, 0})
	req := make([]byte, 3)
	if _, err := io.ReadFull(r, req); err != nil || req[0] != 5 {
		return
	}
	target, err := readSocksAddr(r)
	if err != nil {
		return
	}
	switch req[1] {
	case 1:
		remote, err := h.dial(target)
		if err != nil {
			socksReply(c, 5, nil)
			return
		}
		socksReply(c, 0, remote.LocalAddr())
		h.pipe(c, r, remote, billedIP(c))
	case 3:
		h.udpAssociate(c, r)
	default:
		socksReply(c, 7, nil)
	}
}

func (h *hostServer) udpAssociate(c net.Conn, r *bufio.Reader) {
	local := c.LocalAddr().(*net.TCPAddr)
	udp, err := net.ListenUDP("udp", &net.UDPAddr{IP: local.IP, Port: 0})
	if err != nil {
		socksReply(c, 1, nil)
		return
	}
	defer udp.Close()
	socksReply(c, 0, udp.LocalAddr())
	clientIP := net.ParseIP(ipOf(c.RemoteAddr()))
	ip := billedIP(c)
	var clientAddr atomic.Pointer[net.UDPAddr]
	outward, err := net.ListenUDP("udp", nil)
	if err != nil {
		return
	}
	defer outward.Close()

	go func() { // do cliente para a internet
		buf := make([]byte, 65535)
		for {
			n, from, err := udp.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if !from.IP.Equal(clientIP) || n < 10 || buf[2] != 0 {
				continue
			}
			clientAddr.Store(from)
			br := bufio.NewReader(bytes.NewReader(buf[3:n]))
			dst, err := readSocksAddr(br)
			if err != nil {
				continue
			}
			payload, _ := io.ReadAll(br)
			ua, err := net.ResolveUDPAddr("udp", dst)
			if err != nil {
				continue
			}
			if ip != "" {
				if !h.meter.allowed(ip) {
					continue
				}
				h.meter.add(ip, int64(len(payload)))
			}
			_, _ = outward.WriteToUDP(payload, ua)
		}
	}()
	go func() { // da internet para o cliente
		buf := make([]byte, 65535)
		for {
			n, from, err := outward.ReadFromUDP(buf)
			if err != nil {
				return
			}
			to := clientAddr.Load()
			if to == nil {
				continue
			}
			hdr := []byte{0, 0, 0}
			if v4 := from.IP.To4(); v4 != nil {
				hdr = append(append(hdr, 1), v4...)
			} else {
				hdr = append(append(hdr, 4), from.IP.To16()...)
			}
			hdr = binary.BigEndian.AppendUint16(hdr, uint16(from.Port))
			if ip != "" {
				h.meter.add(ip, int64(n))
			}
			h.sent.Add(int64(n))
			_, _ = udp.WriteToUDP(append(hdr, buf[:n]...), to)
		}
	}()
	// A associação dura enquanto a conexão TCP de controle estiver aberta.
	_, _ = io.Copy(io.Discard, r)
}

// ------------------------------------------------------------------ HTTP

func (h *hostServer) handleHTTP(c net.Conn) {
	r := bufio.NewReader(c)
	req, err := http.ReadRequest(r)
	if err != nil {
		return
	}
	if req.Method != http.MethodConnect && strings.HasPrefix(req.URL.Path, "/redep2p/") && (req.URL.Host == "" || isLocalAddr(req.URL.Hostname())) {
		h.serveLocal(c, req)
		return
	}
	ip := billedIP(c)
	if req.Method == http.MethodConnect {
		remote, err := h.dial(req.Host)
		if err != nil {
			io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
			return
		}
		io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\n")
		h.pipe(c, r, remote, ip)
		return
	}
	target := req.URL.Host
	if _, _, err := net.SplitHostPort(target); err != nil {
		target = net.JoinHostPort(target, "80")
	}
	remote, err := h.dial(target)
	if err != nil {
		io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
		return
	}
	req.Header.Del("Proxy-Connection")
	req.Header.Set("Connection", "close")
	req.Close = true
	req.RequestURI = ""
	if err := req.Write(remote); err != nil {
		remote.Close()
		return
	}
	h.pipe(c, r, remote, ip)
}

func isLocalAddr(host string) bool {
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	addrs, _ := net.InterfaceAddrs()
	for _, a := range addrs {
		if ipn, ok := a.(*net.IPNet); ok && ipn.IP.Equal(ip) {
			return true
		}
	}
	return false
}

// linkLocalOf devolve o IPv6 link-local da interface que tem o endereço ip.
func linkLocalOf(ip net.IP) string {
	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		addrs, _ := ifc.Addrs()
		has := false
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && ipn.IP.Equal(ip) {
				has = true
			}
		}
		if !has {
			continue
		}
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() == nil && ipn.IP.IsLinkLocalUnicast() {
				return ipn.IP.String()
			}
		}
	}
	return ""
}

func (h *hostServer) serveLocal(c net.Conn, req *http.Request) {
	var body string
	switch req.URL.Path {
	case infoPath:
		local := c.LocalAddr().(*net.TCPAddr)
		body = fmt.Sprintf("hops=0\nll=%s\n", linkLocalOf(local.IP))
	case usagePath:
		ip := ipOf(c.RemoteAddr())
		body = fmt.Sprintf("client=%s\nbytes=%d\nhost=%s\n", ip, h.meter.get(ip), h.id.hex())
	default:
		io.WriteString(c, "HTTP/1.0 404 Not Found\r\nConnection: close\r\n\r\n")
		return
	}
	fmt.Fprintf(c, "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", len(body), body)
}
