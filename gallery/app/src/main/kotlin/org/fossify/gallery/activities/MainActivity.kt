package org.fossify.gallery.activities

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.Images
import android.provider.MediaStore.Video
import android.view.ViewGroup
import android.view.View
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.dialogs.CreateNewFolderDialog
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.fixDuplicateLauncherIcons
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.checkWhatsNew
import org.fossify.commons.extensions.deleteFiles
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFileCount
import org.fossify.commons.extensions.getFilePublicUri
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getLatestMediaByDateId
import org.fossify.commons.extensions.getLatestMediaId
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperSize
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getStorageDirectories
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.handleHiddenFolderPasswordProtection
import org.fossify.commons.extensions.handleLockedFolderOpening
import org.fossify.commons.extensions.hasAllPermissions
import org.fossify.commons.extensions.hasOTGConnected
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isExternalStorageManager
import org.fossify.commons.extensions.isGif
import org.fossify.commons.extensions.isGone
import org.fossify.commons.extensions.isImageFast
import org.fossify.commons.extensions.isMediaFile
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isRawFast
import org.fossify.commons.extensions.isSvg
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.sdCardPath
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toFileDirItem
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.FAVORITES
import org.fossify.commons.helpers.PERMISSION_READ_STORAGE
import org.fossify.commons.helpers.SORT_BY_DATE_MODIFIED
import org.fossify.commons.helpers.SORT_BY_DATE_TAKEN
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.commons.helpers.SORT_USE_NUMERIC_VALUE
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.Release
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.commons.views.MyRecyclerView
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.adapters.DirectoryAdapter
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.databinding.ActivityMainBinding
import org.fossify.gallery.dialogs.ChangeSortingDialog
import org.fossify.gallery.dialogs.ChangeViewTypeDialog
import org.fossify.gallery.dialogs.FilterMediaDialog
import org.fossify.gallery.dialogs.GrantAllFilesDialog
import org.fossify.gallery.extensions.useViperVisionBar
import org.fossify.gallery.extensions.keepOnlyViperVisionItems
import org.fossify.gallery.extensions.settingsAsDotsButton
import org.fossify.gallery.extensions.addTempFolderIfNeeded
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.createDirectoryFromMedia
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.getCachedDirectories
import org.fossify.gallery.extensions.getCachedMedia
import org.fossify.gallery.extensions.getDirectorySortingValue
import org.fossify.gallery.extensions.getDirsToShow
import org.fossify.gallery.extensions.getDistinctPath
import org.fossify.gallery.extensions.getFavoritePaths
import org.fossify.gallery.extensions.getNoMediaFoldersSync
import org.fossify.gallery.extensions.getOTGFolderChildrenNames
import org.fossify.gallery.extensions.getSortedDirectories
import org.fossify.gallery.extensions.handleExcludedFolderPasswordProtection
import org.fossify.gallery.extensions.launchGrantAllFilesIntent
import org.fossify.gallery.extensions.isDownloadsFolder
import org.fossify.gallery.extensions.launchCamera
import org.fossify.gallery.extensions.launchSettings
import org.fossify.gallery.extensions.openAiSearch
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.movePathsInRecycleBin
import org.fossify.gallery.extensions.movePinnedDirectoriesToFront
import org.fossify.gallery.extensions.openRecycleBin
import org.fossify.gallery.extensions.removeInvalidDBDirectories
import org.fossify.gallery.extensions.storeDirectoryItems
import org.fossify.gallery.extensions.tryDeleteFileDirItem
import org.fossify.gallery.extensions.updateDBDirectory
import org.fossify.gallery.extensions.updateWidgets
import org.fossify.gallery.helpers.DIRECTORY
import org.fossify.gallery.helpers.GET_ANY_INTENT
import org.fossify.gallery.helpers.GET_IMAGE_INTENT
import org.fossify.gallery.helpers.GET_VIDEO_INTENT
import org.fossify.gallery.helpers.GROUP_BY_DATE_TAKEN_DAILY
import org.fossify.gallery.helpers.GROUP_BY_DATE_TAKEN_MONTHLY
import org.fossify.gallery.helpers.GROUP_BY_LAST_MODIFIED_DAILY
import org.fossify.gallery.helpers.GROUP_BY_LAST_MODIFIED_MONTHLY
import org.fossify.gallery.helpers.GROUP_DESCENDING
import org.fossify.gallery.helpers.LOCATION_INTERNAL
import org.fossify.gallery.helpers.MAX_COLUMN_COUNT
import org.fossify.gallery.helpers.MONTH_MILLISECONDS
import org.fossify.gallery.helpers.MediaFetcher
import org.fossify.gallery.helpers.PICKED_PATHS
import org.fossify.gallery.helpers.RECYCLE_BIN
import org.fossify.gallery.helpers.SET_WALLPAPER_INTENT
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SHOW_TEMP_HIDDEN_DURATION
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION
import org.fossify.gallery.helpers.TYPE_GIFS
import org.fossify.gallery.helpers.TYPE_IMAGES
import org.fossify.gallery.helpers.TYPE_RAWS
import org.fossify.gallery.helpers.TYPE_SVGS
import org.fossify.gallery.helpers.TYPE_VIDEOS
import org.fossify.gallery.helpers.getDefaultFileFilter
import org.fossify.gallery.helpers.getPermissionToRequest
import org.fossify.gallery.helpers.getPermissionsToRequest
import org.fossify.gallery.interfaces.DirectoryOperationsListener
import org.fossify.gallery.jobs.NewPhotoFetcher
import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.Medium
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

class MainActivity : SimpleActivity(), DirectoryOperationsListener {
    override var isSearchBarEnabled = true
    
    companion object {
        private const val PICK_MEDIA = 2
        private const val PICK_WALLPAPER = 3
        private const val LAST_MEDIA_CHECK_PERIOD = 3000L
    }

    private var mIsPickImageIntent = false
    private var mIsPickVideoIntent = false
    private var mIsGetImageContentIntent = false
    private var mIsGetVideoContentIntent = false
    private var mIsGetAnyContentIntent = false
    private var mIsSetWallpaperIntent = false
    private var mAllowPickingMultiple = false
    private var mIsThirdPartyIntent = false
    private var mIsGettingDirs = false
    private var mLoadedInitialPhotos = false
    private var mShouldStopFetching = false
    private var mWasDefaultFolderChecked = false
    private var mLatestMediaId = 0L
    private var mLatestMediaDateId = 0L

    // used at "Group direct subfolders" for navigation
    private var mCurrentPathPrefix = ""

    // used at "Group direct subfolders" for navigating Up with the back button
    private var mOpenedSubfolders = arrayListOf("")

    private var mDateFormat = ""
    private var mTimeFormat = ""
    private var mLastMediaHandler = Handler()
    private var mTempShowHiddenHandler = Handler()
    private var mZoomListener: MyRecyclerView.MyZoomListener? = null
    private var mLastMediaFetcher: MediaFetcher? = null
    private var mDirs = ArrayList<Directory>()
    private var mDirsIgnoringSearch = ArrayList<Directory>()

    private var mStoredAnimateGifs = true
    private var mStoredCropThumbnails = true
    private var mStoredScrollHorizontally = true
    private var mStoredTextColor = 0
    private var mStoredPrimaryColor = 0
    private var mStoredStyleString = ""
    private val binding by viewBinding(ActivityMainBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        // Viper Vision: tidy up a second launcher icon left by an interrupted icon switch.
        fixDuplicateLauncherIcons()

        if (savedInstanceState == null) {
            config.temporarilyShowHidden = false
            config.temporarilyShowExcluded = false
            config.tempSkipDeleteConfirmation = false
            config.tempSkipRecycleBin = false
            removeTempFolder()
            checkRecycleBinItems()
            startNewPhotoFetcher()
        }

        mIsPickImageIntent = isPickImageIntent(intent)
        mIsPickVideoIntent = isPickVideoIntent(intent)
        mIsGetImageContentIntent = isGetImageContentIntent(intent)
        mIsGetVideoContentIntent = isGetVideoContentIntent(intent)
        mIsGetAnyContentIntent = isGetAnyContentIntent(intent)
        mIsSetWallpaperIntent = isSetWallpaperIntent(intent)
        mAllowPickingMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        mIsThirdPartyIntent = mIsPickImageIntent
                || mIsPickVideoIntent
                || mIsGetImageContentIntent
                || mIsGetVideoContentIntent
                || mIsGetAnyContentIntent
                || mIsSetWallpaperIntent

        setupOptionsMenu()
        refreshMenuItems()

        setupEdgeToEdge(
            padBottomImeAndSystem = listOf(binding.directoriesGrid)
        )

        binding.directoriesRefreshLayout.setOnRefreshListener { getDirectories() }
        storeStateVariables()
        checkWhatsNewDialog()
        setupLatestMediaId()

        if (!config.wereFavoritesPinned) {
            config.addPinnedFolders(hashSetOf(FAVORITES))
            config.wereFavoritesPinned = true
        }

        if (!config.wasRecycleBinPinned) {
            config.addPinnedFolders(hashSetOf(RECYCLE_BIN))
            config.wasRecycleBinPinned = true
            config.saveFolderGrouping(SHOW_ALL, GROUP_BY_DATE_TAKEN_DAILY or GROUP_DESCENDING)
        }

        if (!config.wasSVGShowingHandled) {
            config.wasSVGShowingHandled = true
            if (config.filterMedia and TYPE_SVGS == 0) {
                config.filterMedia += TYPE_SVGS
            }
        }

        if (!config.wasSortingByNumericValueAdded) {
            config.wasSortingByNumericValueAdded = true
            config.sorting = config.sorting or SORT_USE_NUMERIC_VALUE
        }

        updateWidgets()
        registerFileUpdateListener()

        binding.directoriesSwitchSearching.setOnClickListener {
            launchSearchActivity()
        }

        // just request the permission, tryLoadGallery will then trigger in onResume
        handleMediaPermissions()
    }

