/*
 * Assistant Médical — pour Anir · Phase B (chantier propre, hors code Google).
 *
 * Branchement de la bibliothèque médicale locale dans le flux de conversation
 * du squelette Gallery : à chaque question, le routeur déterministe
 * (package `routeur`, portage fidèle 33/33 de chercherB) cherche dans
 * l'index inversé d'origine, et les meilleurs passages sont injectés AVANT
 * l'appel au modèle, chacun avec le titre de son document source.
 *
 * Données : assets/biblio.db (= biblio-v2.db : 70 documents, 6 856 passages,
 * table index_inverse 35 362 termes / 496 060 paires, lue en ORDER BY rowid).
 * La base est copiée une fois dans le stockage privé de l'app puis ouverte
 * en LECTURE SEULE. Aucun accès réseau : tout est local, 100 % hors-ligne.
 */

package com.google.ai.edge.gallery.biblio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import routeur.RouteurBiblio
import routeur.SEUIL_BIBLIO

object BiblioRouteur {

  private const val TAG = "BiblioRouteur"
  private const val NOM_ASSET = "biblio.db"
  private const val NOM_FICHIER = "biblio.db"
  private const val NOM_MARQUEUR = "biblio.db.taille"

  /** Nombre de passages injectés au maximum (comme la page v6.4 : top 5). */
  private const val NB_PASSAGES = 5

  /** Garde-fou contexte : longueur maximale d'un passage injecté. */
  private const val MAX_CARACTERES_PASSAGE = 1500

  /**
   * ⚠️ POINT DE DÉCISION PRODUIT A/B (PLAN.md §6) — NON TRANCHÉ.
   * La décision appartient à l'utilisateur ; ce drapeau est l'UNIQUE point
   * de code à basculer, tout le câblage autour reste identique :
   *
   * - `true` = option B (« réponse libre contrôlée », comportement de la
   *   v6 en service) : si aucun extrait n'atteint le seuil, la question part
   *   au modèle telle quelle et le modèle répond de sa propre mémoire.
   * - `false` = option A (« aucune réponse médicale libre », proposition) :
   *   si aucun extrait n'atteint le seuil, une consigne restrictive est
   *   ajoutée au prompt : le modèle doit dire qu'il n'a pas de source
   *   vérifiée et orienter vers un professionnel, sans répondre de mémoire.
   *
   * Valeur actuelle : `true` (on ne change pas le comportement en service
   * sans décision explicite).
   */
  const val REPONSE_LIBRE_AUTORISEE: Boolean = true

  /** Consigne ajoutée quand l'option A est active et qu'aucune source ne couvre la question. */
  private const val CONSIGNE_SANS_SOURCE =
    "\n\n[Consigne : aucun extrait de la bibliothèque médicale locale ne couvre cette " +
      "question. N'y réponds pas de ta propre mémoire : explique que tu n'as pas de " +
      "source vérifiée pour cette question et invite à consulter un professionnel de santé.]"

  @Volatile private var moteur: RouteurBiblio? = null
  @Volatile private var base: SQLiteDatabase? = null
  private val verrou = Any()

  /** Préchargement au démarrage de l'app (fil d'arrière-plan) : copie + index en mémoire. */
  fun precharger(context: Context) {
    try {
      assurerMoteur(context.applicationContext)
    } catch (e: Exception) {
      Log.e(TAG, "Préchargement de la bibliothèque échoué (nouvel essai à la 1re question)", e)
    }
  }

