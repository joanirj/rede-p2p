package main

import (
	"encoding/hex"
	"strings"
	"testing"
)

func unhexT(t *testing.T, s string) []byte {
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// Vetores oficiais do BIP340 (índices 0 e 1).
func TestBIP340Vectors(t *testing.T) {
	vectors := []struct{ sk, pk, aux, msg, sig string }{
		{"0000000000000000000000000000000000000000000000000000000000000003",
			"F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
			"0000000000000000000000000000000000000000000000000000000000000000",
			"0000000000000000000000000000000000000000000000000000000000000000",
			"E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0"},
		{"B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF",
			"DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
			"0000000000000000000000000000000000000000000000000000000000000001",
			"243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
			"6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A"},
	}
	for i, v := range vectors {
		sk := unhexT(t, v.sk)
		pk, err := schnorrPublicKey(sk)
		if err != nil || !strings.EqualFold(hex.EncodeToString(pk), v.pk) {
			t.Fatalf("vetor %d: chave pública errada", i)
		}
		sig, err := schnorrSign(unhexT(t, v.msg), sk, unhexT(t, v.aux))
		if err != nil || !strings.EqualFold(hex.EncodeToString(sig), v.sig) {
			t.Fatalf("vetor %d: assinatura errada %x", i, sig)
		}
		if !schnorrVerify(unhexT(t, v.msg), pk, sig) {
			t.Fatalf("vetor %d: verificação falhou", i)
		}
		sig[63] ^= 1
		if schnorrVerify(unhexT(t, v.msg), pk, sig) {
			t.Fatalf("vetor %d: assinatura adulterada foi aceita", i)
		}
	}
}

func TestPriceFor(t *testing.T) {
	if priceFor(mb, pricePerMB) != 1000 || priceFor(1, pricePerMB) != 1 || priceFor(0, pricePerMB) != 0 {
		t.Fatal("priceFor não confere com o app Android")
	}
}
