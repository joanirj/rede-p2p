package main

// Proxy HTTP local (127.0.0.1:8081). O Windows e os navegadores mandam o tráfego
// para cá (via PAC do sistema); daqui ele segue para o proxy SOCKS5 do
// compartilhador. Também conta os bytes, para conferir a cobrança.

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

var trafficBytes atomic.Int64

type countingConn struct{ net.Conn }

func (c countingConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	trafficBytes.Add(int64(n))
	return n, err
}

func (c countingConn) Write(p []byte) (int, error) {
	n, err := c.Conn.Write(p)
	trafficBytes.Add(int64(n))
	return n, err
}

// socksDial abre uma conexão até target (host:porta) pelo SOCKS5 do compartilhador.
func socksDial(proxyHost, target string, timeout time.Duration) (net.Conn, error) {
	host, portStr, err := net.SplitHostPort(target)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, err
	}
	c, err := net.DialTimeout("tcp", net.JoinHostPort(proxyHost, strconv.Itoa(socksPort)), timeout)
	if err != nil {
		return nil, err
	}
	_ = c.SetDeadline(time.Now().Add(timeout))
	fail := func(e error) (net.Conn, error) { c.Close(); return nil, e }
	if _, err := c.Write([]byte{5, 1, 0}); err != nil {
		return fail(err)
	}
	resp := make([]byte, 2)
	if _, err := io.ReadFull(c, resp); err != nil || resp[0] != 5 || resp[1] != 0 {
		return fail(errors.New("o compartilhador não respondeu como SOCKS5"))
	}
	req := []byte{5, 1, 0}
	if ip := net.ParseIP(host); ip != nil && ip.To4() != nil {
		req = append(req, 1)
		req = append(req, ip.To4()...)
	} else if ip != nil {
		req = append(req, 4)
		req = append(req, ip.To16()...)
	} else {
		if len(host) > 255 {
			return fail(errors.New("nome longo demais"))
		}
		req = append(req, 3, byte(len(host)))
		req = append(req, host...)
	}
	req = binary.BigEndian.AppendUint16(req, uint16(port))
	if _, err := c.Write(req); err != nil {
		return fail(err)
	}
	head := make([]byte, 4)
	if _, err := io.ReadFull(c, head); err != nil {
		return fail(err)
	}
	if head[1] != 0 {
		return fail(fmt.Errorf("o compartilhador não alcançou o destino (código %d)", head[1]))
	}
	var skip int
	switch head[3] {
	case 1:
		skip = 4
	case 4:
		skip = 16
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(c, l); err != nil {
			return fail(err)
		}
		skip = int(l[0])
	}
	if _, err := io.ReadFull(c, make([]byte, skip+2)); err != nil {
		return fail(err)
	}
	_ = c.SetDeadline(time.Time{})
	return c, nil
}

type localProxy struct {
	mu   sync.Mutex
	host string // compartilhador atual; vazio = desconectado
	ln   net.Listener
}

func (p *localProxy) close() {
	if p.ln != nil {
		p.ln.Close()
	}
}

func (p *localProxy) setHost(h string) {
	p.mu.Lock()
	p.host = h
	p.mu.Unlock()
}

func (p *localProxy) currentHost() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.host
}

func (p *localProxy) serve() error {
	ln, err := net.Listen("tcp", localProxyAddr)
	if err != nil {
		return err
	}
	p.ln = ln
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go p.handle(c)
		}
	}()
	return nil
}

func (p *localProxy) handle(client net.Conn) {
	defer client.Close()
	br := bufio.NewReader(client)
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	host := p.currentHost()
	if host == "" {
		io.WriteString(client, "HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\nRede P2P desconectada.\r\n")
		return
	}
	if req.Method == http.MethodConnect {
		remote, err := socksDial(host, req.Host, 12*time.Second)
		if err != nil {
			io.WriteString(client, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
			return
		}
		defer remote.Close()
		io.WriteString(client, "HTTP/1.1 200 Connection Established\r\n\r\n")
		relay(&bufferedConn{client, br}, countingConn{remote})
		return
	}
	target := req.URL.Host
	if target == "" {
		target = req.Host
	}
	if _, _, err := net.SplitHostPort(target); err != nil {
		target = net.JoinHostPort(target, "80")
	}
	remote, err := socksDial(host, target, 12*time.Second)
	if err != nil {
		io.WriteString(client, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
		return
	}
	defer remote.Close()
	cr := countingConn{remote}
	req.Header.Del("Proxy-Connection")
	req.Header.Del("Proxy-Authorization")
	req.Header.Set("Connection", "close")
	req.Close = true
	req.RequestURI = ""
	if err := req.Write(cr); err != nil {
		return
	}
	_, _ = io.Copy(client, cr)
}

type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (b *bufferedConn) Read(p []byte) (int, error) { return b.r.Read(p) }

func relay(a, b net.Conn) {
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		_, _ = io.Copy(dst, src)
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
	// Um lado terminou: fecha os dois para a outra cópia terminar também.
	a.Close()
	b.Close()
	<-done
}
