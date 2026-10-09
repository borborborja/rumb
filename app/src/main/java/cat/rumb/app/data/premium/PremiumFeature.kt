package cat.rumb.app.data.premium

/** Product access rules, independent of Android and receipt storage. */
enum class PremiumFeature(val requiresPremium: Boolean) {
    RECORDING(false),
    ONLINE_MAPS(false),
    SAVED_ACTIVITIES(false),
    EXPORT(false),
    OFFLINE_MAPS(true),
    ROUTE_FOLLOWING(true),
    LAYOUT_EDITING(true),
    BLE_SENSORS(true),
    CLOUD_SYNC(true),
    ADVANCED_ANALYSIS(true),
    COMPETITIONS(true),
    WEIGHT(true),
}

enum class PremiumWorkAccess { ALLOWED, DENIED, UNKNOWN }

object PremiumFeaturePolicy {
    fun allows(feature: PremiumFeature, hasPremium: Boolean): Boolean =
        !feature.requiresPremium || hasPremium

    /** A cold start or store outage is unresolved, including when access was previously granted. */
    fun workAccess(
        hasPremium: Boolean,
        ownershipVerified: Boolean,
        accessCodeActive: Boolean = false,
    ): PremiumWorkAccess = when {
        accessCodeActive -> PremiumWorkAccess.ALLOWED
        !ownershipVerified -> PremiumWorkAccess.UNKNOWN
        hasPremium -> PremiumWorkAccess.ALLOWED
        else -> PremiumWorkAccess.DENIED
    }

    /** Keep tools already in use until an ongoing recording finishes; never unlock new actions. */
    fun allowsContinuation(
        feature: PremiumFeature,
        hasPremium: Boolean,
        recordingInProgress: Boolean,
        premiumAtRecordingStart: Boolean,
    ): Boolean = allows(feature, hasPremium) ||
        (recordingInProgress && premiumAtRecordingStart && feature in recordingFeatures)

    private val recordingFeatures = setOf(
        PremiumFeature.OFFLINE_MAPS,
        PremiumFeature.ROUTE_FOLLOWING,
        PremiumFeature.BLE_SENSORS,
        PremiumFeature.COMPETITIONS,
    )
}
