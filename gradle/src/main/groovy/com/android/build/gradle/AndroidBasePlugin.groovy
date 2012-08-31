/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.build.gradle

import com.android.build.gradle.internal.AndroidSourceSet
import com.android.build.gradle.internal.ApplicationVariant
import com.android.build.gradle.internal.ProductFlavorData
import com.android.build.gradle.internal.ProductionAppVariant
import com.android.build.gradle.internal.TestAppVariant
import com.android.builder.AndroidBuilder
import com.android.builder.DefaultSdkParser
import com.android.builder.ProductFlavor
import com.android.builder.SdkParser
import com.android.builder.VariantConfiguration
import com.android.utils.ILogger
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.logging.LogLevel
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.Compile

/**
 * Base class for all Android plugins
 */
abstract class AndroidBasePlugin {

    public final static String INSTALL_GROUP = "Install"

    private final Map<Object, AndroidBuilder> builders = [:]

    protected Project project
    protected File sdkDir
    private DefaultSdkParser androidSdkParser
    private AndroidLogger androidLogger

    private ProductFlavorData defaultConfigData
    protected SourceSet mainSourceSet
    protected SourceSet testSourceSet

    protected Task installAllForTests
    protected Task runAllTests
    protected Task uninstallAll
    protected Task assembleTest

    abstract String getTarget()

    protected void apply(Project project) {
        this.project = project
        project.apply plugin: JavaBasePlugin

        mainSourceSet = project.sourceSets.add("main")
        testSourceSet = project.sourceSets.add("test")

        project.tasks.assemble.description =
            "Assembles all variants of all applications and secondary packages."

        findSdk(project)

        installAllForTests = project.tasks.add("installAllForTests")
        installAllForTests.group = "Verification"
        installAllForTests.description = "Installs all applications needed to run tests."

        runAllTests = project.tasks.add("runAllTests")
        runAllTests.group = "Verification"
        runAllTests.description = "Runs all tests."

        uninstallAll = project.tasks.add("uninstallAll")
        uninstallAll.description = "Uninstall all applications."
        uninstallAll.group = "Install"

        project.tasks.check.dependsOn installAllForTests, runAllTests
    }

    protected setDefaultConfig(ProductFlavor defaultConfig) {
        defaultConfigData = new ProductFlavorData(defaultConfig, mainSourceSet,
                testSourceSet, project)
    }

    ProductFlavorData getDefaultConfigData() {
        return defaultConfigData
    }

    SdkParser getSdkParser() {
        if (androidSdkParser == null) {
            androidSdkParser = new DefaultSdkParser(sdkDir.absolutePath)
        }

        return androidSdkParser;
    }

    ILogger getLogger() {
        if (androidLogger == null) {
            androidLogger = new AndroidLogger(project.logger)
        }

        return androidLogger
    }

    boolean isVerbose() {
        return project.logger.isEnabled(LogLevel.DEBUG)
    }

    AndroidBuilder getAndroidBuilder(Object key) {
        return builders.get(key)
    }

    void setAndroidBuilder(Object key, AndroidBuilder androidBuilder) {
        builders.put(key, androidBuilder)
    }

    private void findSdk(Project project) {
        def localProperties = project.file("local.properties")
        if (localProperties.exists()) {
            Properties properties = new Properties()
            localProperties.withInputStream { instr ->
                properties.load(instr)
            }
            def sdkDirProp = properties.getProperty('sdk.dir')
            if (!sdkDirProp) {
                throw new RuntimeException("No sdk.dir property defined in local.properties file.")
            }
            sdkDir = new File(sdkDirProp)
        } else {
            def envVar = System.getenv("ANDROID_HOME")
            if (envVar != null) {
                sdkDir = new File(envVar)
            }
        }

        if (sdkDir == null) {
            throw new RuntimeException(
                    "SDK location not found. Define location with sdk.dir in the local.properties file or with an ANDROID_HOME environment variable.")
        }

        if (!sdkDir.directory) {
            throw new RuntimeException(
                    "The SDK directory '$sdkDir' specified in local.properties does not exist.")
        }
    }

    protected String getRuntimeJars(ApplicationVariant variant) {
        AndroidBuilder androidBuilder = getAndroidBuilder(variant)

        return androidBuilder.runtimeClasspath.join(":")
    }

    protected ProcessManifestTask createProcessManifestTask(ApplicationVariant variant,
                                                        String manifestOurDir) {
        def processManifestTask = project.tasks.add("process${variant.name}Manifest",
                ProcessManifestTask)
        processManifestTask.plugin = this
        processManifestTask.variant = variant
        processManifestTask.configObjects = variant.configObjects
        processManifestTask.conventionMapping.processedManifest = {
            project.file(
                    "$project.buildDir/${manifestOurDir}/$variant.dirName/AndroidManifest.xml")
        }

        return processManifestTask
    }

