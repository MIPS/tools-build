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
package org.gradle.android

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.OutputFile
import org.gradle.android.internal.AndroidManifest
import org.gradle.api.tasks.Optional

class GenerateManifest extends DefaultTask {
    @InputFile @Optional
    File sourceFile

    @OutputFile
    File outputFile

    @Input
    String packageName

    @Input
    Integer versionCode

    @Input
    String versionName

    @TaskAction
    def generate() {
        AndroidManifest manifest = new AndroidManifest()
        if (getSourceFile() != null) {
            manifest.load(getSourceFile())
        }
        manifest.packageName = getPackageName()
        manifest.versionCode = getVersionCode()
        manifest.versionName = getVersionName()
        manifest.save(getOutputFile())
    }
}
