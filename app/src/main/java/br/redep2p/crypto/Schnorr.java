package br.redep2p.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Assinaturas Schnorr sobre secp256k1 conforme o BIP340 (chave pública "x-only" de 32 bytes,
 * assinatura de 64 bytes). É o mesmo tipo de chave usado pela Kaspa, para que os recibos
 * assinados hoje possam ser verificados no futuro por um contrato (covenant) na blockchain.
 *
 * Implementação em Java puro com coordenadas jacobianas. Não é de tempo constante: serve
 * para a prova de conceito; numa versão de produção, trocar por libsecp256k1.
 */
public final class Schnorr {

    private Schnorr() {
    }

    static final BigInteger P = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    private static final BigInteger GX = new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);
    private static final BigInteger GY = new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16);
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger THREE = BigInteger.valueOf(3);
    private static final BigInteger SEVEN = BigInteger.valueOf(7);
    private static final BigInteger EIGHT = BigInteger.valueOf(8);
    private static final BigInteger SQRT_EXP = P.add(BigInteger.ONE).shiftRight(2);

    /** Ponto em coordenadas jacobianas (X, Y, Z); Z = 0 representa o infinito. */
    private static final class Jac {
        final BigInteger x, y, z;

        Jac(BigInteger x, BigInteger y, BigInteger z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean isInfinity() {
            return z.signum() == 0;
        }
    }

    private static final Jac INFINITY = new Jac(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
    private static final Jac G = new Jac(GX, GY, BigInteger.ONE);

    private static BigInteger mod(BigInteger a) {
        return a.mod(P);
    }

    private static Jac dbl(Jac p) {
        if (p.isInfinity() || p.y.signum() == 0) return INFINITY;
        BigInteger a = mod(p.x.multiply(p.x));
        BigInteger b = mod(p.y.multiply(p.y));
        BigInteger c = mod(b.multiply(b));
        BigInteger xb = p.x.add(b);
        BigInteger d = mod(TWO.multiply(xb.multiply(xb).subtract(a).subtract(c)));
        BigInteger e = mod(THREE.multiply(a));
        BigInteger f = mod(e.multiply(e));
        BigInteger x3 = mod(f.subtract(TWO.multiply(d)));
        BigInteger y3 = mod(e.multiply(d.subtract(x3)).subtract(EIGHT.multiply(c)));
        BigInteger z3 = mod(TWO.multiply(p.y).multiply(p.z));
        return new Jac(x3, y3, z3);
    }

    private static Jac add(Jac p, Jac q) {
        if (p.isInfinity()) return q;
        if (q.isInfinity()) return p;
        BigInteger z1z1 = mod(p.z.multiply(p.z));
        BigInteger z2z2 = mod(q.z.multiply(q.z));
        BigInteger u1 = mod(p.x.multiply(z2z2));
        BigInteger u2 = mod(q.x.multiply(z1z1));
        BigInteger s1 = mod(p.y.multiply(q.z).multiply(z2z2));
        BigInteger s2 = mod(q.y.multiply(p.z).multiply(z1z1));
        if (u1.equals(u2)) {
            return s1.equals(s2) ? dbl(p) : INFINITY;
        }
        BigInteger h = mod(u2.subtract(u1));
        BigInteger i = mod(TWO.multiply(h).pow(2));
        BigInteger j = mod(h.multiply(i));
        BigInteger r = mod(TWO.multiply(s2.subtract(s1)));
        BigInteger v = mod(u1.multiply(i));
        BigInteger x3 = mod(r.multiply(r).subtract(j).subtract(TWO.multiply(v)));
        BigInteger y3 = mod(r.multiply(v.subtract(x3)).subtract(TWO.multiply(s1).multiply(j)));
        BigInteger z1z2 = p.z.add(q.z);
        BigInteger z3 = mod(z1z2.multiply(z1z2).subtract(z1z1).subtract(z2z2).multiply(h));
        return new Jac(x3, y3, z3);
    }

    private static Jac mul(Jac p, BigInteger k) {
        Jac result = INFINITY;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            result = dbl(result);
            if (k.testBit(i)) result = add(result, p);
        }
        return result;
    }

    /** Converte para coordenadas afins: {x, y}, ou null no infinito. */
    private static BigInteger[] affine(Jac p) {
        if (p.isInfinity()) return null;
        BigInteger zInv = p.z.modInverse(P);
        BigInteger zInv2 = mod(zInv.multiply(zInv));
        return new BigInteger[]{mod(p.x.multiply(zInv2)), mod(p.y.multiply(zInv2).multiply(zInv))};
    }

    private static BigInteger[] liftX(BigInteger x) {
        if (x.compareTo(P) >= 0) return null;
        BigInteger c = mod(x.modPow(THREE, P).add(SEVEN));
        BigInteger y = c.modPow(SQRT_EXP, P);
        if (!mod(y.multiply(y)).equals(c)) return null;
        return new BigInteger[]{x, y.testBit(0) ? P.subtract(y) : y};
    }

    static byte[] bytes32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    private static BigInteger toInt(byte[] b) {
        return new BigInteger(1, b);
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) md.update(part);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] taggedHash(String tag, byte[]... parts) {
        byte[] t = sha256(tag.getBytes(StandardCharsets.UTF_8));
        byte[][] all = new byte[parts.length + 2][];
        all[0] = t;
        all[1] = t;
        System.arraycopy(parts, 0, all, 2, parts.length);
        return sha256(all);
    }

    private static BigInteger secretInt(byte[] secretKey) {
        if (secretKey.length != 32) throw new IllegalArgumentException("chave privada deve ter 32 bytes");
        BigInteger d = toInt(secretKey);
        if (d.signum() == 0 || d.compareTo(N) >= 0) throw new IllegalArgumentException("chave privada fora do intervalo");
        return d;
    }

    /** Chave pública x-only (32 bytes) correspondente à chave privada. */
    public static byte[] publicKey(byte[] secretKey) {
        BigInteger[] pub = affine(mul(G, secretInt(secretKey)));
        return bytes32(pub[0]);
    }

    /** Assina uma mensagem de 32 bytes (normalmente um hash) com aleatoriedade auxiliar de 32 bytes. */
    public static byte[] sign(byte[] msg, byte[] secretKey, byte[] auxRand) {
        if (msg.length != 32 || auxRand.length != 32) throw new IllegalArgumentException("mensagem e aux devem ter 32 bytes");
        BigInteger d0 = secretInt(secretKey);
        BigInteger[] pub = affine(mul(G, d0));
        BigInteger d = pub[1].testBit(0) ? N.subtract(d0) : d0;
        byte[] px = bytes32(pub[0]);
        byte[] auxHash = taggedHash("BIP0340/aux", auxRand);
        byte[] dBytes = bytes32(d);
        byte[] t = new byte[32];
        for (int i = 0; i < 32; i++) t[i] = (byte) (dBytes[i] ^ auxHash[i]);
        BigInteger k0 = toInt(taggedHash("BIP0340/nonce", t, px, msg)).mod(N);
        if (k0.signum() == 0) throw new IllegalStateException("nonce nulo");
        BigInteger[] r = affine(mul(G, k0));
        BigInteger k = r[1].testBit(0) ? N.subtract(k0) : k0;
        byte[] rx = bytes32(r[0]);
        BigInteger e = toInt(taggedHash("BIP0340/challenge", rx, px, msg)).mod(N);
        byte[] sig = new byte[64];
        System.arraycopy(rx, 0, sig, 0, 32);
        System.arraycopy(bytes32(k.add(e.multiply(d)).mod(N)), 0, sig, 32, 32);
        if (!verify(msg, px, sig)) throw new IllegalStateException("assinatura gerada não confere");
        return sig;
    }

    /** Verifica uma assinatura BIP340. */
    public static boolean verify(byte[] msg, byte[] publicKey, byte[] sig) {
        if (msg.length != 32 || publicKey.length != 32 || sig.length != 64) return false;
        BigInteger[] pub = liftX(toInt(publicKey));
        if (pub == null) return false;
        BigInteger r = toInt(Arrays.copyOfRange(sig, 0, 32));
        BigInteger s = toInt(Arrays.copyOfRange(sig, 32, 64));
        if (r.compareTo(P) >= 0 || s.compareTo(N) >= 0) return false;
        BigInteger e = toInt(taggedHash("BIP0340/challenge", Arrays.copyOfRange(sig, 0, 32), publicKey, msg)).mod(N);
        Jac pJac = new Jac(pub[0], pub[1], BigInteger.ONE);
        Jac rJac = add(mul(G, s), mul(pJac, N.subtract(e)));
        BigInteger[] rr = affine(rJac);
        if (rr == null || rr[1].testBit(0)) return false;
        return rr[0].equals(r);
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
