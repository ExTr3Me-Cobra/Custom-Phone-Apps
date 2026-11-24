package com.labelrescue.core.barcode

/**
 * Reed-Solomon decoding over GF(256) with errors AND erasures (DataMatrix ECC 200: field
 * polynomial 0x12D, generator base 1). ZXing's decoder only corrects errors: with ecc check
 * codewords it fixes ecc/2 unknown errors. When the damaged codewords are known (a dead print-head
 * line runs through them, or their modules read as neither black nor white), it can fix up to
 * ecc of those: 2·errors + erasures ≤ ecc.
 */
object ReedSolomonErasures {
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) { exp[i] = x; log[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x12D }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    private fun div(a: Int, b: Int): Int { require(b != 0); return if (a == 0) 0 else exp[(log[a] - log[b] + 255) % 255] }
    private fun pow(i: Int) = exp[((i % 255) + 255) % 255]

    /** Polynomials as coefficient arrays, lowest degree first. */
    private fun polyMul(a: IntArray, b: IntArray): IntArray {
        val r = IntArray(a.size + b.size - 1)
        for (i in a.indices) for (j in b.indices) r[i + j] = r[i + j] xor mul(a[i], b[j])
        return r
    }

    private fun polyEval(p: IntArray, x: Int): Int {
        var r = 0
        for (i in p.indices.reversed()) r = mul(r, x) xor p[i]
        return r
    }

    /**
     * Corrects [cw] in place (first element = highest-degree coefficient, the last [ecc] are check
     * codewords), treating positions in [erasures] as unknown. Returns false if it can't: too many
     * errors (more than [maxErrors] besides the erasures), or the result doesn't check.
     */
    fun decode(cw: IntArray, ecc: Int, erasures: Collection<Int> = emptyList(), maxErrors: Int = Int.MAX_VALUE): Boolean {
        val n = cw.size
        val er = erasures.distinct().filter { it in 0 until n }
        if (er.size > ecc) return false
        // Syndromes S_j = r(α^j), j = 1..ecc.
        val s = IntArray(ecc) { j -> var v = 0; val a = pow(j + 1); for (c in cw) v = mul(v, a) xor c; v }
        if (s.all { it == 0 }) return true
        // Locator power of position i: X = α^(n-1-i).
        fun xOf(i: Int) = pow(n - 1 - i)
        // Erasure locator Γ(x) = Π (1 + X_k x).
        var gamma = intArrayOf(1)
        for (i in er) gamma = polyMul(gamma, intArrayOf(1, xOf(i)))
        // Forney syndromes: T(x) = Γ(x)·S(x) mod x^ecc; the error search uses T[e .. ecc-1].
        val sPoly = s
        val t = polyMul(gamma, sPoly).copyOf(ecc)
        val e = er.size
        // Berlekamp-Massey on the modified syndromes t[e..ecc-1].
        val syn = IntArray(ecc - e) { t[e + it] }
        var lambda = intArrayOf(1); var prev = intArrayOf(1)
        var l = 0; var m = 1; var b = 1
        for (k in syn.indices) {
            var d = syn[k]
            for (i in 1..l) if (i < lambda.size && k - i >= 0) d = d xor mul(lambda[i], syn[k - i])
            if (d == 0) { m++; continue }
            val coef = div(d, b)
            val shifted = IntArray(prev.size + m); for (i in prev.indices) shifted[i + m] = mul(prev[i], coef)
            val next = IntArray(maxOf(lambda.size, shifted.size)); for (i in lambda.indices) next[i] = lambda[i]; for (i in shifted.indices) next[i] = next[i] xor shifted[i]
            if (2 * l <= k) { prev = lambda; l = k + 1 - l; b = d; m = 1 } else m++
            lambda = next
        }
        if (2 * l + e > ecc || l > maxErrors) return false
        // Combined locator Ψ = Λ·Γ; its roots X^-1 give every error and erasure position.
        val psi = polyMul(lambda, gamma)
        val deg = psi.indexOfLast { it != 0 }
        val positions = ArrayList<Int>()
        for (i in 0 until n) if (polyEval(psi, pow(-(n - 1 - i))) == 0) positions += i
        if (positions.size != deg) return false
        // Forney: Ω = S·Ψ mod x^ecc; magnitude at X is Ω(X^-1) / Ψ'(X^-1) (generator base 1).
        val omega = polyMul(sPoly, psi).copyOf(ecc)
        val dpsi = IntArray(maxOf(1, psi.size - 1)) { i -> if ((i + 1) % 2 == 1) psi[i + 1] else 0 }
        val out = cw.copyOf()
        for (i in positions) {
            val xInv = pow(-(n - 1 - i))
            val den = polyEval(dpsi, xInv)
            if (den == 0) return false
            out[i] = out[i] xor div(polyEval(omega, xInv), den)
        }
        // Must check now.
        for (j in 1..ecc) { var v = 0; val a = pow(j); for (c in out) v = mul(v, a) xor c; if (v != 0) return false }
        System.arraycopy(out, 0, cw, 0, n)
        return true
    }
}