    protected CrunchResourcesTask createCrunchResTask(ApplicationVariant variant) {
        def crunchTask = project.tasks.add("crunch${variant.name}Res", CrunchResourcesTask)
        crunchTask.plugin = this
        crunchTask.variant = variant
        crunchTask.configObjects = variant.configObjects
        crunchTask.conventionMapping.resDirectories = { variant.config.resourceInputs }
        crunchTask.conventionMapping.outputDir = {
            project.file("$project.buildDir/res/$variant.dirName")
        }

        return crunchTask
    }

    protected GenerateBuildConfigTask createBuildConfigTask(ApplicationVariant variant,
                                                            ProcessManifestTask processManifestTask) {
        def generateBuildConfigTask = project.tasks.add(
                "generate${variant.name}BuildConfig", GenerateBuildConfigTask)
        if (processManifestTask != null) {
            // This is in case the manifest is generated
            generateBuildConfigTask.dependsOn processManifestTask
        }
        generateBuildConfigTask.plugin = this
        generateBuildConfigTask.variant = variant
        generateBuildConfigTask.configObjects = variant.configObjects
        generateBuildConfigTask.optionalJavaLines = variant.buildConfigLines
        generateBuildConfigTask.conventionMapping.sourceOutputDir = {
            project.file("$project.buildDir/source/${variant.dirName}")
        }
        return generateBuildConfigTask
    }

    protected ProcessResourcesTask createProcessResTask(ApplicationVariant variant,
                                                    ProcessManifestTask processManifestTask,
                                                    CrunchResourcesTask crunchTask) {
        def processResources = project.tasks.add("process${variant.name}Res", ProcessResourcesTask)
        processResources.dependsOn processManifestTask
        processResources.plugin = this
        processResources.variant = variant
        processResources.configObjects = variant.configObjects
        processResources.conventionMapping.manifestFile = { processManifestTask.processedManifest }
        // TODO: unify with generateBuilderConfig somehow?
        processResources.conventionMapping.sourceOutputDir = {
            project.file("$project.buildDir/source/$variant.dirName")
        }
        processResources.conventionMapping.packageFile = {
            project.file(
                    "$project.buildDir/libs/${project.archivesBaseName}-${variant.baseName}.ap_")
        }
        if (variant.runProguard) {
            processResources.conventionMapping.proguardFile = {
                project.file("$project.buildDir/proguard/${variant.dirName}/rules.txt")
            }
        }

        if (crunchTask != null) {
            processResources.dependsOn crunchTask
            processResources.conventionMapping.crunchDir = { crunchTask.outputDir }
            processResources.conventionMapping.resDirectories = { crunchTask.resDirectories }
        } else {
            processResources.conventionMapping.resDirectories = { variant.config.resourceInputs }
        }

        processResources.aaptOptions = extension.aaptOptions
        return processResources
    }

    protected void createCompileTask(ApplicationVariant variant,
                                     ApplicationVariant testedVariant,
                                     ProcessResourcesTask processResources,
                                     GenerateBuildConfigTask generateBuildConfigTask) {
        def compileTask = project.tasks.add("compile${variant.name}", Compile)
        compileTask.dependsOn processResources, generateBuildConfigTask

        VariantConfiguration config = variant.config

        List<Object> sourceList = new ArrayList<Object>();
        sourceList.add(((AndroidSourceSet) config.defaultSourceSet).sourceSet.java)
        sourceList.add({ processResources.sourceOutputDir })
        if (config.getType() != VariantConfiguration.Type.TEST) {
            sourceList.add(((AndroidSourceSet) config.buildTypeSourceSet).sourceSet.java)
        }
        if (config.hasFlavors()) {
            for (com.android.builder.SourceSet flavorSourceSet : config.flavorSourceSets) {
                sourceList.add(((AndroidSourceSet) flavorSourceSet).sourceSet.java)
            }
        }
        compileTask.source = sourceList.toArray()

        // if the test is for a full app, the tested runtimeClasspath is added to the classpath for
        // compilation only, not for packaging
        variant.packagedClasspath = project.files(config.compileClasspath)
        if (testedVariant != null &&
                testedVariant.config.type != VariantConfiguration.Type.LIBRARY) {
            compileTask.classpath = variant.packagedClasspath + testedVariant.runtimeClasspath
        } else {
            compileTask.classpath = variant.packagedClasspath
        }

        compileTask.conventionMapping.destinationDir = {
            project.file("$project.buildDir/classes/$variant.dirName")
        }
        compileTask.doFirst {
            compileTask.options.bootClasspath = getRuntimeJars(variant)
        }

        // Wire up the outputs
        variant.runtimeClasspath = compileTask.outputs.files + compileTask.classpath
        variant.resourcePackage = project.files({processResources.packageFile}) {
            builtBy processResources
        }
        variant.compileTask = compileTask
    }

