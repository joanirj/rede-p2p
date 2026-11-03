package main

import (
	"bufio"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"sync/atomic"
	"testing"
	"time"
)

// Servidor SOCKS5 mínimo, no papel do compartilhador (só CONNECT).
func fakeSocks(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(socksPort))
	if err != nil {
		t.Skip("porta SOCKS ocupada:", err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				h := make([]byte, 2)
				io.ReadFull(c, h)
				io.ReadFull(c, make([]byte, h[1]))
				c.Write([]byte{5, 0})
				req := make([]byte, 4)
				io.ReadFull(c, req)
				var host string
				switch req[3] {
				case 1:
					b := make([]byte, 4)
					io.ReadFull(c, b)
					host = net.IP(b).String()
				case 3:
					l := make([]byte, 1)
					io.ReadFull(c, l)
					b := make([]byte, l[0])
					io.ReadFull(c, b)
					host = string(b)
				}
				pb := make([]byte, 2)
				io.ReadFull(c, pb)
				port := binary.BigEndian.Uint16(pb)
				r, err := net.Dial("tcp", net.JoinHostPort(host, strconv.Itoa(int(port))))
				if err != nil {
					c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
					return
				}
				defer r.Close()
				c.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0})
				relay(c, r)
			}(c)
		}
	}()
}

func TestProxyAndBilling(t *testing.T) {
	fakeSocks(t)
	payload := make([]byte, 3*mb)
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(payload) }))
	defer target.Close()

	p := &localProxy{}
	if err := p.serve(); err != nil {
		t.Skip("porta do proxy local ocupada:", err)
	}
	p.setHost("127.0.0.1")
	before := trafficBytes.Load()
	pu, _ := url.Parse("http://" + localProxyAddr)
	c := http.Client{Timeout: 10 * time.Second, Transport: &http.Transport{Proxy: http.ProxyURL(pu)}}
	resp, err := c.Get(target.URL)
	if err != nil {
		t.Fatal(err)
	}
	n, _ := io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if n != int64(len(payload)) {
		t.Fatalf("recebi %d bytes", n)
	}
	used := trafficBytes.Load() - before
	if used < int64(len(payload)) {
		t.Fatalf("contador do proxy local marcou só %d bytes", used)
	}

	// Compartilhador falso na porta de cobrança, seguindo Billing.kt.
	ln, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(controlPort))
	if err != nil {
		t.Skip("porta de cobrança ocupada:", err)
	}
	defer ln.Close()
	hostSK := make([]byte, 32)
	rand.Read(hostSK)
	hostSK[0] = 1
	hostPK, _ := schnorrPublicKey(hostSK)
	got := make(chan error, 1)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			got <- err
			return
		}
		defer conn.Close()
		r := bufio.NewReader(conn)
		var hello struct{ Client string }
		line, _ := r.ReadBytes('\n')
		json.Unmarshal(line, &hello)
		client, _ := hex.DecodeString(hello.Client)
		session := make([]byte, 16)
		rand.Read(session)
		aux := make([]byte, 32)
		sig, _ := schnorrSign(offerHash(session, hostPK, client, pricePerMB), hostSK, aux)
		o, _ := json.Marshal(map[string]any{"t": "offer", "session": hex.EncodeToString(session), "host": hex.EncodeToString(hostPK), "price": pricePerMB, "sig": hex.EncodeToString(sig)})
		conn.Write(append(o, '\n'))
		time.Sleep(200 * time.Millisecond)
		// Conta de 2 MB depois de o cliente já ter recebido 3 MB pelo proxy.
		bill, _ := json.Marshal(map[string]any{"t": "bill", "bytes": 2 * mb, "amount": priceFor(2*mb, pricePerMB)})
		conn.Write(append(bill, '\n'))
		line, err = r.ReadBytes('\n')
		if err != nil {
			got <- err
			return
		}
		var rc struct {
			T                  string
			Seq, Bytes, Amount int64
			Sig                string
		}
		json.Unmarshal(line, &rc)
		rs, _ := hex.DecodeString(rc.Sig)
		if rc.T != "receipt" || rc.Bytes != 2*mb || rc.Amount != 2000 || rc.Seq != 1 {
			got <- fmt.Errorf("recibo inesperado: %s", line)
			return
		}
		if !schnorrVerify(receiptHash(session, hostPK, client, rc.Seq, rc.Bytes, rc.Amount), client, rs) {
			got <- fmt.Errorf("assinatura do recibo inválida")
			return
		}
		got <- nil
	}()

	id := &identity{}
	id.sk = make([]byte, 32)
	rand.Read(id.sk)
	id.sk[0] = 2
	id.pk, _ = schnorrPublicKey(id.sk)
	led := &ledger{Balance: 100000, path: t.TempDir() + "/saldo.json"}
	// O cliente começa a contar a partir do início da sessão: simula 3 MB recebidos depois disso.
	var fake atomic.Int64
	b := newBillingClient("127.0.0.1", id, led, fake.Load)
	b.start()
	time.Sleep(100 * time.Millisecond)
	fake.Store(3 * mb)
	select {
	case err := <-got:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("o compartilhador falso não recebeu recibo")
	}
	b.stop()
	if bal, _, spent := led.snapshot(); spent != 2000 || bal != 98000 {
		t.Fatalf("saldo errado: %d gasto %d", bal, spent)
	}
}
