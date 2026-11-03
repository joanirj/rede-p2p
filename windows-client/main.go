// Cliente da Rede P2P para Windows: usa a internet de um compartilhador
// (celular, tablet ou outro PC com o app) e mede o alcance da rede.
package main

import (
	_ "embed"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

//go:embed ui.html
var uiPage []byte

func main() {
	initLog()
	logf("Rede P2P para Windows · versão %s", appVersion)
	recoverSystemProxy()

	a := newApp()
	logf("Identidade deste notebook: %s", a.id.short())

	if err := a.proxy.serve(); err != nil {
		fmt.Println("ERRO: não consegui abrir o proxy local em", localProxyAddr, "—", err)
		fmt.Println("Outro programa já usa essa porta? Feche-o e abra este app de novo.")
		pause()
		return
	}
	ln, err := net.Listen("tcp", uiAddr)
	if err != nil {
		// Provavelmente o app já está aberto: só mostra a tela.
		openBrowser("http://" + uiAddr)
		fmt.Println("O app já parece estar aberto. A tela foi aberta no navegador.")
		time.Sleep(3 * time.Second)
		return
	}
	srv := &http.Server{Handler: a.routes(uiPage), ReadHeaderTimeout: 10 * time.Second}
	go func() { _ = srv.Serve(ln) }()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)

	fmt.Println()
	fmt.Println("  A tela do app abriu no navegador: http://" + uiAddr)
	fmt.Println("  Deixe esta janela aberta enquanto usar a Rede P2P.")
	fmt.Println("  Para sair, clique em Desconectar na tela e feche esta janela.")
	fmt.Println()
	openBrowser("http://" + uiAddr)

	<-stop
	logf("Fechando…")
	a.disconnect()
	a.stopSharing()
}

func pause() {
	fmt.Println("Pressione Enter para fechar.")
	var s string
	_, _ = fmt.Scanln(&s)
}
