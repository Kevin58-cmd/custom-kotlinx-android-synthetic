package com.github.kevin58cmd.customkotlinxandroidsynthetic.startup

import com.github.kevin58cmd.customkotlinxandroidsynthetic.SyntheticViewGenerator
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SyntheticStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        SyntheticViewGenerator.generate(project.basePath ?: return)
    }
}