    private fun handleMediaPermissions(callback: (() -> Unit)? = null) {
        // Viper Vision: All files access is a step of the setup (showViperSetup) instead of
        // Fossify's prompt here, which could appear on top of it.
        requestMediaPermissions(enableRationale = true) {
            callback?.invoke()
        }
    }

    override fun onStart() {
        super.onStart()
        mTempShowHiddenHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        // Viper Vision: limited photo access (or none) isn't enough; asks again each time this
        // screen shows until "Allow all" is given (e.g. back from the phone's Settings).
        if (!mIsThirdPartyIntent && !isAskingPermissions && !hasAllPermissions(getPermissionsToRequest())) {
            handleMediaPermissions()
        }
        if (org.fossify.gallery.ViperVisionGallery.cameToFront) {
            org.fossify.gallery.ViperVisionGallery.cameToFront = false
            aiModelsChecks = 0
        }
        // Checked each time this screen shows (e.g. after Back from the AI models screen), so
        // the download can't be skipped.
        offerAiModelsOnResume = true
        // Samsung phones: the required setup first, then the AI models.
        if (!showViperSetup() && offerAiModelsOnResume) offerAiModels()
        updateMenuColors()
        config.isThirdPartyIntent = false
        mDateFormat = config.dateFormat
        mTimeFormat = getTimeFormat()

        refreshMenuItems()

        if (mStoredAnimateGifs != config.animateGifs) {
            getRecyclerAdapter()?.updateAnimateGifs(config.animateGifs)
        }

        if (mStoredCropThumbnails != config.cropThumbnails) {
            getRecyclerAdapter()?.updateCropThumbnails(config.cropThumbnails)
        }

        if (mStoredScrollHorizontally != config.scrollHorizontally) {
            mLoadedInitialPhotos = false
            binding.directoriesGrid.adapter = null
            getDirectories()
        }

        if (mStoredTextColor != getProperTextColor()) {
            getRecyclerAdapter()?.updateTextColor(getProperTextColor())
        }

        val primaryColor = getProperPrimaryColor()
        if (mStoredPrimaryColor != primaryColor) {
            getRecyclerAdapter()?.updatePrimaryColor()
        }

        val styleString =
            "${config.folderStyle}${config.showFolderMediaCount}${config.limitFolderTitle}"
        if (mStoredStyleString != styleString) {
            setupAdapter(mDirs, forceRecreate = true)
        }

        binding.directoriesFastscroller.updateColors(primaryColor)
        binding.directoriesRefreshLayout.isEnabled = config.enablePullToRefresh
        getRecyclerAdapter()?.apply {
            dateFormat = config.dateFormat
            timeFormat = getTimeFormat()
        }

        binding.directoriesEmptyPlaceholder.setTextColor(getProperTextColor())
        binding.directoriesEmptyPlaceholder2.setTextColor(primaryColor)
        binding.directoriesSwitchSearching.setTextColor(primaryColor)
        binding.directoriesSwitchSearching.underlineText()
        binding.directoriesEmptyPlaceholder2.bringToFront()

        if (!binding.mainMenu.isSearchOpen) {
            refreshMenuItems()
            tryLoadGallery()
        }

        if (config.searchAllFilesByDefault) {
            binding.mainMenu.updateHintText(getString(org.fossify.commons.R.string.search_files))
        } else {
            binding.mainMenu.updateHintText(getString(org.fossify.commons.R.string.search_folders))
        }
    }

    override fun onPause() {
        super.onPause()
        binding.directoriesRefreshLayout.isRefreshing = false
        mIsGettingDirs = false
        storeStateVariables()
        mLastMediaHandler.removeCallbacksAndMessages(null)
    }

    override fun onStop() {
        super.onStop()

        if (config.temporarilyShowHidden || config.tempSkipDeleteConfirmation || config.temporarilyShowExcluded) {
            mTempShowHiddenHandler.postDelayed({
                config.temporarilyShowHidden = false
                config.temporarilyShowExcluded = false
                config.tempSkipDeleteConfirmation = false
                config.tempSkipRecycleBin = false
            }, SHOW_TEMP_HIDDEN_DURATION)
        } else {
            mTempShowHiddenHandler.removeCallbacksAndMessages(null)
        }
    }

