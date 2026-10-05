group = "io.foodvisor"

patches {
    about {
        name = "Foodvisor ReVanced Patches"
        description = "ReVanced patches for Foodvisor (io.foodvisor.foodvisor), including experimental Cosori CNS-R101S kitchen scale support."
        source = "git@github.com:cbinnefeld/foodvisor-revanced.git"
        author = "cbinnefeld"
        contact = "cbinnefeld@localhost"
        website = "https://github.com/cbinnefeld/foodvisor-revanced"
        license = "GNU General Public License v3.0"
    }
}

// ReVanced Patcher v21 exposes fingerprints via context receivers.
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}
