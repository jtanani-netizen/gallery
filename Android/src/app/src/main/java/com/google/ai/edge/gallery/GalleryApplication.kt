/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery

import android.app.Application
import android.os.Process
import android.util.Log
import com.google.ai.edge.gallery.biblio.BiblioRouteur
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.notifications.NotificationScheduleManager
import com.google.ai.edge.gallery.services.tts.TtsMemoryTrimCallbacks
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository
  @Inject lateinit var notificationScheduleManager: NotificationScheduleManager
  @Inject lateinit var ttsMemoryTrimCallbacks: TtsMemoryTrimCallbacks

  override fun onCreate() {
    // androidx.pdf parses PDFs in an isolated service process, which also instantiates this
    // Application. Isolated processes have no access to storage or most system services (e.g.
    // Firebase initialization crashes there), so skip all app initialization in them.
    if (Process.isIsolated()) {
      return
    }
    super.onCreate()
    registerComponentCallbacks(ttsMemoryTrimCallbacks)
    // Initialize the notification schedule manager to load the scheduled notifications from the
    // disk.
    notificationScheduleManager.initialize()

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    // PHASE C (chantier Anir) : cet APK ne contient AUCUNE configuration
    // Firebase (pas de google-services.json, donc aucune ressource
    // google_app_id) : FirebaseApp.initializeApp() y lève
    // IllegalStateException et tuait le processus avant toute fenêtre
    // (plantage au démarrage constaté sur l'appareil le 2026-11-16).
    // L'application est 100 % hors-ligne : Firebase est inerte par
    // conception, son échec d'initialisation ne doit jamais être fatal.
    // Si une configuration Firebase existait, le comportement serait
    // strictement inchangé.
    try {
      FirebaseApp.initializeApp(this)
      firebaseAnalytics?.setAnalyticsCollectionEnabled(dataStoreRepository.readFirebaseAnalytics())
    } catch (e: Exception) {
      Log.w(TAG, "Firebase indisponible (aucune configuration) : initialisation ignorée", e)
    }

    // PHASE B (chantier Anir) : précharger la bibliothèque médicale locale
    // (copie de l'asset + chargement de l'index inversé) sur un fil
    // d'arrière-plan, pour que la première question n'attende pas.
    Thread { BiblioRouteur.precharger(applicationContext) }.start()
  }

  companion object {
    private const val TAG = "GalleryApplication"
  }
}
