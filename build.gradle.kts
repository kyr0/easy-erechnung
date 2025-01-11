plugins {
    id("java")
}

group = "de.aronhomberg"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.e-iceblue.com/nexus/content/groups/public/")
    }
}

dependencies {
    implementation("com.formdev:flatlaf:3.5.4")
    implementation("e-iceblue:spire.pdf.free:9.13.0")
    implementation("org.mustangproject:validator:2.15.0:shaded")
    implementation("org.verapdf:verapdf-pdfbox-validation:1.26.2")
    implementation("com.openai:openai-java:0.11.0")
    implementation("org.apache.pdfbox:pdfbox-tools:3.0.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.mustangproject:library:2.15.0")
    implementation("org.apache.pdfbox:pdfbox-debugger:3.0.2")
    implementation("org.apache.pdfbox:preflight:3.0.2")
    implementation("org.swinglabs:swingx:1.6.1")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}