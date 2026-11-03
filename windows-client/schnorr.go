package main

// Assinaturas Schnorr sobre secp256k1 conforme o BIP340, iguais às do app Android
// (app/src/main/java/br/redep2p/crypto/Schnorr.java). Prova de conceito: não é de
// tempo constante.

import (
	"crypto/sha256"
	"errors"
	"math/big"
)

var (
	curveP = hexInt("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F")
	curveN = hexInt("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141")
	curveG = jac{hexInt("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798"),
		hexInt("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8"), big.NewInt(1)}
	sqrtExp = new(big.Int).Rsh(new(big.Int).Add(curveP, big.NewInt(1)), 2)
	two     = big.NewInt(2)
	three   = big.NewInt(3)
	seven   = big.NewInt(7)
	eight   = big.NewInt(8)
)

func hexInt(s string) *big.Int {
	v, ok := new(big.Int).SetString(s, 16)
	if !ok {
		panic("constante inválida")
	}
	return v
}

type jac struct{ x, y, z *big.Int }

var infinity = jac{big.NewInt(1), big.NewInt(1), big.NewInt(0)}

func (p jac) isInf() bool { return p.z.Sign() == 0 }

func md(a *big.Int) *big.Int { return new(big.Int).Mod(a, curveP) }

func mul2(a, b *big.Int) *big.Int { return md(new(big.Int).Mul(a, b)) }

func sub2(a, b *big.Int) *big.Int { return md(new(big.Int).Sub(a, b)) }

func add2(a, b *big.Int) *big.Int { return md(new(big.Int).Add(a, b)) }

func pdbl(p jac) jac {
	if p.isInf() || p.y.Sign() == 0 {
		return infinity
	}
	a := mul2(p.x, p.x)
	b := mul2(p.y, p.y)
	c := mul2(b, b)
	xb := add2(p.x, b)
	d := mul2(two, sub2(sub2(mul2(xb, xb), a), c))
	e := mul2(three, a)
	f := mul2(e, e)
	x3 := sub2(f, mul2(two, d))
	y3 := sub2(mul2(e, sub2(d, x3)), mul2(eight, c))
	z3 := mul2(mul2(two, p.y), p.z)
	return jac{x3, y3, z3}
}

func padd(p, q jac) jac {
	if p.isInf() {
		return q
	}
	if q.isInf() {
		return p
	}
	z1z1 := mul2(p.z, p.z)
	z2z2 := mul2(q.z, q.z)
	u1 := mul2(p.x, z2z2)
	u2 := mul2(q.x, z1z1)
	s1 := mul2(mul2(p.y, q.z), z2z2)
	s2 := mul2(mul2(q.y, p.z), z1z1)
	if u1.Cmp(u2) == 0 {
		if s1.Cmp(s2) == 0 {
			return pdbl(p)
		}
		return infinity
	}
	h := sub2(u2, u1)
	h2 := mul2(two, h)
	i := mul2(h2, h2)
	j := mul2(h, i)
	r := mul2(two, sub2(s2, s1))
	v := mul2(u1, i)
	x3 := sub2(sub2(mul2(r, r), j), mul2(two, v))
	y3 := sub2(mul2(r, sub2(v, x3)), mul2(mul2(two, s1), j))
	zs := add2(p.z, q.z)
	z3 := mul2(sub2(sub2(mul2(zs, zs), z1z1), z2z2), h)
	return jac{x3, y3, z3}
}

func pmul(p jac, k *big.Int) jac {
	r := infinity
	for i := k.BitLen() - 1; i >= 0; i-- {
		r = pdbl(r)
		if k.Bit(i) == 1 {
			r = padd(r, p)
		}
	}
	return r
}

func affine(p jac) (x, y *big.Int, ok bool) {
	if p.isInf() {
		return nil, nil, false
	}
	zi := new(big.Int).ModInverse(p.z, curveP)
	zi2 := mul2(zi, zi)
	return mul2(p.x, zi2), mul2(mul2(p.y, zi2), zi), true
}

