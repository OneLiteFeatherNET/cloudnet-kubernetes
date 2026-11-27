dependencies {
    compileOnlyApi(platform(libs.cloudnet.bom))
    compileOnlyApi(libs.cloudnet.driver.api)
}

publishing {
    publications.create<MavenPublication>("maven") { from(components["java"]) }
}
