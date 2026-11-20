plugins {
    alias(libs.plugins.agp.app)
}

layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("patch/build/permission-probe/gradle"))

extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "example.permissionprobe"

    buildTypes.configureEach {
        signingConfig = signingConfigs.getByName("debug")
    }
}
