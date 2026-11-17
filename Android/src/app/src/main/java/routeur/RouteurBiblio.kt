package routeur

import java.text.Normalizer

/**
 * Portage Kotlin/JVM du routeur bibliothèque de la page v6.4
 * (termux-ia-locale/interface/index-v64.html : normB, tokensB, racineB,
 * chercherB). Fidélité exacte par construction : mêmes données (index
 * inversé d'origine, cf. biblio-v2.db / data/index.tsv), même formule,
 * même ordre d'accumulation des scores (voir ordreForIn).
 *
 * Les tables MOTS_VIDE_B et SYN_B vivent dans DonneesRouteur.kt,
 * générées mécaniquement depuis la page par generer-donnees.py
 * (extraction + exécution du vrai code JS sous Node : aucune
 * retranscription à la main).
 */

/** Seuil de routage calibré (calibrage-seuil.py) : score >= 7 -> la bibliothèque répond. */
const val SEUIL_BIBLIO: Double = 7.0

/** Priorités par document (PRIO_B de la page) : première clé contenue dans le titre gagne. */
private val PRIO_B: List<Pair<String, Double>> = listOf(
    "PNDS HAS 2024" to 1.35,
    "Synthèse MG HAS 2024" to 1.2,
    "NHLBI" to 1.15,
    "Formulaire OMS enfants" to 1.12,
    "Guide ALD" to 1.1,
    "PNDS HAS 2010" to 1.1,
    "Fiche mémo" to 1.05,
    "Mémento" to 1.0,
    "Plante médicinale OMS" to 0.8,
)

/** racineB : retire un s/x final si longueur > 4. */
fun racineB(t: String): String =
    if (t.length > 4 && (t.endsWith("s") || t.endsWith("x"))) t.dropLast(1) else t

/** normB : minuscules, décomposition NFD, retrait des marques U+0300..U+036F (comme le JS). */
fun normB(s: String): String {
    val nfd = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
    val sb = StringBuilder(nfd.length)
    for (ch in nfd) {
        if (ch.code !in 0x0300..0x036F) sb.append(ch)
    }
    return sb.toString()
}

private val SEPARATEURS_B = Regex("[^a-z0-9\u0600-\u06FF]+")

/** tokensB : découpage, longueur > 2, mots vides retirés, racine appliquée. */
fun tokensB(s: String): List<String> =
    normB(s).split(SEPARATEURS_B)
        .filter { it.length > 2 && it !in MOTS_VIDE_B }
        .map { racineB(it) }

/**
 * Reproduit l'ordre d'itération d'un `for...in` JavaScript sur un objet :
 * d'abord les clés « indice de tableau » (chaîne canonique d'un entier
 * 0..2^32-2) par ordre numérique croissant, puis les autres clés dans
 * l'ordre d'insertion. Cet ordre fixe la suite des additions en virgule
 * flottante de chaque score : il conditionne l'égalité au bit près avec
 * le moteur d'origine.
 */
private fun estIndiceTableau(k: String): Boolean {
    if (k.isEmpty() || k.length > 10) return false
    for (c in k) if (c !in '0'..'9') return false
    val v = k.toLong()
    return v < 4294967295L && v.toString() == k
}

private fun ordreForIn(poids: LinkedHashMap<String, Double>): List<Map.Entry<String, Double>> {
    val indices = ArrayList<Map.Entry<String, Double>>()
    val autres = ArrayList<Map.Entry<String, Double>>()
    for (e in poids.entries) {
        if (estIndiceTableau(e.key)) indices.add(e) else autres.add(e)
    }
    indices.sortBy { it.key.toLong() }
    return indices + autres
}

/**
 * Le routeur. Données injectées (l'application Android les lira dans
 * biblio-v2.db ; les tests JVM les chargent depuis les TSV de data/) :
 * - index : terme -> liste de (n° de passage, occurrences), dans l'ordre
 *   d'origine ; occurrences = 0 marque la présence dans le titre du document ;
 * - docParChunk : n° de document de chaque passage ;
 * - titres : titre de chaque document (pour les priorités PRIO_B).
 */
class RouteurBiblio(
    private val index: Map<String, List<Pair<Int, Int>>>,
    private val docParChunk: IntArray,
    private val titres: Array<String>,
) {
    /** Équivalent du retour de chercherB : score final et passages retenus, dans l'ordre. */
    data class Resultat(val score: Double, val morceaux: List<Int>)

    fun chercherB(question: String, n: Int = 5): Resultat? {
        val qt = tokensB(question)
        val poids = LinkedHashMap<String, Double>()
        for (t in qt) poids[t] = 1.0
        for (t in qt) {
            val freqT = index[t]?.size ?: 0
            for (s in SYN_B[t].orEmpty()) {
                if (s !in poids) {
                    poids[s] = when {
                        freqT == 0 -> 1.15
                        freqT <= 15 -> 1.0
                        else -> 0.6
                    }
                }
            }
        }
        val scores = HashMap<Int, Double>()
        val hits = HashMap<Int, MutableSet<String>>()
        for ((terme, poids0) in ordreForIn(poids)) {
            val liste = index[terme] ?: continue
            var w = poids0
            val freq = liste.size
            if (freq > 600) w *= 0.3 else if (freq > 250) w *= 0.55
            for ((cid, c) in liste) {
                scores[cid] = (scores[cid] ?: 0.0) +
                    if (c == 0) 3.0 * w else minOf(c, 5) * w
                hits.getOrPut(cid) { mutableSetOf() }.add(terme)
            }
        }
        for (cid in scores.keys.toList()) {
            val titre = titres[docParChunk[cid]]
            var p = 0.95
            for ((cle, v) in PRIO_B) {
                if (titre.contains(cle)) {
                    p = v
                    break
                }
            }
            scores[cid] = scores.getValue(cid) * p
        }
        if (scores.isEmpty()) return null
        // Object.entries(scores) énumère les clés entières par ordre croissant,
        // puis le tri JS (stable) départage les égalités dans cet ordre :
        // score décroissant, puis n° de passage croissant.
        val top = scores.entries
            .sortedWith(
                compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key }
            )
            .take(n)
        val hitTop = hits[top[0].key] ?: emptySet()
        val conc = qt.isNotEmpty() && qt.all { t ->
            t in hitTop || SYN_B[t].orEmpty().any { it in hitTop }
        }
        return Resultat(
            top[0].value * if (conc) 1.15 else 1.0,
            top.map { it.key },
        )
    }
}
