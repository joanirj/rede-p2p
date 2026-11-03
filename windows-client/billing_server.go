package main

// Lado compartilhador da cobrança, igual ao BillingServer do Android.

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"sort"
	"sync"
	"time"
)

const billStep = mb
const rebillInterval = 5 * time.Second

type hostSession struct {
	ip       string
	client   []byte
	id       []byte
	w        *bufio.Writer
	wmu      sync.Mutex
	baseUsed int64

	mu          sync.Mutex
	paidBytes   int64
	paidAmount  int64
	seq         int64
	billedBytes int64
	lastBill    time.Time
	warned      bool
}

func (s *hostSession) send(m map[string]any) error {
	s.wmu.Lock()
	defer s.wmu.Unlock()
	return send(s.w, m)
}

type billingServer struct {
	id    *identity
	led   *ledger
	meter *meter

	mu       sync.Mutex
	sessions map[string]*hostSession
	stopCh   chan struct{}
}

func newBillingServer(id *identity, led *ledger, m *meter) *billingServer {
	return &billingServer{id: id, led: led, meter: m, sessions: map[string]*hostSession{}}
}

func (b *billingServer) start() {
	b.stopCh = make(chan struct{})
	b.meter.mu.Lock()
	b.meter.policy = b.allow
	b.meter.mu.Unlock()
	go b.tick(b.stopCh)
	logf("Cobrança ativa: %s crédito(s) por MB", credits(pricePerMB))
}

func (b *billingServer) stop() {
	if b.stopCh != nil {
		close(b.stopCh)
		b.stopCh = nil
	}
	b.meter.mu.Lock()
	b.meter.policy = nil
	b.meter.mu.Unlock()
	b.mu.Lock()
	b.sessions = map[string]*hostSession{}
	b.mu.Unlock()
}

func (b *billingServer) allow(ip string, used int64) bool {
	b.mu.Lock()
	s := b.sessions[ip]
	b.mu.Unlock()
	unpaid := used
	if s != nil {
		s.mu.Lock()
		unpaid = used - s.baseUsed - s.paidBytes
		ok := unpaid <= unpaidLimitBytes
		if !ok && !s.warned {
			s.warned = true
			logf("Cliente %s passou de %s sem pagar: tráfego pausado até chegar um recibo", hex.EncodeToString(s.client)[:8], megabytes(unpaidLimitBytes))
		}
		s.mu.Unlock()
		return ok
	}
	return unpaid <= unpaidLimitBytes
}

func (b *billingServer) handle(c net.Conn) {
	ip := ipOf(c.RemoteAddr())
	r := bufio.NewReader(c)
	line, err := r.ReadBytes('\n')
	if err != nil {
		return
	}
	var hello struct {
		T      string `json:"t"`
		Client string `json:"client"`
	}
	if json.Unmarshal(line, &hello) != nil || hello.T != "hello" {
		return
	}
	client, err := hex.DecodeString(hello.Client)
	if err != nil || len(client) != 32 {
		return
	}
	sid := make([]byte, 16)
	_, _ = rand.Read(sid)
	s := &hostSession{ip: ip, client: client, id: sid, w: bufio.NewWriter(c), baseUsed: b.meter.get(ip)}
	b.mu.Lock()
	b.sessions[ip] = s
	b.mu.Unlock()
	sig := b.id.sign(offerHash(sid, b.id.pk, client, pricePerMB))
	if err := s.send(map[string]any{"t": "offer", "session": hex.EncodeToString(sid), "host": b.id.hex(), "price": pricePerMB, "sig": hex.EncodeToString(sig)}); err != nil {
		return
	}
	short := hello.Client[:8]
	logf("Cliente %s (%s) abriu uma sessão de cobrança", short, ip)
	defer func() {
		b.mu.Lock()
		if b.sessions[ip] == s {
			delete(b.sessions, ip)
		}
		b.mu.Unlock()
		s.mu.Lock()
		logf("Sessão de %s encerrada: %s pagos, %s créditos recebidos", short, megabytes(s.paidBytes), credits(s.paidAmount))
		s.mu.Unlock()
	}()
	for {
		line, err := r.ReadBytes('\n')
		if err != nil {
			return
		}
		var msg struct {
			T      string `json:"t"`
			Seq    int64  `json:"seq"`
			Bytes  int64  `json:"bytes"`
			Amount int64  `json:"amount"`
			Sig    string `json:"sig"`
			Reason string `json:"reason"`
		}
		if json.Unmarshal(line, &msg) != nil {
			continue
		}
		switch msg.T {
		case "receipt":
			b.onReceipt(s, msg.Seq, msg.Bytes, msg.Amount, msg.Sig)
		case "dispute":
			logf("ERRO: cliente %s contestou a conta: %s", short, msg.Reason)
		case "low_balance":
			logf("Cliente %s está sem saldo", short)
		}
	}
}

