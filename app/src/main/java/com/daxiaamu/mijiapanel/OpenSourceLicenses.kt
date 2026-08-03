package com.daxiaamu.mijiapanel

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private data class OpenSourceProject(
    val name: String,
    @param:StringRes val role: Int,
    val license: String,
    val projectUrl: String,
    val licenseUrl: String,
)

private val openSourceProjects = listOf(
    OpenSourceProject(
        name = "MijiaPanel",
        role = R.string.open_source_role_app,
        license = "GNU GPL v3.0 or later",
        projectUrl = "https://github.com/daxiaamu/mijiapanel",
        licenseUrl = "https://github.com/daxiaamu/mijiapanel/blob/main/LICENSE",
    ),
    OpenSourceProject(
        name = "AndroidX / Jetpack Compose / Material 3 / CameraX",
        role = R.string.open_source_role_dependency,
        license = "Apache License 2.0",
        projectUrl = "https://github.com/androidx/androidx",
        licenseUrl = "https://source.android.com/docs/setup/about/licenses",
    ),
    OpenSourceProject(
        name = "Kotlin",
        role = R.string.open_source_role_dependency,
        license = "Apache License 2.0",
        projectUrl = "https://github.com/JetBrains/kotlin",
        licenseUrl = "https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt",
    ),
    OpenSourceProject(
        name = "libxposed API / service",
        role = R.string.open_source_role_dependency,
        license = "Apache License 2.0",
        projectUrl = "https://github.com/libxposed",
        licenseUrl = "https://github.com/libxposed/api/blob/master/LICENSE",
    ),
    OpenSourceProject(
        name = "DexKit",
        role = R.string.open_source_role_dependency,
        license = "Apache License 2.0 / GNU LGPL v3.0",
        projectUrl = "https://github.com/LuckyPray/DexKit",
        licenseUrl = "https://github.com/LuckyPray/DexKit/blob/master/LICENSE",
    ),
    OpenSourceProject(
        name = "Google ML Kit Face Detection",
        role = R.string.open_source_role_third_party,
        license = "ML Kit Terms of Service",
        projectUrl = "https://developers.google.com/ml-kit/vision/face-detection/android",
        licenseUrl = "https://developers.google.com/ml-kit/terms",
    ),
    OpenSourceProject(
        name = "Google ML Kit Pose Detection",
        role = R.string.open_source_role_third_party,
        license = "ML Kit Terms of Service",
        projectUrl = "https://developers.google.com/ml-kit/vision/pose-detection/android",
        licenseUrl = "https://developers.google.com/ml-kit/terms",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenSourceLicensesSheet(
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var selectedProject by remember { mutableStateOf<OpenSourceProject?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.open_source_licenses),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.open_source_licenses_summary),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            openSourceProjects.forEach { project ->
                ListItem(
                    headlineContent = { Text(project.name) },
                    supportingContent = {
                        Text("${stringResource(project.role)} · ${project.license}")
                    },
                    trailingContent = {
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable { selectedProject = project },
                )
            }
        }
    }

    selectedProject?.let { project ->
        AlertDialog(
            onDismissRequest = { selectedProject = null },
            title = { Text(project.name) },
            text = {
                Column {
                    Text(stringResource(project.role))
                    Text(
                        text = stringResource(
                            R.string.open_source_license_label,
                            project.license,
                        ),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedProject = null }) {
                    Text(stringResource(R.string.close))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onOpenUrl(project.projectUrl) }) {
                        Text(stringResource(R.string.open_source_project_homepage))
                    }
                    TextButton(onClick = { onOpenUrl(project.licenseUrl) }) {
                        Text(stringResource(R.string.open_source_view_license))
                    }
                }
            },
        )
    }
}
