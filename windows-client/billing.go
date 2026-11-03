package main

// Sessão de cobrança com o compartilhador, no mesmo protocolo do app Android
// (Billing.kt): oferta assinada, contas a cada MB e recibos cumulativos assinados.

import (
	"bufio"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"sync"
	"time"
)

func be64(v int64) []byte {
	b := make([]byte, 8)
	binary.BigEndian.PutUint64(b, uint64(v))
	return b
}

func offerHash(session, host, client []byte, price int64) []byte {
	return sha([]byte("RedeP2P/oferta/v1"), session, host, client, be64(price))
}

func receiptHash(session, host, client []byte, seq, bytes, amount int64) []byte {
	return sha([]byte("RedeP2P/recibo/v1"), session, host, client, be64(seq), be64(bytes), be64(amount))
}

type billingClient struct {
	host    string
	id      *identity
	led     *ledger
	measure func() int64

	mu      sync.Mutex
	conn    net.Conn
	stopped bool
	summary string
}

func newBillingClient(host string, id *identity, led *ledger, measure func() int64) *billingClient {
	return &billingClient{host: host, id: id, led: led, measure: measure}
}

func (b *billingClient) start() { go b.run() }

func (b *billingClient) stop() {
	b.mu.Lock()
	b.stopped = true
	if b.conn != nil {
		_ = b.conn.Close()
	}
	b.summary = ""
	b.mu.Unlock()
}

func (b *billingClient) Summary() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.summary
}

func (b *billingClient) setSummary(s string) {
	b.mu.Lock()
	b.summary = s
	b.mu.Unlock()
}

func (b *billingClient) isStopped() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.stopped
}

func send(w *bufio.Writer, m map[string]any) error {
	data, err := json.Marshal(m)
	if err != nil {
		return err
	}
	if _, err := w.Write(append(data, '\n')); err != nil {
		return err
	}
	return w.Flush()
}

func (b *billingClient) run() {
	err := b.session()
	if err != nil && !b.isStopped() {
		logf("ERRO: cobrança encerrada — %v", err)
	}
}

func (b *billingClient) session() error {
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(b.host, fmt.Sprint(controlPort)), 10*time.Second)
	if err != nil {
		return err
	}
	b.mu.Lock()
	if b.stopped {
		b.mu.Unlock()
		conn.Close()
		return nil
	}
	b.conn = conn
	b.mu.Unlock()
	defer conn.Close()

	r := bufio.NewReader(conn)
	w := bufio.NewWriter(conn)
	if err := send(w, map[string]any{"t": "hello", "v": 1, "client": b.id.hex()}); err != nil {
		return err
	}

	var offer struct {
		T       string `json:"t"`
		Session string `json:"session"`
		Host    string `json:"host"`
		Price   int64  `json:"price"`
		Sig     string `json:"sig"`
	}
	line, err := r.ReadBytes('\n')
	if err != nil {
		return err
	}
	if err := json.Unmarshal(line, &offer); err != nil || offer.T != "offer" {
		return errors.New("oferta inválida")
	}
	session, _ := hex.DecodeString(offer.Session)
	hostKey, _ := hex.DecodeString(offer.Host)
	sig, _ := hex.DecodeString(offer.Sig)
	if !schnorrVerify(offerHash(session, hostKey, b.id.pk, offer.Price), hostKey, sig) {
		return errors.New("oferta com assinatura inválida")
	}
	if offer.Price > maxAcceptedPrice {
		return fmt.Errorf("custo acima do limite (%s por MB)", credits(offer.Price))
	}
	hostID := offer.Host[:8]
	logf("Sessão de cobrança com o compartilhador %s: %s crédito(s) por MB", hostID, credits(offer.Price))

	baseline := b.measure()
	var seq, paidAmount, paidBytes int64
	b.setSummary(fmt.Sprintf("Pagando ao compartilhador %s: nada ainda", hostID))
	for !b.isStopped() {
		line, err := r.ReadBytes('\n')
		if err != nil {
			return err
		}
		var bill struct {
			T      string `json:"t"`
			Bytes  int64  `json:"bytes"`
			Amount int64  `json:"amount"`
		}
		if json.Unmarshal(line, &bill) != nil || bill.T != "bill" {
			continue
		}
		measured := b.measure() - baseline
		if bill.Amount != priceFor(bill.Bytes, offer.Price) {
			_ = send(w, map[string]any{"t": "dispute", "reason": "valor não confere com o custo"})
			continue
		}
		// Paga o que este aparelho já recebeu, no máximo o cobrado (igual ao Android).
		payBytes := bill.Bytes
		if measured < payBytes {
			payBytes = measured
		}
		if payBytes <= paidBytes {
			continue
		}
		payAmount := priceFor(payBytes, offer.Price)
		if !b.led.debit(payAmount - paidAmount) {
			logf("ERRO: saldo insuficiente. Use \"Recarregar saldo de teste\".")
			_ = send(w, map[string]any{"t": "low_balance"})
			continue
		}
		seq++
		rs := b.id.sign(receiptHash(session, hostKey, b.id.pk, seq, payBytes, payAmount))
		if err := send(w, map[string]any{"t": "receipt", "seq": seq, "bytes": payBytes, "amount": payAmount, "sig": hex.EncodeToString(rs)}); err != nil {
			return err
		}
		if payBytes/(5*mb) > paidBytes/(5*mb) {
			logf("Pago ao compartilhador %s: %s (%s créditos). Cobrado %s, medido aqui %s",
				hostID, megabytes(payBytes), credits(payAmount), megabytes(bill.Bytes), megabytes(measured))
		}
		paidAmount, paidBytes = payAmount, payBytes
		b.setSummary(fmt.Sprintf("Pagando ao compartilhador %s: %s, %s créditos", hostID, megabytes(payBytes), credits(payAmount)))
	}
	return nil
}