    protected void createTestTasks(TestAppVariant variant, ProductionAppVariant testedVariant) {
        // Add a task to process the manifest
        def processManifestTask = createProcessManifestTask(variant, "manifests")

        // Add a task to crunch resource files
        def crunchTask = createCrunchResTask(variant)

        if (testedVariant.config.type == VariantConfiguration.Type.LIBRARY) {
            // in this case the tested library must be fully built before test can be built!
            if (testedVariant.assembleTask != null) {
                processManifestTask.dependsOn testedVariant.assembleTask
                crunchTask.dependsOn testedVariant.assembleTask
            }
        }

        // Add a task to create the BuildConfig class
        def generateBuildConfigTask = createBuildConfigTask(variant, processManifestTask)

        // Add a task to generate resource source files
        def processResources = createProcessResTask(variant, processManifestTask, crunchTask)

        // Add a task to compile the test application
        createCompileTask(variant, testedVariant, processResources, generateBuildConfigTask)

        Task assembleTask = addPackageTasks(variant, null, true /*isTestApk*/)

        if (assembleTest != null) {
            assembleTest.dependsOn assembleTask
        }

        def runTestsTask = project.tasks.add("Run${variant.name}Tests", RunTestsTask)
        runTestsTask.sdkDir = sdkDir
        runTestsTask.variant = variant

        runAllTests.dependsOn runTestsTask
    }

    protected Task addPackageTasks(ApplicationVariant variant, Task assembleTask,
                                   boolean isTestApk) {
        // Add a dex task
        def dexTaskName = "dex${variant.name}"
        def dexTask = project.tasks.add(dexTaskName, DexTask)
        dexTask.dependsOn variant.compileTask
        dexTask.plugin = this
        dexTask.variant = variant
        dexTask.conventionMapping.libraries = { variant.packagedClasspath }
        dexTask.conventionMapping.sourceFiles = { variant.compileTask.outputs.files }
        dexTask.conventionMapping.outputFile = {
            project.file(
                    "${project.buildDir}/libs/${project.archivesBaseName}-${variant.baseName}.dex")
        }
        dexTask.dexOptions = extension.dexOptions

        // Add a task to generate application package
        def packageApp = project.tasks.add("package${variant.name}", PackageApplicationTask)
        packageApp.dependsOn variant.resourcePackage, dexTask
        packageApp.plugin = this
        packageApp.variant = variant
        packageApp.configObjects = variant.configObjects

        def signedApk = variant.isSigned()

        def apkName = signedApk ?
            "${project.archivesBaseName}-${variant.baseName}-unaligned.apk" :
            "${project.archivesBaseName}-${variant.baseName}-unsigned.apk"

        packageApp.conventionMapping.outputFile = {
            project.file("$project.buildDir/apk/${apkName}")
        }
        packageApp.conventionMapping.resourceFile = { variant.resourcePackage.singleFile }
        packageApp.conventionMapping.dexFile = { dexTask.outputFile }

        def appTask = packageApp

        if (signedApk) {
            if (variant.zipAlign) {
                // Add a task to zip align application package
                def alignApp = project.tasks.add("zipalign${variant.name}", ZipAlignTask)
                alignApp.dependsOn packageApp
                alignApp.conventionMapping.inputFile = { packageApp.outputFile }
                alignApp.conventionMapping.outputFile = {
                    project.file(
                            "$project.buildDir/apk/${project.archivesBaseName}-${variant.baseName}.apk")
                }
                alignApp.sdkDir = sdkDir

                appTask = alignApp
            }

            // Add a task to install the application package
            def installApp = project.tasks.add("install${variant.name}", InstallTask)
            installApp.description = "Installs the " + variant.description
            installApp.group = "Install"
            installApp.dependsOn appTask
            installApp.conventionMapping.packageFile = { appTask.outputFile }
            installApp.sdkDir = sdkDir

            if (isTestApk) {
                installAllForTests.dependsOn installApp
            }
        }

        // Add an assemble task
        Task returnTask = null
        if (assembleTask == null) {
            assembleTask = project.tasks.add("assemble${variant.name}")
            assembleTask.description = "Assembles the " + variant.description
            assembleTask.group = BasePlugin.BUILD_GROUP
            returnTask = assembleTask
        }
        assembleTask.dependsOn appTask

        // add an uninstall task
        def uninstallApp = project.tasks.add("uninstall${variant.name}", UninstallTask)
        uninstallApp.description = "Uninstalls the " + variant.description
        uninstallApp.group = AndroidBasePlugin.INSTALL_GROUP
        uninstallApp.variant = variant
        uninstallApp.sdkDir = sdkDir

        uninstallAll.dependsOn uninstallApp

        return returnTask
    }

}
