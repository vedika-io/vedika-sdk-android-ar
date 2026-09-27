package io.vedika.sdk.ar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

/**
 * ARCore availability, install, and camera-permission plumbing —
 * [ArCoreApk.checkAvailability]/[ArCoreApk.requestInstall] and the plain
 * framework permission check, wrapped as clean, testable-by-inspection
 * outcomes for [VastuArCoreCaptureView] to switch on.
 *
 * Deliberately does not request the CAMERA runtime permission itself (same
 * house style as `VastuArView`): a library module cannot own the host app's
 * permission-request flow, and different hosts want different UX (an
 * `ActivityResultContracts.RequestPermission` launcher, a rationale dialog,
 * etc). Call [hasCameraPermission] to check, let the host app request it,
 * then retry.
 */
object ArCoreAvailability {

    /** Coarse status this module presents; see [availability] for how a raw [ArCoreApk.Availability] maps here. */
    enum class Status {
        /** ARCore is installed and current. A [com.google.ar.core.Session] can be created now. */
        SUPPORTED_INSTALLED,
        /** ARCore support is likely but the Play Store app is missing or needs an update. Call [requestInstall]. */
        SUPPORTED_NEEDS_INSTALL,
        /** This device cannot run ARCore at all. Do not offer the AR capture flow. */
        UNSUPPORTED,
        /** Availability could not be determined yet (e.g. still checking asynchronously); the caller should re-check shortly. */
        UNKNOWN,
    }

    fun status(context: Context): Status = when (ArCoreApk.getInstance().checkAvailability(context)) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> Status.SUPPORTED_INSTALLED
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
        -> Status.SUPPORTED_NEEDS_INSTALL
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
        ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
        -> Status.UNSUPPORTED
        ArCoreApk.Availability.UNKNOWN_ERROR,
        ArCoreApk.Availability.UNKNOWN_CHECKING,
        -> Status.UNKNOWN
        else -> Status.UNKNOWN
    }

    /** Outcome of [requestInstall]. */
    sealed interface InstallOutcome {
        /** ARCore is ready; create the [com.google.ar.core.Session] now. */
        data object Installed : InstallOutcome
        /** The system install/update flow was launched; the calling `Activity` will be recreated — retry there. */
        data object InstallRequested : InstallOutcome
        /** The user declined the install prompt. */
        data object UserDeclined : InstallOutcome
        /** The device genuinely cannot run ARCore, or the installed ARCore is unusable and cannot be updated further. */
        data class Unsupported(val reason: String) : InstallOutcome
    }

    /**
     * Requests the ARCore install/update flow if needed. Call from
     * `Activity.onResume()`, matching [ArCoreApk.requestInstall]'s own
     * contract: `userRequestedInstall` should be `true` the first time (the
     * user just tapped something that starts AR) and `false` on the
     * automatic retry after the Activity is recreated post-install.
     */
    fun requestInstall(activity: Activity, userRequestedInstall: Boolean): InstallOutcome = try {
        when (ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)) {
            ArCoreApk.InstallStatus.INSTALLED -> InstallOutcome.Installed
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> InstallOutcome.InstallRequested
            else -> InstallOutcome.Unsupported("ArCoreApk.requestInstall returned an unrecognized status")
        }
    } catch (e: UnavailableUserDeclinedInstallationException) {
        InstallOutcome.UserDeclined
    } catch (e: UnavailableDeviceNotCompatibleException) {
        InstallOutcome.Unsupported("This device cannot run ARCore: ${e.message}")
    } catch (e: UnavailableApkTooOldException) {
        InstallOutcome.Unsupported("The installed ARCore build is too old and could not be updated: ${e.message}")
    } catch (e: UnavailableSdkTooOldException) {
        InstallOutcome.Unsupported("This app's ARCore SDK is too old for the installed ARCore build: ${e.message}")
    }

    fun hasCameraPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
