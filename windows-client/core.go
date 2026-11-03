package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	appVersion = "0.1.0"

	// Iguais ao app Android (Core.kt / Billing.kt).
	passphrase  = "redep2p123"
	ssidPrefix  = "DIRECT-RP-"
	socksPort   = 1080
	httpPort    = 8080
	controlPort = 8090
	infoPath    = "/redep2p/info"

	mb                 = int64(1048576)
	pricePerMB         = int64(1000) // mili-créditos por MB
	maxAcceptedPrice   = int64(5000)
	unpaidLimitBytes   = 4 * mb
	initialBalance     = int64(200000) // 200 créditos (o notebook gasta mais nas medições)
	testTopUp          = int64(100000)
	localProxyAddr     = "127.0.0.1:8081"
	uiAddr             = "127.0.0.1:8765"
	githubIssueBaseURL = "https://github.com/joanirj/rede-p2p/issues/new"
)

func priceFor(bytes, milliPerMB int64) int64 { return (bytes*milliPerMB + mb - 1) / mb }

func credits(milli int64) string {
	return strings.Replace(fmt.Sprintf("%.2f", float64(milli)/1000), ".", ",", 1)
}

func megabytes(b int64) string {
	return strings.Replace(fmt.Sprintf("%.1f MB", float64(b)/float64(mb)), ".", ",", 1)
}

func dataDir() string {
	d, err := os.UserConfigDir()
	if err != nil {
		d = "."
	}
	p := filepath.Join(d, "RedeP2P")
	_ = os.MkdirAll(p, 0o700)
	return p
}

// ------------------------------------------------------------------ log

var (
	logMu    sync.Mutex
	logLines []string
	logFile  *os.File
)

func initLog() {
	path := filepath.Join(dataDir(), "log.txt")
	if b, err := os.ReadFile(path); err == nil {
		lines := strings.Split(strings.TrimRight(string(b), "\n"), "\n")
		if len(lines) > 300 {
			lines = lines[len(lines)-300:]
		}
		logLines = append(logLines, lines...)
		_ = os.WriteFile(path, []byte(strings.Join(lines, "\n")+"\n"), 0o600)
	}
	if f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600); err == nil {
		logFile = f
	}
}

func logf(format string, a ...any) {
	line := time.Now().Format("15:04:05") + " " + fmt.Sprintf(format, a...)
	logMu.Lock()
	logLines = append(logLines, line)
	if len(logLines) > 800 {
		logLines = logLines[len(logLines)-800:]
	}
	if logFile != nil {
		_, _ = logFile.WriteString(line + "\n")
	}
	logMu.Unlock()
	fmt.Println(line)
}

func logText() string {
	logMu.Lock()
	defer logMu.Unlock()
	return strings.Join(logLines, "\n")
}

func clearLog() {
	logMu.Lock()
	logLines = nil
	if logFile != nil {
		_ = logFile.Truncate(0)
	}
	logMu.Unlock()
}

// ------------------------------------------------------------- identidade

type identity struct {
	sk, pk []byte
}

func loadIdentity() *identity {
	path := filepath.Join(dataDir(), "identidade.key")
	if b, err := os.ReadFile(path); err == nil {
		if sk, err := hex.DecodeString(strings.TrimSpace(string(b))); err == nil {
			if pk, err := schnorrPublicKey(sk); err == nil {
				return &identity{sk, pk}
			}
		}
		logf("ERRO: identidade salva inválida; criando outra")
	}
	for {
		sk := make([]byte, 32)
		if _, err := rand.Read(sk); err != nil {
			panic(err)
		}
		if pk, err := schnorrPublicKey(sk); err == nil {
			_ = os.WriteFile(path, []byte(hex.EncodeToString(sk)), 0o600)
			return &identity{sk, pk}
		}
	}
}

func (id *identity) sign(hash []byte) []byte {
	aux := make([]byte, 32)
	_, _ = rand.Read(aux)
	sig, err := schnorrSign(hash, id.sk, aux)
	if err != nil {
		panic(err)
	}
	return sig
}

func (id *identity) hex() string   { return hex.EncodeToString(id.pk) }
func (id *identity) short() string { return id.hex()[:8] }

// ------------------------------------------------------------------ saldo

type ledger struct {
	mu      sync.Mutex
	Balance int64 `json:"balance"`
	Earned  int64 `json:"earned"`
	Spent   int64 `json:"spent"`
	path    string
}

func loadLedger() *ledger {
	l := &ledger{Balance: initialBalance, path: filepath.Join(dataDir(), "saldo.json")}
	if b, err := os.ReadFile(l.path); err == nil {
		_ = json.Unmarshal(b, l)
	} else {
		l.save()
	}
	return l
}

func (l *ledger) save() {
	b, _ := json.Marshal(l)
	_ = os.WriteFile(l.path, b, 0o600)
}

func (l *ledger) debit(milli int64) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.Balance < milli {
		return false
	}
	l.Balance -= milli
	l.Spent += milli
	l.save()
	return true
}

func (l *ledger) topUp(milli int64) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.Balance += milli
	l.save()
}

func (l *ledger) snapshot() (int64, int64, int64) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.Balance, l.Earned, l.Spent
}
