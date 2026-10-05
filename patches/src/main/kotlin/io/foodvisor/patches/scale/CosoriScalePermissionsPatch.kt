package io.foodvisor.patches.scale

import app.revanced.patcher.patch.resourcePatch
import org.w3c.dom.Element

private val REQUIRED_PERMISSIONS = listOf(
    // Android 12+ (targetSdk >= 31) runtime permissions.
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
    // Legacy permissions for older Android versions.
    "android.permission.BLUETOOTH",
    "android.permission.BLUETOOTH_ADMIN",
    // Required for BLE scanning below Android 12.
    "android.permission.ACCESS_FINE_LOCATION",
)

/**
 * Adds the Bluetooth LE permissions (and the optional BLE feature) to
 * Foodvisor's manifest. Foodvisor 7.5.6 declares no Bluetooth permissions at
 * all, so the scale support cannot work without this resource patch.
 */
@Suppress("unused")
val cosoriScalePermissionsPatch = resourcePatch(
    name = "Cosori CNS-R101S scale permissions",
    description = "Adds BLUETOOTH_SCAN/CONNECT (and legacy BLE permissions) to the manifest.",
) {
    compatibleWith("io.foodvisor.foodvisor"("7.5.6"))

    execute {
        val manifest = document("AndroidManifest.xml")
        val root = manifest.documentElement

        val existingPermissions = mutableSetOf<String>()
        root.getElementsByTagName("uses-permission").let { nodes ->
            for (index in 0 until nodes.length) {
                (nodes.item(index) as? Element)
                    ?.getAttribute("android:name")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(existingPermissions::add)
            }
        }

        REQUIRED_PERMISSIONS
            .filterNot(existingPermissions::contains)
            .forEach { permission ->
                manifest.createElement("uses-permission").apply {
                    setAttribute("android:name", permission)
                    // Without neverForLocation, Android 12+ only delivers scan
                    // results if ACCESS_FINE_LOCATION is granted. The scale's
                    // weight is not used to derive location, so opt out.
                    if (permission == "android.permission.BLUETOOTH_SCAN") {
                        setAttribute("android:usesPermissionFlags", "neverForLocation")
                    }
                    root.appendChild(this)
                }
            }

        val hasBleFeature = root.getElementsByTagName("uses-feature").let { nodes ->
            (0 until nodes.length).any { index ->
                (nodes.item(index) as? Element)
                    ?.getAttribute("android:name") == "android.hardware.bluetooth_le"
            }
        }
        if (!hasBleFeature) {
            manifest.createElement("uses-feature").apply {
                setAttribute("android:name", "android.hardware.bluetooth_le")
                setAttribute("android:required", "false")
                root.appendChild(this)
            }
        }

        // Persist the modified manifest; writing happens on close().
        manifest.close()
    }
}
