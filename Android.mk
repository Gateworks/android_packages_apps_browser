LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_STATIC_JAVA_LIBRARIES := \
        android-common \
        guava \
        android-support-v13 \
        android-support-v4 \

LOCAL_SRC_FILES := \
        $(call all-java-files-under, src) \
        src/com/android/browser/EventLogTags.logtags

LOCAL_PACKAGE_NAME := Browser

LOCAL_PROGUARD_FLAG_FILES := proguard.flags

LOCAL_EMMA_COVERAGE_FILTER := *,-com.android.common.*

# We need the sound recorder for the Media Capture API.
#LOCAL_REQUIRED_MODULES := SoundRecorder

LOCAL_OVERRIDES_PACKAGES := \
	LatinIME DeskClock FusedLocation WallpaperCropper MusicFx \
	GateworksDemo MagicSmokeWallpapers LegacyCamera FSLOta WfdSink wfd \
	VideoEditor FSLProfileApp FSLProfileService TSCalibration Gallery2 \
	LiveWallpapers LiveWallpapersPicker VisualizationWallpapers CubeLiveWallpapers \
	Calendar MmsService Music SharedStorageBackup TeleService VpnDialogs \
	TelephonyProvider CalendarProvider SoundRecorder Telecom BackupRestoreConfirmation \
	Development BasicDreams CaptivePortalLogin DownloadProviderUi Exchange2 \
	PacProcessor PicoTts PrintSpooler Provision QuickSearchBox SpeechRecorder \
	Clock Downloads Search Calculator Calender Camera Camera2 Email Contacts Gallery Messaging \
	Home Launcher3 Launcher2

# Inject security files for Browser Lock Task Mode
$(shell mkdir -p "$(TARGET_OUT)/../data/system/")
$(shell cp $(LOCAL_PATH)/device_* "$(TARGET_OUT)/../data/system/")

include $(BUILD_PACKAGE)
