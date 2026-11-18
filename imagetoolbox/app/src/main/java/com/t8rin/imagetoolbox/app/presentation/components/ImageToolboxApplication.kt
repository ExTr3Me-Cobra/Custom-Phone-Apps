/*
 * ImageToolbox is an image editor for android
 * Copyright (c) 2026 T8RIN (Malik Mukhametzyanov)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.t8rin.imagetoolbox.app.presentation.components

import com.t8rin.imagetoolbox.app.presentation.components.functions.attachLogWriter
import com.t8rin.imagetoolbox.app.presentation.components.functions.initColorNames
import com.t8rin.imagetoolbox.app.presentation.components.functions.initNeuralTool
import com.t8rin.imagetoolbox.app.presentation.components.functions.initOpenCV
import com.t8rin.imagetoolbox.app.presentation.components.functions.injectBaseComponent
import com.t8rin.imagetoolbox.app.presentation.components.functions.setupFlags
import com.t8rin.imagetoolbox.app.presentation.components.utils.isMain
import com.t8rin.imagetoolbox.core.crash.presentation.components.applyGlobalExceptionHandler
import com.t8rin.imagetoolbox.core.domain.coroutines.AppScope
import com.t8rin.imagetoolbox.core.domain.remote.AnalyticsManager
import com.t8rin.imagetoolbox.core.domain.remote.DownloadManager
import com.t8rin.imagetoolbox.core.domain.saving.KeepAliveService
import com.t8rin.imagetoolbox.core.resources.emoji.Emoji.initEmoji
import com.t8rin.imagetoolbox.core.ui.utils.ComposeApplication
import com.t8rin.imagetoolbox.core.utils.initAppContext
import com.t8rin.imagetoolbox.feature.media_picker.domain.SemanticSearchScheduler
import dagger.hilt.android.HiltAndroidApp
import org.fossify.gallery.ViperVisionGallery
import javax.inject.Inject


@HiltAndroidApp
class ImageToolboxApplication : ComposeApplication() {

    @Inject
    lateinit var keepAliveService: KeepAliveService

    @Inject
    lateinit var appScope: AppScope

    @Inject
    lateinit var downloadManager: DownloadManager

    @Inject
    lateinit var analyticsManager: AnalyticsManager

    @Inject
    lateinit var semanticSearchScheduler: SemanticSearchScheduler

    private var isSetupCompleted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        // Viper Vision: the diagnostic log (Settings → Diagnostic log), first so it sees everything.
        com.vipervision.editor.ViperLog.install(this)
        com.t8rin.imagetoolbox.core.data.remote.DownloadLog.sink = { com.vipervision.editor.ViperLog.i("DOWNLOAD", it) }
        runSetup()
        com.vipervision.editor.ViperLog.catchCrashes()
    }

    override fun runSetup() {
        if (isSetupCompleted) return

        if (isMain()) {
            setupFlags()
            initAppContext()
            initEmoji()
            initOpenCV()
            initNeuralTool()
            initColorNames()
            attachLogWriter()
            applyGlobalExceptionHandler()
            injectBaseComponent()
            // Viper Vision: Image Toolbox's CLIP search is replaced by the gallery's AI search
            // (SigLIP 2 + text reading); free the space its model took, and keep the new index up to date.
            java.io.File(filesDir, "ai_models/clip_search").deleteRecursively()
            com.vipervision.editor.search.AiSearch.onAppStart(this)
            // Viper Vision: start up the Gallery side of the app too.
            ViperVisionGallery.init(this)

            isSetupCompleted = true
        }
    }

}