func liftX(x *big.Int) (jac, bool) {
	if x.Cmp(curveP) >= 0 {
		return jac{}, false
	}
	c := add2(new(big.Int).Exp(x, three, curveP), seven)
	y := new(big.Int).Exp(c, sqrtExp, curveP)
	if mul2(y, y).Cmp(c) != 0 {
		return jac{}, false
	}
	if y.Bit(0) == 1 {
		y = new(big.Int).Sub(curveP, y)
	}
	return jac{new(big.Int).Set(x), y, big.NewInt(1)}, true
}

func bytes32(v *big.Int) []byte {
	out := make([]byte, 32)
	v.FillBytes(out)
	return out
}

func sha(parts ...[]byte) []byte {
	h := sha256.New()
	for _, p := range parts {
		h.Write(p)
	}
	return h.Sum(nil)
}

func taggedHash(tag string, parts ...[]byte) []byte {
	t := sha([]byte(tag))
	return sha(append([][]byte{t, t}, parts...)...)
}

func secretInt(sk []byte) (*big.Int, error) {
	if len(sk) != 32 {
		return nil, errors.New("chave privada deve ter 32 bytes")
	}
	d := new(big.Int).SetBytes(sk)
	if d.Sign() == 0 || d.Cmp(curveN) >= 0 {
		return nil, errors.New("chave privada fora do intervalo")
	}
	return d, nil
}

func schnorrPublicKey(sk []byte) ([]byte, error) {
	d, err := secretInt(sk)
	if err != nil {
		return nil, err
	}
	x, _, _ := affine(pmul(curveG, d))
	return bytes32(x), nil
}

func schnorrSign(msg, sk, aux []byte) ([]byte, error) {
	if len(msg) != 32 || len(aux) != 32 {
		return nil, errors.New("mensagem e aux devem ter 32 bytes")
	}
	d0, err := secretInt(sk)
	if err != nil {
		return nil, err
	}
	px, py, _ := affine(pmul(curveG, d0))
	d := d0
	if py.Bit(0) == 1 {
		d = new(big.Int).Sub(curveN, d0)
	}
	pxb := bytes32(px)
	auxHash := taggedHash("BIP0340/aux", aux)
	db := bytes32(d)
	t := make([]byte, 32)
	for i := range t {
		t[i] = db[i] ^ auxHash[i]
	}
	k0 := new(big.Int).Mod(new(big.Int).SetBytes(taggedHash("BIP0340/nonce", t, pxb, msg)), curveN)
	if k0.Sign() == 0 {
		return nil, errors.New("nonce nulo")
	}
	rx, ry, _ := affine(pmul(curveG, k0))
	k := k0
	if ry.Bit(0) == 1 {
		k = new(big.Int).Sub(curveN, k0)
	}
	rxb := bytes32(rx)
	e := new(big.Int).Mod(new(big.Int).SetBytes(taggedHash("BIP0340/challenge", rxb, pxb, msg)), curveN)
	s := new(big.Int).Mod(new(big.Int).Add(k, new(big.Int).Mul(e, d)), curveN)
	sig := append(rxb, bytes32(s)...)
	if !schnorrVerify(msg, pxb, sig) {
		return nil, errors.New("assinatura gerada não confere")
	}
	return sig, nil
}

func schnorrVerify(msg, pub, sig []byte) bool {
	if len(msg) != 32 || len(pub) != 32 || len(sig) != 64 {
		return false
	}
	P, ok := liftX(new(big.Int).SetBytes(pub))
	if !ok {
		return false
	}
	r := new(big.Int).SetBytes(sig[:32])
	s := new(big.Int).SetBytes(sig[32:])
	if r.Cmp(curveP) >= 0 || s.Cmp(curveN) >= 0 {
		return false
	}
	e := new(big.Int).Mod(new(big.Int).SetBytes(taggedHash("BIP0340/challenge", sig[:32], pub, msg)), curveN)
	R := padd(pmul(curveG, s), pmul(P, new(big.Int).Sub(curveN, e)))
	x, y, ok := affine(R)
	if !ok || y.Bit(0) == 1 {
		return false
	}
	return x.Cmp(r) == 0
}
