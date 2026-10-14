package io.github.victorious93.edlcommander.companion

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/** Decides whether a Binder caller may talk to the companion. */
fun interface CallerVerifier {
    fun isAllowed(callingUid: Int): Boolean
}

object CallerPolicy {
    /**
     * Pure decision: the caller must share our signing certificate, and, when an allowlist is
     * configured, must also run under one of the allowlisted package names.
     */
    fun decide(sameSignature: Boolean, callerPackages: List<String>, allowlist: Set<String>): Boolean {
        if (!sameSignature) return false
        return allowlist.isEmpty() || callerPackages.any { it in allowlist }
    }
}

/**
 * Defence in depth on top of the signature-level bind permission declared in the manifest:
 * re-checks the signing certificate of the calling UID on every transaction.
 */
class SignatureCallerVerifier(
    context: Context,
    private val allowlist: Set<String> = HUB_PACKAGE_ALLOWLIST,
) : CallerVerifier {
    private val pm: PackageManager = context.applicationContext.packageManager

    override fun isAllowed(callingUid: Int): Boolean {
        if (callingUid == Process.myUid()) return true
        val same = pm.checkSignatures(callingUid, Process.myUid()) == PackageManager.SIGNATURE_MATCH
        val packages = pm.getPackagesForUid(callingUid)?.toList().orEmpty()
        return CallerPolicy.decide(same, packages, allowlist)
    }

    companion object {
        /**
         * TODO(droidcommand): the Droidcommand-AI hub package name is not known to this repo.
         * Until it is filled in, any app signed with the fleet key is accepted. Prefer pinning
         * the exact hub package once known.
         */
        val HUB_PACKAGE_ALLOWLIST: Set<String> = emptySet()
    }
}
