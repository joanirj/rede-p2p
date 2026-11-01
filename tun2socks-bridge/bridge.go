// Package bridge expõe o tun2socks para o app Android (via gomobile).
// Ele recebe o descritor da VPN local e encaminha todo o tráfego para o
// proxy SOCKS5 do compartilhador.
package bridge

import (
	"fmt"
	"sync"

	"github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
	mu      sync.Mutex
	running bool
)

// Start inicia o túnel. fd é o descritor da interface TUN criada pelo VpnService
// e proxy é algo como "socks5://192.168.49.1:1080".
func Start(fd int, proxy string, mtu int) error {
	mu.Lock()
	defer mu.Unlock()
	if running {
		return fmt.Errorf("túnel já está em execução")
	}
	engine.Insert(&engine.Key{
		Device:   fmt.Sprintf("fd://%d", fd),
		Proxy:    proxy,
		MTU:      mtu,
		LogLevel: "warning",
	})
	engine.Start()
	running = true
	return nil
}

// Stop encerra o túnel e fecha o descritor.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if !running {
		return
	}
	engine.Stop()
	running = false
}
