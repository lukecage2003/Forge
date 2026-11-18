package ai.forge.app.drive

import android.app.Activity
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Autorisation OAuth 2.0 via Google Identity Services (AuthorizationClient).
 * Prérequis côté Google Cloud : un identifiant OAuth « Android » (nom de package + empreinte SHA-1)
 * et l'API Drive activée — étape que seul l'utilisateur peut réaliser (voir docs/SETUP.md).
 */
class DriveAuth(private val activity: Activity) {
    sealed interface Outcome {
        data class Token(val accessToken: String) : Outcome
        /** L'utilisateur doit consentir : lancer cet intent puis appeler [handleConsentResult]. */
        data class NeedsConsent(val launch: android.app.PendingIntent) : Outcome
        data class Error(val message: String) : Outcome
    }

    suspend fun authorize(fullReadOnly: Boolean = false): Outcome = runCatching {
        val scope = if (fullReadOnly) DriveConnector.SCOPE_READONLY else DriveConnector.SCOPE_FILE
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(scope))).build()
        val result: AuthorizationResult = Identity.getAuthorizationClient(activity).authorize(req).await()
        when {
            result.hasResolution() -> Outcome.NeedsConsent(result.pendingIntent!!)
            result.accessToken != null -> Outcome.Token(result.accessToken!!)
            else -> Outcome.Error("Aucun jeton reçu.")
        }
    }.getOrElse { Outcome.Error(it.message ?: it.toString()) }

    fun handleConsentResult(data: Intent?): Outcome = runCatching {
        val r = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        r.accessToken?.let { Outcome.Token(it) } ?: Outcome.Error("Consentement refusé.")
    }.getOrElse { Outcome.Error(it.message ?: it.toString()) }
}