    override fun onDestroy() {
        // The setup popup goes with this screen (it's shown again when the screen is).
        viperSetupDialog?.dismiss()
        viperSetupDialog = null
        super.onDestroy()
        if (!isChangingConfigurations) {
            config.temporarilyShowHidden = false
            config.temporarilyShowExcluded = false
            config.tempSkipDeleteConfirmation = false
            config.tempSkipRecycleBin = false
            mTempShowHiddenHandler.removeCallbacksAndMessages(null)
            removeTempFolder()
            unregisterFileUpdateListener()

            if (!config.showAll) {
                mLastMediaFetcher?.shouldStop = true
                GalleryDatabase.destroyInstance()
            }
        }
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else if (config.groupDirectSubfolders) {
            if (mCurrentPathPrefix.isEmpty()) {
                appLockManager.lock()
                false
            } else {
                mOpenedSubfolders.removeAt(mOpenedSubfolders.lastIndex)
                mCurrentPathPrefix = mOpenedSubfolders.last()
                setupAdapter(mDirs)
                true
            }
        } else {
            appLockManager.lock()
            false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (resultCode == RESULT_OK) {
            if (requestCode == PICK_MEDIA && resultData != null) {
                val resultIntent = Intent()
                var resultUri: Uri? = null
                if (mIsThirdPartyIntent) {
                    when {
                        intent.extras?.containsKey(MediaStore.EXTRA_OUTPUT) == true
                                && intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 -> {
                            resultUri = fillExtraOutput(resultData)
                        }

                        resultData.extras?.containsKey(PICKED_PATHS) == true -> {
                            fillPickedPaths(resultData, resultIntent)
                        }

                        else -> fillIntentPath(resultData, resultIntent)
                    }
                }

                if (resultUri != null) {
                    resultIntent.data = resultUri
                    resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                setResult(RESULT_OK, resultIntent)
                finish()
            } else if (requestCode == PICK_WALLPAPER) {
                setResult(RESULT_OK)
                finish()
            }
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    private fun refreshMenuItems() {
        if (!mIsThirdPartyIntent) {
            binding.mainMenu.requireToolbar().menu.apply {
                findItem(R.id.column_count).isVisible = config.viewTypeFolders == VIEW_TYPE_GRID
                findItem(R.id.set_as_default_folder).isVisible = !config.defaultFolder.isEmpty()
                findItem(R.id.open_recycle_bin).isVisible =
                    config.useRecycleBin && !config.showRecycleBinAtFolders
                findItem(R.id.more_apps_from_us).isVisible =
                    resources.getBoolean(org.fossify.commons.R.bool.is_google_play_build)
            }
        }

        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.temporarily_show_hidden).isVisible = !config.shouldShowHidden
            findItem(R.id.stop_showing_hidden).isVisible =
                (!isRPlus() || isExternalStorageManager()) && config.temporarilyShowHidden

            findItem(R.id.temporarily_show_excluded).isVisible = !config.temporarilyShowExcluded
            findItem(R.id.stop_showing_excluded).isVisible = config.temporarilyShowExcluded

            // Viper Vision: only Sort and ⋮ (Settings); the grid/folders switch is the
            // top-left button.
            keepOnlyViperVisionItems(R.id.settings, R.id.create_new_folder, R.id.vv_ai_search)
        }
    }

    /** Viper Vision: set when the app is opened, so the AI models offer shows once per opening. */
    private var offerAiModelsOnResume = false
    private var aiModelsChecks = 0

    /**
     * Viper Vision: each time this screen shows, opens the AI models screen (the editor's
     * AiModelsActivity) if any AI model is missing and no download has been asked for (started,
     * or waiting for Wi-Fi), so it can't be skipped. Waits for photo access
     * and for the app's check of which models are already here (vv_models_checked, done when it
     * starts), so someone who has them all isn't asked. The all-in-one APK shows it every time
     * until it has switched to the normal APK.
     */
    private fun offerAiModels() {
        if (mIsThirdPartyIntent || !hasPermission(getPermissionToRequest())) return
        val prefs = getSharedPreferences("Prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("vv_models_checked", false)) {
            // The check is still running (it takes a moment when the app starts); look again.
            if (aiModelsChecks++ < 10) Handler(Looper.getMainLooper()).postDelayed({ if (offerAiModelsOnResume && !isFinishing) offerAiModels() }, 500)
            return
        }
        offerAiModelsOnResume = false
        // The all-in-one APK shows the screen until it has unpacked its models and switched to the
        // normal APK (which has no vv_models).
        val allInOne = runCatching { assets.open("vv_models/index.txt").close(); true }.getOrDefault(false)
        val downloadAsked = prefs.getBoolean("vv_models_requested", false)
        if (!allInOne && (prefs.getBoolean("vv_models_complete", false) || prefs.getBoolean("vv_models_running", false) || downloadAsked)) return
        runCatching { startActivity(Intent().setClassName(packageName, "com.vipervision.editor.search.AiModelsActivity")) }
    }

    private var viperSetupDialog: androidx.appcompat.app.AlertDialog? = null
    private val APP_INFO_OPENED = "vv_setup_app_info_opened"
    private val NOTIFICATIONS_ASKED = "vv_setup_notifications_asked"
    private val NOTIFICATIONS_REQUEST = 4711
    private val TOOLBAR_CHECK_PENDING = "vv_toolbar_check_pending"
    private val TOOLBAR_CHECK_RESULT = "vv_toolbar_check_result"
    private val TOOLBAR_CHECK_SHOTS = "vv_toolbar_check_shots"

    /**
     * Viper Vision, Samsung phones only: a setup that can't be skipped, shown each time this
     * screen comes back until it's done. 1) The accessibility service (the editor's
     * CameraThumbnailService: Samsung Camera's thumbnail, the screenshot toolbar, the Samsung AI
     * bubbles). 2) Samsung's own screenshot toolbar off, while Viper Vision's is on; the service
     * clears "vv_samsung_toolbar_off" if it sees Samsung's toolbar again, which asks again.
     * True if a step is showing.
     */
    private fun showViperSetup(): Boolean {
        viperSetupDialog?.dismiss()
        viperSetupDialog = null
        if (mIsThirdPartyIntent) return false
        // After the photo permission, so the two don't overlap.
        if (!hasPermission(getPermissionToRequest())) return false
        val isSamsung = android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        // Every phone needs the accessibility service (camera shortcut, screenshot toolbar) and,
        // on Android 11+, All files access; Samsung phones also the screenshot toolbar step.
        val needsAllFiles = isRPlus() && !android.os.Environment.isExternalStorageManager()
        // Every required step on this phone, in order (for "Set up Viper Vision (n of m)"). Each is
        // checked again every time this screen shows, so one turned off later is asked for again.
        val stepList = listOfNotNull(
            "access", "toolbar".takeIf { isSamsung }, "battery",
            "notifications".takeIf { android.os.Build.VERSION.SDK_INT >= 33 }, "files".takeIf { isRPlus() }
        )
        val steps = stepList.size
        fun stepOf(key: String) = stepList.indexOf(key) + 1
        val prefs = getSharedPreferences("Prefs", MODE_PRIVATE)
        // Unrestricted battery: otherwise Android may stop the accessibility service in the
        // background. Required, like the rest.
        val needsBattery = getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == false
        // Notifications: AI model downloads and AI search show their progress in one.
        val needsNotifications = android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        val service = "$packageName/com.vipervision.editor.CameraThumbnailService"
        val serviceOn = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').any { it.equals(service, ignoreCase = true) }
        // Switched on but not running (e.g. Android stopped it, or it was on for an earlier
        // install): the service writes its process id when it starts. It starts with the app, so
        // give it a few seconds after the app starts before asking.
        val serviceRunning = prefs.getInt("vv_a11y_running_pid", 0) == android.os.Process.myPid()
        val sinceStart = android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()
        if (serviceOn && !serviceRunning && sinceStart < 4000) {
            Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing && !isDestroyed) showViperSetup() }, 4000 - sinceStart)
        }
        val builder = getAlertDialogBuilder().setCancelable(false)
        // Test screenshots from step 2 go once nothing would make Android ask about deleting them.
        if (!needsAllFiles) deleteTestScreenshots(prefs)
        when {
            !serviceOn -> builder
                .setTitle(getString(R.string.vv_setup_title, stepOf("access"), steps))
                .setMessage(R.string.vv_setup_access_message)
                .setView(accessibilitySetupButtons(prefs))

            !serviceRunning && sinceStart >= 4000 -> builder
                .setTitle(getString(R.string.vv_setup_title, stepOf("access"), steps))
                .setMessage(R.string.vv_setup_access_not_running)
                .setView(accessibilitySetupButtons(prefs))

            isSamsung && prefs.getBoolean("vv_screenshot_toolbar", true) && !prefs.getBoolean("vv_samsung_toolbar_off", false) -> {
                // The screenshot toolbar (the editor's ScreenshotToolbar) checks the next screenshot.
                prefs.edit().putBoolean(TOOLBAR_CHECK_PENDING, true).apply()
                builder
                    .setTitle(getString(R.string.vv_setup_title, stepOf("toolbar"), steps))
                    .setMessage(R.string.vv_setup_toolbar_message)
                    .setView(toolbarCheckView(prefs))
            }

            needsBattery -> builder
                .setTitle(getString(R.string.vv_setup_title, stepOf("battery"), steps))
                .setMessage(R.string.vv_setup_battery_message)
                .setPositiveButton(R.string.vv_setup_battery_open) { _, _ ->
                    val ask = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
                    runCatching { startActivity(ask) }.onFailure {
                        runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
                }

            needsNotifications -> builder
                .setTitle(getString(R.string.vv_setup_title, stepOf("notifications"), steps))
                .setMessage(R.string.vv_setup_notifications_message)
                .setPositiveButton(R.string.vv_setup_battery_open) { _, _ ->
                    // Android's own prompt the first time (and while it still offers it), else
                    // the app's notification settings.
                    val asked = prefs.getBoolean(NOTIFICATIONS_ASKED, false)
                    if (!asked || shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                        prefs.edit().putBoolean(NOTIFICATIONS_ASKED, true).apply()
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS_REQUEST)
                    } else {
                        runCatching {
                            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                        }
                    }
                }

            // Saving over originals, rotating, deleting, moving and hidden albums without Android
            // asking each time. Only "All files" counts (checked here each time).
            needsAllFiles -> builder
                .setTitle(if (steps > 1) getString(R.string.vv_setup_title, stepOf("files"), steps) else getString(R.string.vv_setup_title_single))
                .setMessage(R.string.vv_setup_files_message)
                .setPositiveButton(R.string.vv_setup_files_open) { _, _ -> launchGrantAllFilesIntent() }

            else -> return false
        }
        viperSetupDialog = builder.show().apply {
            setOnDismissListener { toolbarCheckListener?.let(prefs::unregisterOnSharedPreferenceChangeListener) }
        }
        return true
    }

    /**
     * Deletes the screenshots taken for step 2's check (listed by the editor's ScreenshotToolbar),
     * without asking: only once All files access is granted (the last step), so Android has no
     * reason to ask. Until then they stay listed.
     */
    private fun deleteTestScreenshots(prefs: android.content.SharedPreferences) {
        val ids = prefs.getString(TOOLBAR_CHECK_SHOTS, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return
        if (isRPlus() && !android.os.Environment.isExternalStorageManager()) return
        prefs.edit().remove(TOOLBAR_CHECK_SHOTS).apply()
        ensureBackgroundThread {
            for (id in ids) {
                val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                @Suppress("DEPRECATION")
                val path = runCatching {
                    contentResolver.query(uri, arrayOf(android.provider.MediaStore.Images.Media.DATA), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull()
                val deleted = runCatching { contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false) ||
                        (path != null && runCatching { java.io.File(path).delete() }.getOrDefault(false).also {
                            if (it) android.media.MediaScannerConnection.scanFile(this, arrayOf(path), null, null)
                        })
                if (!deleted && path != null) android.util.Log.w("ViperSetup", "Couldn't delete test screenshot $path")
            }
        }
    }

    private var toolbarCheckListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * Step 2's check: what the last test screenshot showed, Open Settings, and Continue, which
     * stays greyed out with a red ✗ until a screenshot is taken without Samsung's toolbar
     * appearing (then a green ✓). Updates as soon as the result comes in.
     */
    private fun toolbarCheckView(prefs: android.content.SharedPreferences): View {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val status = TextView(this).apply {
            textSize = 15f
            setPadding(pad + pad / 2, 0, pad, pad / 2)
        }
        val continueButton = com.google.android.material.button.MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false
            textSize = 16f
            setOnClickListener {
                prefs.edit().putBoolean("vv_samsung_toolbar_off", true)
                    .putBoolean(TOOLBAR_CHECK_PENDING, false).remove(TOOLBAR_CHECK_RESULT).apply()
                viperSetupDialog?.dismiss()
                if (!showViperSetup() && offerAiModelsOnResume) offerAiModels()
            }
        }
        val red = 0xFFE5484D.toInt()
        val green = 0xFF30A46C.toInt()
        fun refresh() {
            val result = prefs.getString(TOOLBAR_CHECK_RESULT, null)
            val passed = result == "pass"
            status.setText(
                when (result) {
                    "pass" -> R.string.vv_setup_check_passed
                    "fail" -> R.string.vv_setup_check_failed
                    else -> R.string.vv_setup_check_waiting
                }
            )
            status.setTextColor(
                when (result) {
                    "pass" -> green
                    "fail" -> red
                    else -> getProperTextColor()
                }
            )
            val mark = if (passed) "✓" else "✗"
            continueButton.text = android.text.SpannableString("$mark  ${getString(R.string.vv_setup_continue)}").apply {
                setSpan(android.text.style.ForegroundColorSpan(if (passed) green else red), 0, 1, 0)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, 1, 0)
            }
            continueButton.setTextColor(getProperPrimaryColor())
            continueButton.isEnabled = passed
            continueButton.alpha = if (passed) 1f else 0.5f
        }
        refresh()
        toolbarCheckListener?.let(prefs::unregisterOnSharedPreferenceChangeListener)
        toolbarCheckListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TOOLBAR_CHECK_RESULT) runOnUiThread { refresh() }
        }.also(prefs::registerOnSharedPreferenceChangeListener)

        val openSettings = com.google.android.material.button.MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            setText(R.string.vv_setup_toolbar_open)
            isAllCaps = false
            textSize = 16f
            setTextColor(getProperPrimaryColor())
            setOnClickListener {
                viperSetupDialog?.dismiss()
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 0, pad, 0)
            addView(openSettings, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(continueButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad / 2, 0, 0)
            addView(status)
            addView(buttons)
        }
    }

    /**
     * Straight to Viper Vision's own switch (Android 11+ has a page per service), else the
     * Accessibility list.
     */
    private fun openViperAccessibilitySwitch() {
        val service = android.content.ComponentName(packageName, "com.vipervision.editor.CameraThumbnailService")
        val direct = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && runCatching {
            startActivity(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, service.flattenToString())
            )
        }.isSuccess
        if (!direct) runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    /**
     * The accessibility step's two buttons, each with its turn above it: Open Accessibility
     * ("Do 1st": tapping the greyed-out service there is what makes Allow restricted settings
     * appear), App info ("Do 2nd"). Once App info has been opened, Open Accessibility is
     * "Do 3rd" (turning it on).
     */
    private fun accessibilitySetupButtons(prefs: android.content.SharedPreferences): View {
        val density = resources.displayMetrics.density
        val appInfoOpened = prefs.getBoolean(APP_INFO_OPENED, false)
        fun column(label: Int, button: Int, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                setText(label)
                setTextColor(getProperTextColor())
                alpha = 0.7f
                textSize = 13f
                gravity = android.view.Gravity.CENTER
            })
            addView(com.google.android.material.button.MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(button)
                isAllCaps = false
                textSize = 16f
                setTextColor(getProperPrimaryColor())
                setOnClickListener {
                    viperSetupDialog?.dismiss()
                    onClick()
                }
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(column(if (appInfoOpened) R.string.vv_setup_do_3rd else R.string.vv_setup_do_1st, R.string.vv_setup_access_open) {
                openViperAccessibilitySwitch()
            })
            addView(column(R.string.vv_setup_do_2nd, R.string.vv_setup_app_info) {
                prefs.edit().putBoolean(APP_INFO_OPENED, true).apply()
                runCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", packageName, null))
                    )
                }
            })
        }
    }

    /** Viper Vision: no search; the top-left button switches to the photo grid. */
    private fun setupViperVisionBar() {
        if (mIsThirdPartyIntent) return
        binding.mainMenu.useViperVisionBar(R.drawable.ic_files_vector, getString(R.string.vv_grid_view)) {
            showAllMedia()
        }
    }

    private fun setupOptionsMenu() {
        val menuId = if (mIsThirdPartyIntent) {
            R.menu.menu_main_intent
        } else {
            R.menu.menu_main
        }

        binding.mainMenu.requireToolbar().inflateMenu(menuId)
        if (!mIsThirdPartyIntent) {
            binding.mainMenu.requireToolbar().menu.apply {
                settingsAsDotsButton(R.id.settings)
                // Viper Vision: new folder as a + button next to ⋮.
                findItem(R.id.create_new_folder)?.apply {
                    setIcon(org.fossify.commons.R.drawable.ic_plus_vector)
                    setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            }
        }
        binding.mainMenu.toggleHideOnScroll(!config.scrollHorizontally)
        binding.mainMenu.setupMenu()

        binding.mainMenu.onSearchOpenListener = {
            if (config.searchAllFilesByDefault) {
                launchSearchActivity()
            }
        }

        binding.mainMenu.onSearchTextChangedListener = { text ->
            setupAdapter(mDirsIgnoringSearch, text)
            binding.directoriesRefreshLayout.isEnabled =
                text.isEmpty() && config.enablePullToRefresh
            binding.directoriesSwitchSearching.beVisibleIf(text.isNotEmpty())
        }

        binding.mainMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.sort -> showSortingDialog()
                R.id.filter -> showFilterMediaDialog()
                R.id.open_camera -> launchCamera()
                R.id.show_all -> showAllMedia()
                R.id.change_view_type -> changeViewType()
                R.id.temporarily_show_hidden -> tryToggleTemporarilyShowHidden()
                R.id.stop_showing_hidden -> tryToggleTemporarilyShowHidden()
                R.id.temporarily_show_excluded -> tryToggleTemporarilyShowExcluded()
                R.id.stop_showing_excluded -> tryToggleTemporarilyShowExcluded()
                R.id.create_new_folder -> createNewFolder()
                R.id.open_recycle_bin -> openRecycleBin()
                R.id.column_count -> changeColumnCount()
                R.id.set_as_default_folder -> setAsDefaultFolder()
                R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                R.id.settings -> launchSettings()
                R.id.vv_ai_search -> openAiSearch()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
        setupViperVisionBar()
    }

    private fun getRecyclerAdapter() = binding.directoriesGrid.adapter as? DirectoryAdapter

    private fun storeStateVariables() {
        mStoredTextColor = getProperTextColor()
        mStoredPrimaryColor = getProperPrimaryColor()
        config.apply {
            mStoredAnimateGifs = animateGifs
            mStoredCropThumbnails = cropThumbnails
            mStoredScrollHorizontally = scrollHorizontally
            mStoredStyleString = "$folderStyle$showFolderMediaCount$limitFolderTitle"
        }
    }

    private fun startNewPhotoFetcher() {
        val photoFetcher = NewPhotoFetcher()
        if (!photoFetcher.isScheduled(applicationContext)) {
            photoFetcher.scheduleJob(applicationContext)
        }
    }

    private fun removeTempFolder() {
        if (config.tempFolderPath.isNotEmpty()) {
            val newFolder = File(config.tempFolderPath)
            if (getDoesFilePathExist(newFolder.absolutePath) && newFolder.isDirectory) {
                if (
                    newFolder.getProperSize(true) == 0L
                    && newFolder.getFileCount(true) == 0
                    && newFolder.list()?.isEmpty() == true
                ) {
                    toast(
                        String.format(
                            getString(org.fossify.commons.R.string.deleting_folder),
                            config.tempFolderPath
                        ), Toast.LENGTH_LONG
                    )
                    tryDeleteFileDirItem(newFolder.toFileDirItem(applicationContext), true, true)
                }
            }
            config.tempFolderPath = ""
        }
    }

    private fun checkOTGPath() {
        ensureBackgroundThread {
            if (!config.wasOTGHandled && hasPermission(getPermissionToRequest()) && hasOTGConnected() && config.OTGPath.isEmpty()) {
                getStorageDirectories().firstOrNull {
                    it.trimEnd('/') != internalStoragePath
                            && it.trimEnd('/') != sdCardPath
                }?.apply {
                    config.wasOTGHandled = true
                    val otgPath = trimEnd('/')
                    config.OTGPath = otgPath
                    config.addIncludedFolder(otgPath)
                }
            }
        }
    }

    private fun checkDefaultSpamFolders() {
        if (!config.spamFoldersChecked) {
            val spamFolders = arrayListOf(
                "/storage/emulated/0/Android/data/com.facebook.orca/files/stickers"
            )

            val OTGPath = config.OTGPath
            spamFolders.forEach {
                if (getDoesFilePathExist(it, OTGPath)) {
                    config.addExcludedFolder(it)
                }
            }
            config.spamFoldersChecked = true
        }
    }

    private fun tryLoadGallery() {
        // avoid calling anything right after granting the permission, it will be called from onResume()
        val wasMissingPermission =
            config.appRunCount == 1 && !hasAllPermissions(getPermissionsToRequest())
        handleMediaPermissions {
            if (wasMissingPermission) {
                return@handleMediaPermissions
            }

            if (!mWasDefaultFolderChecked) {
                openDefaultFolder()
                mWasDefaultFolderChecked = true
            }

            checkOTGPath()
            checkDefaultSpamFolders()

            if (config.showAll) {
                showAllMedia()
            } else {
                getDirectories()
            }

            setupLayoutManager()
        }
    }

    private fun getDirectories() {
        if (mIsGettingDirs) {
            return
        }

        mShouldStopFetching = true
        mIsGettingDirs = true
        val getImages = mIsPickImageIntent || mIsGetImageContentIntent
        val getVideos = mIsPickVideoIntent || mIsGetVideoContentIntent

        getCachedDirectories(getVideos && !getImages, getImages && !getVideos) {
            gotDirectories(addTempFolderIfNeeded(it))
        }
    }

    private fun launchSearchActivity() {
        hideKeyboard()
        Intent(this, SearchActivity::class.java).apply {
            startActivity(this)
        }

        binding.mainMenu.postDelayed({
            binding.mainMenu.closeSearch()
        }, 500)
    }

    private fun showSortingDialog() {
        ChangeSortingDialog(this, true, false) {
            binding.directoriesGrid.adapter = null
            if (config.directorySorting and SORT_BY_DATE_MODIFIED != 0 || config.directorySorting and SORT_BY_DATE_TAKEN != 0) {
                getDirectories()
            } else {
                ensureBackgroundThread {
                    gotDirectories(getCurrentlyDisplayedDirs())
                }
            }

            getRecyclerAdapter()?.directorySorting = config.directorySorting
        }
    }

    private fun showFilterMediaDialog() {
        FilterMediaDialog(this) {
            mShouldStopFetching = true
            binding.directoriesRefreshLayout.isRefreshing = true
            binding.directoriesGrid.adapter = null
            getDirectories()
        }
    }

    private fun showAllMedia() {
        config.showAll = true
        Intent(this, MediaActivity::class.java).apply {
            putExtra(DIRECTORY, "")

            if (mIsThirdPartyIntent) {
                handleMediaIntent(this)
            } else {
                hideKeyboard()
                startActivity(this)
                finish()
            }
        }
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this, true) {
            refreshMenuItems()
            setupLayoutManager()
            binding.directoriesGrid.adapter = null
            setupAdapter(getRecyclerAdapter()?.dirs ?: mDirs)
        }
    }

    private fun tryToggleTemporarilyShowHidden() {
        if (config.temporarilyShowHidden) {
            toggleTemporarilyShowHidden(false)
        } else {
            if (isRPlus() && !isExternalStorageManager()) {
                GrantAllFilesDialog(this)
            } else {
                handleHiddenFolderPasswordProtection {
                    toggleTemporarilyShowHidden(true)
                }
            }
        }
    }

    private fun toggleTemporarilyShowHidden(show: Boolean) {
        mLoadedInitialPhotos = false
        config.temporarilyShowHidden = show
        binding.directoriesGrid.adapter = null
        getDirectories()
        refreshMenuItems()
    }

    private fun tryToggleTemporarilyShowExcluded() {
        if (config.temporarilyShowExcluded) {
            toggleTemporarilyShowExcluded(false)
        } else {
            handleExcludedFolderPasswordProtection {
                toggleTemporarilyShowExcluded(true)
            }
        }
    }

    private fun toggleTemporarilyShowExcluded(show: Boolean) {
        mLoadedInitialPhotos = false
        config.temporarilyShowExcluded = show
        binding.directoriesGrid.adapter = null
        getDirectories()
        refreshMenuItems()
    }

    override fun deleteFolders(folders: ArrayList<File>) {
        val fileDirItems = folders
            .asSequence()
            .filter { it.isDirectory }
            .map { FileDirItem(it.absolutePath, it.name, true) }
            .toMutableList() as ArrayList<FileDirItem>

        when {
            fileDirItems.isEmpty() -> return
            fileDirItems.size == 1 -> {
                try {
                    toast(
                        String.format(
                            getString(org.fossify.commons.R.string.deleting_folder),
                            fileDirItems.first().name
                        )
                    )
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }

            else -> {
                val baseString = if (config.useRecycleBin && !config.tempSkipRecycleBin) {
                    org.fossify.commons.R.plurals.moving_items_into_bin
                } else {
                    org.fossify.commons.R.plurals.delete_items
                }

                toast(
                    msg = resources.getQuantityString(
                        baseString, fileDirItems.size, fileDirItems.size
                    )
                )
            }
        }

        val itemsToDelete = ArrayList<FileDirItem>()
        val filter = config.filterMedia
        val showHidden = config.shouldShowHidden
        fileDirItems.filter { it.isDirectory }.forEach {
            val files = File(it.path).listFiles()
            files?.filter {
                it.absolutePath.isMediaFile()
                        && (showHidden || !it.name.startsWith('.'))
                        && ((it.isImageFast() && filter and TYPE_IMAGES != 0)
                        || (it.isVideoFast() && filter and TYPE_VIDEOS != 0)
                        || (it.isGif() && filter and TYPE_GIFS != 0)
                        || (it.isRawFast() && filter and TYPE_RAWS != 0)
                        || (it.isSvg() && filter and TYPE_SVGS != 0))
            }?.mapTo(itemsToDelete) { it.toFileDirItem(applicationContext) }
        }

        if (config.useRecycleBin && !config.tempSkipRecycleBin) {
            val pathsToDelete = ArrayList<String>()
            itemsToDelete.mapTo(pathsToDelete) { it.path }

            movePathsInRecycleBin(pathsToDelete) {
                if (it) {
                    deleteFilteredFileDirItems(itemsToDelete, folders)
                } else {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
            }
        } else {
            deleteFilteredFileDirItems(itemsToDelete, folders)
        }
    }

    private fun deleteFilteredFileDirItems(
        fileDirItems: ArrayList<FileDirItem>,
        folders: ArrayList<File>
    ) {
        val OTGPath = config.OTGPath
        deleteFiles(fileDirItems) {
            runOnUiThread {
                refreshItems()
            }

            ensureBackgroundThread {
                folders.filter { !getDoesFilePathExist(it.absolutePath, OTGPath) }.forEach {
                    directoryDB.deleteDirPath(it.absolutePath)
                }

                if (config.deleteEmptyFolders) {
                    folders.filter {
                        !it.absolutePath.isDownloadsFolder()
                                && it.isDirectory
                                && it.toFileDirItem(this).getProperFileCount(this, true) == 0
                    }
                        .forEach {
                            tryDeleteFileDirItem(it.toFileDirItem(this), true, true)
                        }
                }
            }
        }
    }

    private fun setupLayoutManager() {
        if (config.viewTypeFolders == VIEW_TYPE_GRID) {
            setupGridLayoutManager()
        } else {
            setupListLayoutManager()
        }

        (binding.directoriesRefreshLayout.layoutParams as RelativeLayout.LayoutParams)
            .addRule(RelativeLayout.BELOW, R.id.directories_switch_searching)
    }

    private fun setupGridLayoutManager() {
        val layoutManager = binding.directoriesGrid.layoutManager as MyGridLayoutManager
        if (config.scrollHorizontally) {
            layoutManager.orientation = RecyclerView.HORIZONTAL
            binding.directoriesRefreshLayout.layoutParams =
                RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
        } else {
            layoutManager.orientation = RecyclerView.VERTICAL
            binding.directoriesRefreshLayout.layoutParams =
                RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
        }

        layoutManager.spanCount = config.dirColumnCnt
    }

    private fun setupListLayoutManager() {
        val layoutManager = binding.directoriesGrid.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        layoutManager.orientation = RecyclerView.VERTICAL
        binding.directoriesRefreshLayout.layoutParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        mZoomListener = null
    }

    private fun initZoomListener() {
        if (config.viewTypeFolders == VIEW_TYPE_GRID) {
            val layoutManager = binding.directoriesGrid.layoutManager as MyGridLayoutManager
            mZoomListener = object : MyRecyclerView.MyZoomListener {
                override fun zoomIn() {
                    if (layoutManager.spanCount > 1) {
                        reduceColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }

                override fun zoomOut() {
                    if (layoutManager.spanCount < MAX_COLUMN_COUNT) {
                        increaseColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }
            }
        } else {
            mZoomListener = null
        }
    }

    private fun createNewFolder() {
        FilePickerDialog(this, internalStoragePath, false, config.shouldShowHidden, false, true) {
            CreateNewFolderDialog(this, it) {
                config.tempFolderPath = it
                ensureBackgroundThread {
                    gotDirectories(addTempFolderIfNeeded(getCurrentlyDisplayedDirs()))
                }
            }
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..MAX_COLUMN_COUNT) {
            items.add(
                RadioItem(
                    id = i,
                    title = resources.getQuantityString(
                        org.fossify.commons.R.plurals.column_counts, i, i
                    )
                )
            )
        }

        val currentColumnCount =
            (binding.directoriesGrid.layoutManager as MyGridLayoutManager).spanCount
        RadioGroupDialog(this, items, currentColumnCount) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.dirColumnCnt = newColumnCount
                columnCountChanged()
            }
        }
    }

    private fun increaseColumnCount() {
        config.dirColumnCnt += 1
        columnCountChanged()
    }

    private fun reduceColumnCount() {
        config.dirColumnCnt -= 1
        columnCountChanged()
    }

    private fun columnCountChanged() {
        (binding.directoriesGrid.layoutManager as MyGridLayoutManager).spanCount =
            config.dirColumnCnt
        refreshMenuItems()
        getRecyclerAdapter()?.apply {
            notifyItemRangeChanged(0, dirs.size)
        }
    }

    private fun isPickImageIntent(intent: Intent): Boolean {
        return isPickIntent(intent) && (hasImageContentData(intent) || isImageType(intent))
    }

    private fun isPickVideoIntent(intent: Intent): Boolean {
        return isPickIntent(intent) && (hasVideoContentData(intent) || isVideoType(intent))
    }

    private fun isPickIntent(intent: Intent): Boolean {
        return intent.action == Intent.ACTION_PICK
    }

    private fun isGetContentIntent(intent: Intent): Boolean {
        return intent.action == Intent.ACTION_GET_CONTENT && intent.type != null
    }

    private fun anyExtraMimeTypeStartingWith(intent: Intent, mimeTypePrefix: String): Boolean {
        return intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
            ?.any { it.startsWith(mimeTypePrefix) } == true
    }

    private fun isGetImageContentIntent(intent: Intent): Boolean {
        return isGetContentIntent(intent)
                && (intent.type!!.startsWith("image/")
                || intent.type == Images.Media.CONTENT_TYPE
                || anyExtraMimeTypeStartingWith(intent, "image/"))
    }

    private fun isGetVideoContentIntent(intent: Intent): Boolean {
        return isGetContentIntent(intent)
                && (intent.type!!.startsWith("video/")
                || intent.type == Video.Media.CONTENT_TYPE
                || anyExtraMimeTypeStartingWith(intent, "video/"))
    }

    private fun isGetAnyContentIntent(intent: Intent): Boolean {
        return isGetContentIntent(intent) && intent.type == "*/*"
    }

    private fun isSetWallpaperIntent(intent: Intent?): Boolean {
        return intent?.action == Intent.ACTION_SET_WALLPAPER
    }

    private fun hasImageContentData(intent: Intent): Boolean {
        return intent.data == Images.Media.EXTERNAL_CONTENT_URI
                || intent.data == Images.Media.INTERNAL_CONTENT_URI
    }

    private fun hasVideoContentData(intent: Intent): Boolean {
        return intent.data == Video.Media.EXTERNAL_CONTENT_URI
                || intent.data == Video.Media.INTERNAL_CONTENT_URI
    }

    private fun isImageType(intent: Intent): Boolean {
        return (intent.type?.startsWith("image/") == true
                || intent.type == Images.Media.CONTENT_TYPE)
    }

    private fun isVideoType(intent: Intent): Boolean {
        return (intent.type?.startsWith("video/") == true
                || intent.type == Video.Media.CONTENT_TYPE)
    }

    private fun fillExtraOutput(resultData: Intent): Uri? {
        val file = File(resultData.data!!.path!!)
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        try {
            val output = intent.extras!!.get(MediaStore.EXTRA_OUTPUT) as Uri
            inputStream = FileInputStream(file)
            outputStream = contentResolver.openOutputStream(output)
            inputStream.copyTo(outputStream!!)
        } catch (e: SecurityException) {
            showErrorToast(e)
        } catch (ignored: FileNotFoundException) {
            return getFilePublicUri(file, BuildConfig.APPLICATION_ID)
        } finally {
            inputStream?.close()
            outputStream?.close()
        }

        return null
    }

    private fun fillPickedPaths(resultData: Intent, resultIntent: Intent) {
        val paths = resultData.extras!!.getStringArrayList(PICKED_PATHS)
        val uris = paths!!
            .map { getFilePublicUri(File(it), BuildConfig.APPLICATION_ID) } as ArrayList
        val clipData = ClipData(
            "Attachment",
            arrayOf("image/*", "video/*"),
            ClipData.Item(uris.removeAt(0))
        )

        uris.forEach {
            clipData.addItem(ClipData.Item(it))
        }

        resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        resultIntent.clipData = clipData
    }

    private fun fillIntentPath(resultData: Intent, resultIntent: Intent) {
        val data = resultData.data
        val path = if (data.toString().startsWith("/")) data.toString() else data!!.path
        val uri = getFilePublicUri(File(path!!), BuildConfig.APPLICATION_ID)
        val type = path.getMimeType()
        resultIntent.setDataAndTypeAndNormalize(uri, type)
        resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun itemClicked(path: String) {
        // Viper Vision: the Locked album opens after the fingerprint, face or screen lock.
        if (org.fossify.gallery.helpers.LockedAlbum.isLocked(path)) {
            org.fossify.gallery.helpers.LockedAlbum.unlock(this) {
                Intent(this, MediaActivity::class.java).apply {
                    putExtra(SKIP_AUTHENTICATION, true)
                    putExtra(DIRECTORY, path)
                    handleMediaIntent(this)
                }
            }
            return
        }
        handleLockedFolderOpening(path) { success ->
            if (success) {
                Intent(this, MediaActivity::class.java).apply {
                    putExtra(SKIP_AUTHENTICATION, true)
                    putExtra(DIRECTORY, path)
                    handleMediaIntent(this)
                }
            }
        }
    }

    private fun handleMediaIntent(intent: Intent) {
        hideKeyboard()
        intent.apply {
            if (mIsSetWallpaperIntent) {
                putExtra(SET_WALLPAPER_INTENT, true)
                startActivityForResult(this, PICK_WALLPAPER)
            } else {
                putExtra(GET_IMAGE_INTENT, mIsPickImageIntent || mIsGetImageContentIntent)
                putExtra(GET_VIDEO_INTENT, mIsPickVideoIntent || mIsGetVideoContentIntent)
                putExtra(GET_ANY_INTENT, mIsGetAnyContentIntent)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, mAllowPickingMultiple)
                startActivityForResult(this, PICK_MEDIA)
            }
        }
    }

    private fun gotDirectories(newDirs: ArrayList<Directory>) {
        mIsGettingDirs = false
        mShouldStopFetching = false

        // if hidden item showing is disabled but all Favorite items are hidden, hide the Favorites folder
        if (!config.shouldShowHidden) {
            val favoritesFolder = newDirs.firstOrNull { it.areFavorites() }
            if (
                favoritesFolder != null
                && favoritesFolder.tmb.getFilenameFromPath().startsWith('.')
            ) {
                newDirs.remove(favoritesFolder)
            }
        }

        val dirs = getSortedDirectories(newDirs)
        if (config.groupDirectSubfolders) {
            mDirs = dirs.clone() as ArrayList<Directory>
        }

        var isPlaceholderVisible = dirs.isEmpty()

        runOnUiThread {
            checkPlaceholderVisibility(dirs)
            setupAdapter(dirs.clone() as ArrayList<Directory>)
        }

        // cached folders have been loaded, recheck folders one by one starting with the first displayed
        mLastMediaFetcher?.shouldStop = true
        mLastMediaFetcher = MediaFetcher(applicationContext)
        val getImages = mIsPickImageIntent || mIsGetImageContentIntent
        val getVideos = mIsPickVideoIntent || mIsGetVideoContentIntent
        val getImagesOnly = getImages && !getVideos
        val getVideosOnly = getVideos && !getImages
        val favoritePaths = getFavoritePaths()
        val hiddenString = getString(R.string.hidden)
        val albumCovers = config.parseAlbumCovers()
        val includedFolders = config.includedFolders
        val noMediaFolders = getNoMediaFoldersSync()
        val tempFolderPath = config.tempFolderPath
        val getProperFileSize = config.directorySorting and SORT_BY_SIZE != 0
        val dirPathsToRemove = ArrayList<String>()
        val lastModifieds = mLastMediaFetcher!!.getLastModifieds()
        val dateTakens = mLastMediaFetcher!!.getDateTakens()

        if (
            config.showRecycleBinAtFolders
            && !config.showRecycleBinLast
            && !dirs.map { it.path }.contains(RECYCLE_BIN)
        ) {
            try {
                if (mediaDB.getDeletedMediaCount() > 0) {
                    val recycleBin = Directory().apply {
                        path = RECYCLE_BIN
                        name = getString(org.fossify.commons.R.string.recycle_bin)
                        location = LOCATION_INTERNAL
                    }

                    dirs.add(0, recycleBin)
                }
            } catch (ignored: Exception) {
            }
        }

        if (dirs.map { it.path }.contains(FAVORITES)) {
            if (mediaDB.getFavoritesCount() > 0) {
                val favorites = Directory().apply {
                    path = FAVORITES
                    name = getString(org.fossify.commons.R.string.favorites)
                    location = LOCATION_INTERNAL
                }

                dirs.add(0, favorites)
            }
        }

        // fetch files from MediaStore only, unless the app has the MANAGE_EXTERNAL_STORAGE permission on Android 11+
        val android11Files = mLastMediaFetcher?.getAndroid11FolderMedia(
            isPickImage = getImagesOnly,
            isPickVideo = getVideosOnly,
            favoritePaths = favoritePaths,
            getFavoritePathsOnly = false,
            getProperDateTaken = true,
            dateTakens = dateTakens
        )
        try {
            for (directory in dirs) {
                if (mShouldStopFetching || isDestroyed || isFinishing) {
                    return
                }

                val sorting = config.getFolderSorting(directory.path)
                val grouping = config.getFolderGrouping(directory.path)
                val getProperDateTaken = config.directorySorting and SORT_BY_DATE_TAKEN != 0
                        || sorting and SORT_BY_DATE_TAKEN != 0
                        || grouping and GROUP_BY_DATE_TAKEN_DAILY != 0
                        || grouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0

                val getProperLastModified =
                    config.directorySorting and SORT_BY_DATE_MODIFIED != 0
                            || sorting and SORT_BY_DATE_MODIFIED != 0
                            || grouping and GROUP_BY_LAST_MODIFIED_DAILY != 0
                            || grouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0

                val curMedia = mLastMediaFetcher!!.getFilesFrom(
                    curPath = directory.path,
                    isPickImage = getImagesOnly,
                    isPickVideo = getVideosOnly,
                    getProperDateTaken = getProperDateTaken,
                    getProperLastModified = getProperLastModified,
                    getProperFileSize = getProperFileSize,
                    favoritePaths = favoritePaths,
                    getVideoDurations = false,
                    lastModifieds = lastModifieds,
                    dateTakens = dateTakens,
                    android11Files = android11Files
                )

                val newDir = if (curMedia.isEmpty()) {
                    if (directory.path != tempFolderPath) {
                        dirPathsToRemove.add(directory.path)
                    }
                    directory
                } else {
                    createDirectoryFromMedia(
                        path = directory.path,
                        curMedia = curMedia,
                        albumCovers = albumCovers,
                        hiddenString = hiddenString,
                        includedFolders = includedFolders,
                        getProperFileSize = getProperFileSize,
                        noMediaFolders = noMediaFolders
                    )
                }

                // we are looping through the already displayed folders looking for changes, do not do anything if nothing changed
                if (directory.copy(subfoldersCount = 0, subfoldersMediaCount = 0) == newDir) {
                    continue
                }

                directory.apply {
                    tmb = newDir.tmb
                    name = newDir.name
                    mediaCnt = newDir.mediaCnt
                    modified = newDir.modified
                    taken = newDir.taken
                    this@apply.size = newDir.size
                    types = newDir.types
                    sortValue = getDirectorySortingValue(curMedia, path, name, size, mediaCnt)
                }

                setupAdapter(dirs)

                // update directories and media files in the local db, delete invalid items. Intentionally creating a new thread
                updateDBDirectory(directory)
                if (!directory.isRecycleBin() && !directory.areFavorites()) {
                    Thread {
                        try {
                            mediaDB.insertAll(curMedia)
                        } catch (ignored: Exception) {
                        }
                    }.start()
                }

                if (!directory.isRecycleBin()) {
                    getCachedMedia(directory.path, getVideosOnly, getImagesOnly) {
                        val mediaToDelete = ArrayList<Medium>()
                        it.forEach {
                            if (!curMedia.contains(it)) {
                                val medium = it as? Medium
                                val path = medium?.path
                                if (path != null) {
                                    mediaToDelete.add(medium)
                                }
                            }
                        }
                        mediaDB.deleteMedia(*mediaToDelete.toTypedArray())
                    }
                }
            }

            if (dirPathsToRemove.isNotEmpty()) {
                val dirsToRemove = dirs.filter { dirPathsToRemove.contains(it.path) }
                dirsToRemove.forEach {
                    directoryDB.deleteDirPath(it.path)
                }
                dirs.removeAll(dirsToRemove)
                setupAdapter(dirs)
            }
        } catch (ignored: Exception) {
        }

        val foldersToScan = mLastMediaFetcher!!.getFoldersToScan()
        foldersToScan.remove(FAVORITES)
        foldersToScan.add(0, FAVORITES)
        if (config.showRecycleBinAtFolders) {
            if (foldersToScan.contains(RECYCLE_BIN)) {
                foldersToScan.remove(RECYCLE_BIN)
                foldersToScan.add(0, RECYCLE_BIN)
            } else {
                foldersToScan.add(0, RECYCLE_BIN)
            }
        } else {
            foldersToScan.remove(RECYCLE_BIN)
        }

        dirs.filterNot { it.path == RECYCLE_BIN || it.path == FAVORITES }.forEach {
            foldersToScan.remove(it.path)
        }

        // check the remaining folders which were not cached at all yet
        for (folder in foldersToScan) {
            if (mShouldStopFetching || isDestroyed || isFinishing) {
                return
            }

            val sorting = config.getFolderSorting(folder)
            val grouping = config.getFolderGrouping(folder)
            val getProperDateTaken = config.directorySorting and SORT_BY_DATE_TAKEN != 0
                    || sorting and SORT_BY_DATE_TAKEN != 0
                    || grouping and GROUP_BY_DATE_TAKEN_DAILY != 0
                    || grouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0

            val getProperLastModified = config.directorySorting and SORT_BY_DATE_MODIFIED != 0
                    || sorting and SORT_BY_DATE_MODIFIED != 0
                    || grouping and GROUP_BY_LAST_MODIFIED_DAILY != 0
                    || grouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0

            val newMedia = mLastMediaFetcher!!.getFilesFrom(
                curPath = folder,
                isPickImage = getImagesOnly,
                isPickVideo = getVideosOnly,
                getProperDateTaken = getProperDateTaken,
                getProperLastModified = getProperLastModified,
                getProperFileSize = getProperFileSize,
                favoritePaths = favoritePaths,
                getVideoDurations = false,
                lastModifieds = lastModifieds,
                dateTakens = dateTakens,
                android11Files = android11Files
            )

            if (newMedia.isEmpty()) {
                continue
            }

            if (isPlaceholderVisible) {
                isPlaceholderVisible = false
                runOnUiThread {
                    binding.directoriesEmptyPlaceholder.beGone()
                    binding.directoriesEmptyPlaceholder2.beGone()
                    binding.directoriesFastscroller.beVisible()
                }
            }

            val newDir = createDirectoryFromMedia(
                path = folder,
                curMedia = newMedia,
                albumCovers = albumCovers,
                hiddenString = hiddenString,
                includedFolders = includedFolders,
                getProperFileSize = getProperFileSize,
                noMediaFolders = noMediaFolders
            )
            dirs.add(newDir)
            setupAdapter(dirs)

            // make sure to create a new thread for these operations, dont just use the common bg thread
            Thread {
                try {
                    directoryDB.insert(newDir)
                    if (folder != RECYCLE_BIN && folder != FAVORITES) {
                        mediaDB.insertAll(newMedia)
                    }
                } catch (ignored: Exception) {
                }
            }.start()
        }

        mLoadedInitialPhotos = true
        if (config.appRunCount > 1) {
            checkLastMediaChanged()
        }

        runOnUiThread {
            binding.directoriesRefreshLayout.isRefreshing = false
            checkPlaceholderVisibility(dirs)
        }

        checkInvalidDirectories(dirs)
        if (mDirs.size > 50) {
            excludeSpamFolders()
        }

        val excludedFolders = config.excludedFolders
        val everShownFolders = config.everShownFolders.toMutableSet() as HashSet<String>

        // do not add excluded folders and their subfolders at everShownFolders
        dirs.filter { dir ->
            return@filter !excludedFolders.any { dir.path.startsWith(it) }
        }.mapTo(everShownFolders) { it.path }

        try {
            // scan the internal storage from time to time for new folders
            if (config.appRunCount == 1 || config.appRunCount % 30 == 0) {
                everShownFolders.addAll(getFoldersWithMedia(config.internalStoragePath))
            }

            // catch some extreme exceptions like too many everShownFolders for storing, shouldnt really happen
            config.everShownFolders = everShownFolders
        } catch (e: Exception) {
            config.everShownFolders = HashSet()
        }

        mDirs = dirs.clone() as ArrayList<Directory>
    }

    private fun setAsDefaultFolder() {
        config.defaultFolder = ""
        refreshMenuItems()
    }

    private fun openDefaultFolder() {
        if (config.defaultFolder.isEmpty()) {
            return
        }

        val defaultDir = File(config.defaultFolder)

        if ((!defaultDir.exists() || !defaultDir.isDirectory) && (config.defaultFolder != RECYCLE_BIN && config.defaultFolder != FAVORITES)) {
            config.defaultFolder = ""
            return
        }

        Intent(this, MediaActivity::class.java).apply {
            putExtra(DIRECTORY, config.defaultFolder)
            handleMediaIntent(this)
        }
    }

    private fun checkPlaceholderVisibility(dirs: ArrayList<Directory>) {
        binding.directoriesEmptyPlaceholder.beVisibleIf(dirs.isEmpty() && mLoadedInitialPhotos)
        binding.directoriesEmptyPlaceholder2.beVisibleIf(dirs.isEmpty() && mLoadedInitialPhotos)

        if (binding.mainMenu.isSearchOpen) {
            binding.directoriesEmptyPlaceholder.text =
                getString(org.fossify.commons.R.string.no_items_found)
            binding.directoriesEmptyPlaceholder2.beGone()
        } else if (dirs.isEmpty() && config.filterMedia == getDefaultFileFilter()) {
            if (isRPlus() && !isExternalStorageManager()) {
                binding.directoriesEmptyPlaceholder.text =
                    getString(org.fossify.commons.R.string.no_items_found)
                binding.directoriesEmptyPlaceholder2.beGone()
            } else {
                binding.directoriesEmptyPlaceholder.text = getString(R.string.no_media_add_included)
                binding.directoriesEmptyPlaceholder2.text = getString(R.string.add_folder)
            }

            binding.directoriesEmptyPlaceholder2.setOnClickListener {
                showAddIncludedFolderDialog {
                    refreshItems()
                }
            }
        } else {
            binding.directoriesEmptyPlaceholder.text = getString(R.string.no_media_with_filters)
            binding.directoriesEmptyPlaceholder2.text =
                getString(R.string.change_filters_underlined)

            binding.directoriesEmptyPlaceholder2.setOnClickListener {
                showFilterMediaDialog()
            }
        }

        binding.directoriesEmptyPlaceholder2.underlineText()
        binding.directoriesFastscroller.beVisibleIf(binding.directoriesEmptyPlaceholder.isGone())
    }

    private fun setupAdapter(
        dirs: ArrayList<Directory>,
        textToSearch: String = binding.mainMenu.getCurrentQuery(),
        forceRecreate: Boolean = false
    ) {
        val currAdapter = binding.directoriesGrid.adapter
        val distinctDirs = dirs
            .distinctBy { it.path.getDistinctPath() }
            .toMutableList() as ArrayList<Directory>

        val sortedDirs = getSortedDirectories(distinctDirs)
        var dirsToShow = getDirsToShow(
            dirs = sortedDirs,
            allDirs = mDirs,
            currentPathPrefix = mCurrentPathPrefix
        ).clone() as ArrayList<Directory>
        // Viper Vision: the Locked album, first on the albums page (not when picking for another app).
        if (mCurrentPathPrefix.isEmpty() && !isPickIntent(intent) && !isGetAnyContentIntent(intent)) {
            dirsToShow.removeAll { org.fossify.gallery.helpers.LockedAlbum.isLocked(it.path) }
            dirsToShow.add(0, org.fossify.gallery.helpers.LockedAlbum.directory(getString(R.string.vv_locked_album)))
        }

        if (currAdapter == null || forceRecreate) {
            mDirsIgnoringSearch = dirs
            initZoomListener()
            DirectoryAdapter(
                this,
                dirsToShow,
                this,
                binding.directoriesGrid,
                isPickIntent(intent) || isGetAnyContentIntent(intent),
                binding.directoriesRefreshLayout
            ) {
                val clickedDir = it as Directory
                val path = clickedDir.path
                if (clickedDir.subfoldersCount == 1 || !config.groupDirectSubfolders) {
                    if (path != config.tempFolderPath) {
                        itemClicked(path)
                    }
                } else {
                    mCurrentPathPrefix = path
                    mOpenedSubfolders.add(path)
                    setupAdapter(mDirs, "")
                }
            }.apply {
                setupZoomListener(mZoomListener)
                runOnUiThread {
                    binding.directoriesGrid.adapter = this
                    setupScrollDirection()

                    if (config.viewTypeFolders == VIEW_TYPE_LIST && areSystemAnimationsEnabled) {
                        binding.directoriesGrid.scheduleLayoutAnimation()
                    }
                }
            }
        } else {
            runOnUiThread {
                if (textToSearch.isNotEmpty()) {
                    dirsToShow = dirsToShow
                        .filter { it.name.contains(textToSearch, true) }
                        .sortedBy { !it.name.startsWith(textToSearch, true) }
                        .toMutableList() as ArrayList
                }
                checkPlaceholderVisibility(dirsToShow)

                (binding.directoriesGrid.adapter as? DirectoryAdapter)?.updateDirs(dirsToShow)
            }
        }

        // recyclerview sometimes becomes empty at init/update, triggering an invisible refresh like this seems to work fine
        binding.directoriesGrid.postDelayed({
            binding.directoriesGrid.scrollBy(0, 0)
        }, 500)
    }

    private fun setupScrollDirection() {
        val scrollHorizontally =
            config.scrollHorizontally && config.viewTypeFolders == VIEW_TYPE_GRID
        binding.directoriesFastscroller.setScrollVertically(!scrollHorizontally)
    }

    private fun checkInvalidDirectories(dirs: ArrayList<Directory>) {
        val invalidDirs = ArrayList<Directory>()
        val OTGPath = config.OTGPath
        dirs.filter { !it.areFavorites() && !it.isRecycleBin() }.forEach {
            if (!getDoesFilePathExist(it.path, OTGPath)) {
                invalidDirs.add(it)
            } else if (it.path != config.tempFolderPath && (!isRPlus() || isExternalStorageManager())) {
                // avoid calling file.list() or listfiles() on Android 11+, it became way too slow
                val children = if (isPathOnOTG(it.path)) {
                    getOTGFolderChildrenNames(it.path)
                } else {
                    File(it.path).list()?.asList()
                }

                val hasMediaFile = children?.any {
                    it != null && (
                            it.isMediaFile()
                                    || (it.startsWith("img_", true)
                                    && File(it).isDirectory)
                            )
                } == true

                if (!hasMediaFile) {
                    invalidDirs.add(it)
                }
            }
        }

        if (getFavoritePaths().isEmpty()) {
            val favoritesFolder = dirs.firstOrNull { it.areFavorites() }
            if (favoritesFolder != null) {
                invalidDirs.add(favoritesFolder)
            }
        }

        if (config.useRecycleBin) {
            try {
                val binFolder = dirs.firstOrNull { it.path == RECYCLE_BIN }
                if (binFolder != null && mediaDB.getDeletedMedia().isEmpty()) {
                    invalidDirs.add(binFolder)
                }
            } catch (ignored: Exception) {
            }
        }

        if (invalidDirs.isNotEmpty()) {
            dirs.removeAll(invalidDirs)
            setupAdapter(dirs)
            invalidDirs.forEach {
                try {
                    directoryDB.deleteDirPath(it.path)
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun getCurrentlyDisplayedDirs() = getRecyclerAdapter()?.dirs ?: ArrayList()

    private fun setupLatestMediaId() {
        ensureBackgroundThread {
            if (hasPermission(PERMISSION_READ_STORAGE)) {
                mLatestMediaId = getLatestMediaId()
                mLatestMediaDateId = getLatestMediaByDateId()
            }
        }
    }

    private fun checkLastMediaChanged() {
        if (isDestroyed) {
            return
        }

        mLastMediaHandler.postDelayed({
            ensureBackgroundThread {
                val mediaId = getLatestMediaId()
                val mediaDateId = getLatestMediaByDateId()
                if (mLatestMediaId != mediaId || mLatestMediaDateId != mediaDateId) {
                    mLatestMediaId = mediaId
                    mLatestMediaDateId = mediaDateId
                    runOnUiThread {
                        getDirectories()
                    }
                } else {
                    mLastMediaHandler.removeCallbacksAndMessages(null)
                    checkLastMediaChanged()
                }
            }
        }, LAST_MEDIA_CHECK_PERIOD)
    }

    private fun checkRecycleBinItems() {
        if (config.useRecycleBin && config.lastBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000) {
            config.lastBinCheck = System.currentTimeMillis()
            Handler().postDelayed({
                ensureBackgroundThread {
                    try {
                        val filesToDelete = mediaDB.getOldRecycleBinItems(
                            System.currentTimeMillis() - MONTH_MILLISECONDS
                        )
                        filesToDelete.forEach {
                            if (File(it.path.replaceFirst(RECYCLE_BIN, recycleBinPath)).delete()) {
                                mediaDB.deleteMediumPath(it.path)
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            }, 3000L)
        }
    }

    // exclude probably unwanted folders, for example facebook stickers are split between hundreds of separate folders like
    // /storage/emulated/0/Android/data/com.facebook.orca/files/stickers/175139712676531/209575122566323
    // /storage/emulated/0/Android/data/com.facebook.orca/files/stickers/497837993632037/499671223448714
    private fun excludeSpamFolders() {
        ensureBackgroundThread {
            try {
                val internalPath = internalStoragePath
                val checkedPaths = ArrayList<String>()
                val oftenRepeatedPaths = ArrayList<String>()
                val paths = mDirs
                    .map { it.path.removePrefix(internalPath) }
                    .toMutableList() as ArrayList<String>
                paths.forEach {
                    val parts = it.split("/")
                    var currentString = ""
                    for (i in 0 until parts.size) {
                        currentString += "${parts[i]}/"

                        if (!checkedPaths.contains(currentString)) {
                            val cnt = paths.count { it.startsWith(currentString) }
                            if (cnt > 50 && currentString.startsWith("/Android/data", true)) {
                                oftenRepeatedPaths.add(currentString)
                            }
                        }

                        checkedPaths.add(currentString)
                    }
                }

                val substringToRemove = oftenRepeatedPaths.filter {
                    val path = it
                    it == "/" || oftenRepeatedPaths.any { it != path && it.startsWith(path) }
                }

                oftenRepeatedPaths.removeAll(substringToRemove)
                val OTGPath = config.OTGPath
                oftenRepeatedPaths.forEach {
                    val file = File("$internalPath/$it")
                    if (getDoesFilePathExist(file.absolutePath, OTGPath)) {
                        config.addExcludedFolder(file.absolutePath)
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun getFoldersWithMedia(path: String): HashSet<String> {
        val folders = HashSet<String>()
        try {
            val files = File(path).listFiles()
            if (files != null) {
                files.sortBy { !it.isDirectory }
                for (file in files) {
                    if (file.isDirectory && !file.startsWith("${config.internalStoragePath}/Android")) {
                        folders.addAll(getFoldersWithMedia(file.absolutePath))
                    } else if (file.isFile && file.isMediaFile()) {
                        folders.add(file.parent ?: "")
                        break
                    }
                }
            }
        } catch (ignored: Exception) {
        }

        return folders
    }

    override fun refreshItems() {
        getDirectories()
    }

    override fun recheckPinnedFolders() {
        ensureBackgroundThread {
            gotDirectories(movePinnedDirectoriesToFront(getCurrentlyDisplayedDirs()))
        }
    }

    override fun updateDirectories(directories: ArrayList<Directory>) {
        ensureBackgroundThread {
            storeDirectoryItems(directories)
            removeInvalidDBDirectories()
        }
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
