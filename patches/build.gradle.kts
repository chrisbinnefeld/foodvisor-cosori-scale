group = "io.foodvisor"

patches {
    about {
        name = "Foodvisor Cosori Scale Patches"
        description = "ReVanced patches for Foodvisor (io.foodvisor.foodvisor) adding support for the Cosori CNS-R101S kitchen scale."
        source = "https://github.com/chrisbinnefeld/foodvisor-cosori-scale"
        author = "chrisbinnefeld"
        contact = "chrisbinnefeld@users.noreply.github.com"
        website = "https://github.com/chrisbinnefeld/foodvisor-cosori-scale"
        license = "GNU General Public License v3.0"
    }
}

// ReVanced Patcher v21 exposes fingerprints via context receivers.
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}