  /**
   * Point d'entrée du flux de conversation : renvoie la question enrichie des
   * extraits sourcés si le routeur dépasse le seuil, sinon la question
   * inchangée (option B) ou assortie de la consigne restrictive (option A).
   * En cas d'erreur quelconque, renvoie la question inchangée : la
   * bibliothèque ne doit jamais empêcher la conversation.
   */
  fun enrichir(context: Context, question: String): String {
    if (question.isBlank()) return question
    return try {
      val m = assurerMoteur(context.applicationContext)
      val resultat = m.chercherB(question, NB_PASSAGES)
      if (resultat == null || resultat.score < SEUIL_BIBLIO) {
        Log.d(
          TAG,
          "Routeur : score=${resultat?.score ?: 0.0} < seuil $SEUIL_BIBLIO — pas d'injection" +
            (if (REPONSE_LIBRE_AUTORISEE) " (réponse libre, option B)." else " (consigne A)."),
        )
        if (REPONSE_LIBRE_AUTORISEE) question else question + CONSIGNE_SANS_SOURCE
      } else {
        val bloc = construireBloc(resultat.morceaux)
        if (bloc == null) {
          question
        } else {
          Log.d(TAG, "Routeur : score=${resultat.score} — ${resultat.morceaux.size} extraits injectés.")
          bloc + "\n\nQuestion de l'utilisateur : " + question
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Enrichissement bibliothèque échoué, question inchangée", e)
      question
    }
  }

  /** Construit le bloc de contexte sourcé pour les passages retenus (ordre du routeur). */
  private fun construireBloc(morceaux: List<Int>): String? {
    val db = base ?: return null
    val sb = StringBuilder()
    sb.append(
      "Extraits de la bibliothèque médicale locale (sources vérifiées). " +
        "Utilise-les en priorité pour répondre, et cite la source de chaque information " +
        "(le titre du document, entre crochets, comme indiqué avant chaque extrait) :\n"
    )
    var nb = 0
    synchronized(verrou) {
      for (cid in morceaux) {
        db.rawQuery(
          "SELECT c.texte, d.titre FROM chunks c JOIN documents d ON d.id = c.doc_id WHERE c.id = ?",
          arrayOf(cid.toString()),
        ).use { c ->
          if (c.moveToFirst()) {
            var texte = c.getString(0)
            if (texte.length > MAX_CARACTERES_PASSAGE) {
              texte = texte.substring(0, MAX_CARACTERES_PASSAGE) + "…"
            }
            sb.append("\n[Source : ").append(c.getString(1)).append("]\n")
            sb.append(texte).append("\n")
            nb++
          }
        }
      }
    }
    return if (nb == 0) null else sb.toString()
  }

  /** Charge (une seule fois) la base et les trois données du routeur. */
  private fun assurerMoteur(context: Context): RouteurBiblio {
    moteur?.let { return it }
    synchronized(verrou) {
      moteur?.let { return it }
      val db = ouvrirBase(context)
      base = db

      val titres = ArrayList<String>()
      db.rawQuery("SELECT titre FROM documents ORDER BY id", null).use { c ->
        while (c.moveToNext()) titres.add(c.getString(0))
      }

      val docParChunk: IntArray
      db.rawQuery("SELECT doc_id FROM chunks ORDER BY id", null).use { c ->
        docParChunk = IntArray(c.count)
        var i = 0
        while (c.moveToNext()) docParChunk[i++] = c.getInt(0)
      }

      // ORDER BY rowid : on retrouve les paires dans l'ordre exact de
      // l'index d'origine (condition de fidélité du scoring, cf. phase B prep §3).
      val index = HashMap<String, MutableList<Pair<Int, Int>>>()
      db.rawQuery(
        "SELECT terme, chunk_id, occurrences FROM index_inverse ORDER BY rowid",
        null,
      ).use { c ->
        while (c.moveToNext()) {
          index.getOrPut(c.getString(0)) { ArrayList() }.add(c.getInt(1) to c.getInt(2))
        }
      }

      val m = RouteurBiblio(index, docParChunk, titres.toTypedArray())
      moteur = m
      Log.i(TAG, "Bibliothèque chargée : ${titres.size} documents, ${index.size} termes.")
      return m
    }
  }

  /** Copie l'asset dans le stockage privé (une fois) et ouvre la base en lecture seule. */
  private fun ouvrirBase(context: Context): SQLiteDatabase {
    base?.let { if (it.isOpen) return it }
    val cible = File(context.filesDir, NOM_FICHIER)
    val marqueur = File(context.filesDir, NOM_MARQUEUR)
    val copieOk =
      cible.exists() &&
        marqueur.exists() &&
        (marqueur.readText().trim().toLongOrNull() ?: -1L) == cible.length()
    if (!copieOk) {
      val tmp = File(context.filesDir, "$NOM_FICHIER.tmp")
      context.assets.open(NOM_ASSET).use { entree ->
        FileOutputStream(tmp).use { sortie -> entree.copyTo(sortie) }
      }
      if (cible.exists()) cible.delete()
      check(tmp.renameTo(cible)) { "Copie de la bibliothèque impossible" }
      marqueur.writeText(cible.length().toString())
      Log.i(TAG, "Bibliothèque copiée depuis les assets : ${cible.length()} octets.")
    }
    return SQLiteDatabase.openDatabase(cible.path, null, SQLiteDatabase.OPEN_READONLY)
  }
}