func (b *billingServer) onReceipt(s *hostSession, seq, bytes, amount int64, sigHex string) {
	sig, _ := hex.DecodeString(sigHex)
	usedNow := b.meter.get(s.ip) - s.baseUsed
	s.mu.Lock()
	defer s.mu.Unlock()
	var problem string
	switch {
	case seq <= s.seq:
		problem = "sequência repetida"
	case bytes < s.paidBytes:
		problem = "bytes menores que o último recibo"
	case bytes > usedNow+65536:
		problem = "recibo acima do que foi usado"
	case amount != priceFor(bytes, pricePerMB):
		problem = "valor não confere com o custo"
	case !schnorrVerify(receiptHash(s.id, b.id.pk, s.client, seq, bytes, amount), s.client, sig):
		problem = "assinatura inválida"
	}
	if problem != "" {
		logf("ERRO: recibo recusado de %s: %s", hex.EncodeToString(s.client)[:8], problem)
		return
	}
	delta := amount - s.paidAmount
	prev := s.paidBytes
	s.seq, s.paidBytes, s.paidAmount, s.warned = seq, bytes, amount, false
	b.led.credit(delta)
	if bytes/(5*mb) > prev/(5*mb) {
		logf("Cliente %s: %s pagos (%s créditos), recibo nº %d, em aberto %s",
			hex.EncodeToString(s.client)[:8], megabytes(bytes), credits(amount), seq, megabytes(usedNow-bytes))
	}
}

func (b *billingServer) tick(stop chan struct{}) {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case now := <-t.C:
			b.mu.Lock()
			list := make([]*hostSession, 0, len(b.sessions))
			for _, s := range b.sessions {
				list = append(list, s)
			}
			b.mu.Unlock()
			for _, s := range list {
				usedNow := b.meter.get(s.ip) - s.baseUsed
				s.mu.Lock()
				step := usedNow-s.billedBytes >= billStep
				tail := usedNow > s.paidBytes+65536 && now.Sub(s.lastBill) >= rebillInterval
				if step || tail {
					s.billedBytes, s.lastBill = usedNow, now
				}
				s.mu.Unlock()
				if step || tail {
					_ = s.send(map[string]any{"t": "bill", "bytes": usedNow, "amount": priceFor(usedNow, pricePerMB)})
				}
			}
		}
	}
}

// Summary descreve os clientes conectados, para a tela.
func (b *billingServer) Summary() []string {
	b.mu.Lock()
	defer b.mu.Unlock()
	var out []string
	for _, s := range b.sessions {
		s.mu.Lock()
		used := b.meter.get(s.ip) - s.baseUsed
		out = append(out, fmt.Sprintf("Cliente %s (%s): usou %s, pagou %s créditos",
			hex.EncodeToString(s.client)[:8], s.ip, megabytes(used), credits(s.paidAmount)))
		s.mu.Unlock()
	}
	sort.Strings(out)
	return out
}
