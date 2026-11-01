# Rede P2P — versão de teste

App Android em que um aparelho com Wi-Fi compartilha a internet com outros por Wi-Fi Direct.
Esta é a **etapa 1** da prova de conceito: só conectividade, ainda sem créditos e sem repetidor.

## Como instalar

1. No celular ou tablet, abra **Releases** neste repositório (ou `github.com/joanirj/rede-p2p/releases/latest`).
2. Baixe o arquivo `rede-p2p.apk` e abra.
3. Se o Android pedir, permita "instalar apps desconhecidos" para o navegador.
   Se aparecer o aviso do Play Protect, escolha instalar mesmo assim.

## Como testar

1. **Compartilhador** (aparelho conectado ao Wi-Fi de casa, Android 10 ou superior):
   abra o app e toque em **Compartilhar internet**. Aceite as permissões.
2. **Cliente** (outro aparelho, sem internet: esqueça o Wi-Fi de casa nele e desligue os dados móveis):
   abra o app e toque em **Usar internet de outro aparelho**. Aceite as permissões e o pedido de VPN.
3. No cliente, abra um site ou o WhatsApp.
4. Se algo der errado, toque em **Copiar log** nos dois aparelhos e cole na conversa.

No Android 10, deixe a **localização** ligada: o sistema exige isso para o Wi-Fi Direct.

## Como testar o repetidor (3 aparelhos)

1. **Compartilhador:** no Wi-Fi de casa, toque em **Compartilhar internet**.
2. **Repetidor:** toque em **Repetir internet de outro aparelho** e confirme a rede `DIRECT-RP-RedeP2P`
   na janela do Android. O status deve mostrar "Repetindo (2 salto(s) até a internet)".
   Para o repetidor também navegar, toque depois em **Usar internet de outro aparelho**.
3. **Cliente da ponta:** toque em **Usar internet de um repetidor**.

Todo dono de grupo Wi-Fi Direct no Android tem o endereço 192.168.49.1. Por isso o repetidor fala com o
aparelho de cima pelo endereço IPv6 link-local dele, descoberto em `http://192.168.49.1:8080/redep2p/info`
antes de criar a própria rede.

## Como funciona

- O compartilhador cria um grupo Wi-Fi Direct (`DIRECT-RP-RedeP2P`) e roda um proxy SOCKS5
  (porta 1080) e HTTP (porta 8080) que só aceita conexões dessa rede.
- O cliente entra no grupo pelo nome e senha, cria uma VPN local e envia todo o tráfego ao proxy
  com o [tun2socks](https://github.com/xjasonlyu/tun2socks).
- Cada `push` na branch `main` compila o APK no GitHub Actions e publica em Releases.

## Estrutura

- `app/` — app Android (Kotlin)
- `tun2socks-bridge/` — ponte em Go para o tun2socks, compilada com gomobile
- `.github/workflows/build.yml` — compilação automática
