package fr.sallio.pronote

/** Every failure the app knows how to explain to a student. */
sealed class PronoteException(message: String) : Exception(message) {
    /** Network down, timeout, DNS: worth retrying later. */
    class Unreachable(cause: Throwable) : PronoteException("Pronote injoignable") {
        init { initCause(cause) }
    }

    /** "Le site est momentanément indisponible", the overload this whole app exists for. */
    class Busy : PronoteException("Pronote est surchargé")

    class Unavailable : PronoteException("Page Pronote indisponible")

    /** PRONOTE bans an IP for a while after too many attempts. */
    class SuspendedIp : PronoteException("Adresse IP suspendue par Pronote")

    class RateLimited : PronoteException("Trop de requêtes")

    class SessionExpired : PronoteException("Session expirée")

    /** The stored token was refused: the student has to log in again. */
    class BadCredentials : PronoteException("Identifiants refusés")

    class AccessDenied : PronoteException("Accès refusé")

    class AccountDisabled : PronoteException("Compte désactivé")

    class Server(message: String) : PronoteException(message)

    /** PRONOTE wants a double authentication step before giving the token. */
    class SecurityRequired(val challenge: SecurityChallenge) :
        PronoteException("Double authentification demandée")

    /** Pronote answered something we cannot read. */
    class Protocol(message: String) : PronoteException(message)

    /** True when the stored login is dead and only a new login will fix it. */
    val needsNewLogin: Boolean
        get() = this is BadCredentials || this is AccessDenied || this is AccountDisabled ||
            this is SecurityRequired
}
