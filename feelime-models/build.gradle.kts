plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("feelime_models")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}

// The asset-pack plugin only accepts the conventional src/main/assets tree.
// That directory is a relative symlink to the one checked model tree in app,
// so the PAD bundle cannot silently drift from the APK's verified bytes.
