import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm") version "2.4.20"
    id("com.google.protobuf") version "0.10.0"
    application
}

group = "facetindex"
version = "0.1.0"

repositories {
    mavenCentral()
}

val luceneVersion = "10.5.1"
val jmhVersion = "1.37"
val jacksonVersion = "2.22.3"
val grpcVersion = "1.84.0"
val grpcKotlinVersion = "1.5.0"
val protobufVersion = "4.36.2"

dependencies {
    implementation("org.apache.lucene:lucene-core:$luceneVersion")
    implementation("org.roaringbitmap:RoaringBitmap:1.6.23")
    implementation("org.apache.kafka:kafka-clients:4.3.1")
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("org.yaml:snakeyaml:2.5")
    // JMH benchmarks live in src/main/java so the `kernels` subcommand can run them in-process.
    implementation("org.openjdk.jmh:jmh-core:$jmhVersion")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.kotest:kotest-property:6.2.5")
    testImplementation("org.testcontainers:kafka:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
}

kotlin {
    jvmToolchain(21)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" }
        id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("grpc")
                id("grpckt")
            }
            it.builtins { id("kotlin") }
        }
    }
}

// JDK 21: java.lang.foreign is a preview API there (final in 22), used by the Java SIMD kernel.
val jvmFlags = listOf("--enable-preview", "--enable-native-access=ALL-UNNAMED", "--add-modules", "jdk.incubator.vector")

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("--enable-preview", "--add-modules", "jdk.incubator.vector"))
}

application {
    mainClass.set("facetindex.cli.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g") + jvmFlags
}

tasks.test {
    useJUnitPlatform {
        // The Kafka crash-replay test needs Docker (CI) or a running broker in FACETINDEX_KAFKA (mini PC).
        if (System.getenv("FACETINDEX_DOCKER") != "1" && System.getenv("FACETINDEX_KAFKA") == null) excludeTags("docker")
    }
    maxHeapSize = "2g"
    jvmArgs(jvmFlags)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
