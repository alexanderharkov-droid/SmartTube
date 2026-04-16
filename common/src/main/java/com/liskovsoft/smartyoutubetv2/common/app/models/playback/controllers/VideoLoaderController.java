package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.os.Build.VERSION;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.ServiceManager;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.VideoActionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import io.reactivex.disposables.Disposable;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VideoLoaderController extends BasePlayerController {
    private static final String TAG = VideoLoaderController.class.getSimpleName();
    private static final int MIN_SHUFFLE_SIZE = 30;
    private final Playlist mPlaylist;
    private Video mPendingVideo;
    private SuggestionsController mSuggestionsController;
    private ErrorFixerController mErrorFixerController;
    private Disposable mFormatInfoAction;
    private final Runnable mReloadVideo = () -> {
        getMainController().onNewVideo(getVideo());
    };
    private final Runnable mLoadNext = this::loadNext;
    private final Runnable mMetadataSync = () -> {
        if (getPlayer() != null) {
            waitMetadataSync(getVideo(), false);
        }
    };
    private final Runnable mRestartEngine = () -> {
        if (getPlayer() != null) {
            getPlayer().restartEngine(); // properly save position of the current track
        }
    };
    private final Runnable mOnApplyPlaybackMode = () -> {
        if (getPlayer() != null && getPlayer().getPositionMs() >= getPlayer().getDurationMs()) {
            applyPlaybackMode(getPlaybackMode());
        }
    };
    private final Runnable mShowProgressBar = () -> {
        if (getPlayer() != null) {
            getPlayer().showProgressBar(true);
        }
    };

    public VideoLoaderController() {
        Log.d("SHUFFLE", "VideoLoaderController CREATED");
        mPlaylist = Playlist.instance();
    }

// ** //
    private List<Integer> shuffleOrder = null;
    private int shufflePos = 0;
    //private String shufflePlaylistId = null;
    // кеширано „следващо“, за да няма разминаване
    @Nullable
    private Video pendingShuffleNext;
    private int globalPlaylistSize = 0;

    @Override
    public void onInit() {
        Log.d("SHUFFLE", "ENTER onInit");
        mSuggestionsController = getController(SuggestionsController.class);
        mErrorFixerController = getController(ErrorFixerController.class);
        mSleepTimerStartMs = System.currentTimeMillis();
    }

    @Override
    public void onNewVideo(Video item) {
        if (item == null) {
            return;
        }

        item.isShuffled = false;

        if (!item.fromQueue && !item.belongsToPlaybackQueue()) {
            mPlaylist.add(item);
        } else {
            item.fromQueue = false;
        }

        if (getPlayer() != null && getPlayer().isEngineInitialized()) { // player is initialized
            // Fix improperly resized video after exit from PIP (Device Formuler Z8 Pro)
            loadVideo(item); // force play immediately even the same video
        } else {
            mPendingVideo = item;
        }
    }

    @Override
    public void onEngineInitialized() {
        if (getPlayer() == null) {
            return;
        }
        
        loadVideo(Helpers.firstNonNull(mPendingVideo, getVideo()));
        getPlayer().setButtonState(R.id.action_repeat, getPlayerData().getPlaybackMode());
        mPendingVideo = null;
    }

    @Override
    public void onEngineReleased() {
        disposeActions();
    }

    @Override
    public void onVideoLoaded(Video video) {
        if (getPlayer() == null) {
            return;
        }
        
        getPlayer().setButtonState(R.id.action_repeat, video.finishOnEnded ? PlayerConstants.PLAYBACK_MODE_CLOSE : getPlayerData().getPlaybackMode());
        // Can't set title at this point
        //checkSleepTimer();
    }

    @Override
    public boolean onPreviousClicked() {
        loadPrevious();

        return true;
    }

    @Override
    public boolean onNextClicked() {
        if (getGeneralData().isChildModeEnabled()) {
            onPlayEnd();
        } else {
            loadNext();
        }

        return true;
    }

    public void loadPrevious() {
        if (getPlayer() == null) {
            return;
        }

        openVideoInt(mSuggestionsController.getPrevious());

        if (getPlayerTweaksData().isPlayerUiOnNextEnabled()) {
            getPlayer().showOverlay(true);
        }
    }

    // Original
    /*public void loadNext() {
        Log.d("SHUFFLE", "ENTER loadNext Original");
        if (getPlayer() == null || getVideo() == null) {
            return;
        }

        Video next = mSuggestionsController.getNext();

        if (next != null) {
            openVideoInt(next);
        } else {
            waitMetadataSync(getVideo(), true);
        }

        if (getPlayerTweaksData().isPlayerUiOnNextEnabled()) {
            getPlayer().showOverlay(true);
        }
    }*/

// ** //
    public void loadNext() {
        Log.d("SHUFFLE", "ENTER loadNext");
        try {

            if (getPlayer() == null || getVideo() == null) {
                return;
            }

            Video next;

            if (getPlayerData().getPlaybackMode() == PlayerConstants.PLAYBACK_MODE_SHUFFLE) {
                next = consumeNextShuffleVideo();
            } else {
                next = mSuggestionsController.getNext();
            }

            if (next != null) {
                next.isShuffled = getVideo().isShuffled;
                openVideoInt(next);
            } else {
                waitMetadataSync(getVideo(), true);
            }

            if (getPlayerTweaksData().isPlayerUiOnNextEnabled()) {
                getPlayer().showOverlay(true);
            }
        } catch (Exception e) {
            Log.d("SHUFFLE","Error in loadNext - "+e.toString());
        }
    }


    @Override
    public void onPlayEnd() {
        if (getPlayer() == null) {
            return;
        }

        // Stop the playback if the user is browsing options or reading comments
        int playbackMode = getPlaybackMode();
        if (getAppDialogPresenter().isDialogShown() && !getAppDialogPresenter().isOverlay() && playbackMode != PlayerConstants.PLAYBACK_MODE_ONE) {
            getAppDialogPresenter().setOnFinish(mOnApplyPlaybackMode);
        } else {
            applyPlaybackMode(playbackMode);
        }
    }

    @Override
    public void onSuggestionItemClicked(Video item) {
        openVideoInt(item);

        if (getPlayer() != null)
            getPlayer().showControls(false);
    }

    @Override
    public boolean onKeyDown(int keyCode) {
        Utils.removeCallbacks(mRestartEngine);

        return false;
    }

    /**
     * Force load and play!
     */
    private void loadVideo(Video item) {
        if (getPlayer() != null && item != null) {
            mPlaylist.setCurrent(item);
            getPlayer().setVideo(item);
            getPlayer().resetPlayerState();
            loadFormatInfo(item);
        }
    }

    /**
     * Force load suggestions.
     */
    private void loadSuggestions(Video item) {
        if (getPlayer() == null) {
            return;
        }

        if (item != null) {
            mPlaylist.setCurrent(item);
            getPlayer().setVideo(item);
            mSuggestionsController.loadSuggestions(item);
        }
    }

    private void waitMetadataSync(Video current, boolean showLoadingMsg) {
        if (current == null) {
            return;
        }

        if (current.nextMediaItem != null) {
            openVideoInt(Video.from(current.nextMediaItem));
        } else if (!current.isSynced) { // Maybe there's nothing left. E.g. when casting from phone
            // Wait in a loop while suggestions have been loaded...
            if (showLoadingMsg) {
                MessageHelpers.showMessage(getContext(), R.string.wait_data_loading);
            }
            // Short videos next fix (suggestions aren't loaded yet)
            boolean isEnded = getPlayer() != null && Math.abs(getPlayer().getDurationMs() - getPlayer().getPositionMs()) < 100;
            if (isEnded) {
                Utils.postDelayed(mMetadataSync, 1_000);
            }
        }
    }

    private void loadFormatInfo(Video video) {
        if (getPlayer() == null) {
            return;
        }

        // Fix no progress on next video (the engine may still buffering a bit)
        //getPlayer().showProgressBar(true);
        Utils.post(mShowProgressBar);
        disposeActions();

        ServiceManager service = YouTubeServiceManager.instance();
        MediaItemService mediaItemManager = service.getMediaItemService();
        mFormatInfoAction = mediaItemManager.getFormatInfoObserve(video.videoId)
                .subscribe(this::processFormatInfo,
                           error -> {
                               getPlayer().showProgressBar(false);
                               mErrorFixerController.runFormatErrorAction(error);
                           });
    }

    private void processFormatInfo(MediaItemFormatInfo formatInfo) {
        PlaybackView player = getPlayer();

        if (player == null || getVideo() == null) {
            return;
        }

        String bgImageUrl = null;

        getVideo().sync(formatInfo);

        // Fix stretched video for a couple milliseconds (before the onVideoSizeChanged gets called)
        applyAspectRatio(formatInfo);

        if (formatInfo.getPaidContentText() != null && getSponsorBlockData().isPaidContentNotificationEnabled()) {
            MessageHelpers.showMessage(getContext(), formatInfo.getPaidContentText());
        }

        if (formatInfo.isUnplayable()) {
            if (isEmbedPlayer()) {
                player.finish();
                return;
            }

            player.setTitle(formatInfo.getPlayabilityReason());
            player.showProgressBar(false);
            mSuggestionsController.loadSuggestions(getVideo());
            bgImageUrl = getVideo().getBackgroundUrl();

            // 18+ video or the video is hidden/removed
            player.showOverlay(true);
            loadNextVideo(5_000);

            //if (formatInfo.isUnknownError()) { // the bot error or the video not available
            //    scheduleRebootAppTimer(5_000);
            //} else { // 18+ video or the video is hidden/removed
            //    scheduleNextVideoTimer(5_000);
            //}
        } else if (acceptAdaptiveFormats(formatInfo) && formatInfo.containsDashFormats()) {
            Log.d(TAG, "Loading regular video in dash format...");

            if (getPlayerTweaksData().isHighBitrateFormatsEnabled() && formatInfo.hasExtendedHlsFormats()) {
                player.openMerged(formatInfo, formatInfo.getHlsManifestUrl());
            } else {
                player.openDash(formatInfo);
            }
        } else if (acceptAdaptiveFormats(formatInfo) && formatInfo.containsSabrFormats() && !formatInfo.isLive()) { // TODO: SABR live not implemented yet
            Log.d(TAG, "Loading video in sabr format...");
            player.openSabr(formatInfo);
        } else if (acceptDashLive(formatInfo)) {
            Log.d(TAG, "Loading live video (current or past live stream) in dash format...");
            player.openDashUrl(formatInfo.getDashManifestUrl());
        } else if (formatInfo.isLive() && formatInfo.containsHlsUrl()) {
            Log.d(TAG, "Loading live video (current or past live stream) in hls format...");
            player.openHlsUrl(formatInfo.getHlsManifestUrl());
        } else if (formatInfo.containsUrlFormats()) {
            Log.d(TAG, "Loading url list video. This is always LQ...");
            player.openUrlList(formatInfo.createUrlList());
        } else {
            Log.d(TAG, "Empty format info received. Seems future live translation. No video data to pass to the player.");
            player.setTitle(formatInfo.getPlayabilityReason());
            player.showProgressBar(false);
            mSuggestionsController.loadSuggestions(getVideo());
            bgImageUrl = getVideo().getBackgroundUrl();
            player.showOverlay(true);
            reloadVideo(30 * 1_000);
        }

        player.showBackground(bgImageUrl); // remove bg (if video playing) or set another bg
    }

    private void reloadVideo(int delayMs) {
        if (getPlayer() == null) {
            return;
        }

        if (getPlayer().isEngineInitialized()) {
            Log.d(TAG, "Reloading the video...");
            Utils.postDelayed(mReloadVideo, delayMs);
        }
    }

    private void loadNextVideo(int delayMs) {
        if (getPlayer() == null) {
            return;
        }

        if (getPlayer().isEngineInitialized()) {
            Log.d(TAG, "Starting the next video...");
            Utils.postDelayed(mLoadNext, delayMs);
        }
    }

    private void restartEngine(int delayMs) {
        if (getPlayer() != null) {
            Log.d(TAG, "Restarting the engine...");
            Utils.postDelayed(mRestartEngine, delayMs);
        }
    }

    private void openVideoInt(Video item) {
        if (item == null) {
            return;
        }

        disposeActions();

        if (item.hasVideo()) {
            // NOTE: Next clicked: instant playback even a mix
            // NOTE: Bypass PIP fullscreen on next caused by startView
            getMainController().onNewVideo(item);
            //getPlayer().showOverlay(true);
        } else {
            VideoActionPresenter.instance(getContext()).apply(item);
        }
    }

    private boolean isActionsRunning() {
        return RxHelper.isAnyActionRunning(mFormatInfoAction);
    }

    private void disposeActions() {
        MediaServiceManager.instance().disposeActions();
        RxHelper.disposeActions(mFormatInfoAction);
        Utils.removeCallbacks(mReloadVideo, mLoadNext, mRestartEngine, mMetadataSync);
    }

    public void restartEngine() {
        restartEngine(1_000);
    }

    public void reloadVideo() {
        reloadVideo(1_000);
    }

    private void applyPlaybackMode(int playbackMode) {
        if (getPlayer() == null) {
            return;
        }

        Video video = getVideo();
        // Fix simultaneous videos loading (e.g. when playback ends and user opens new video)
        if (video == null || isActionsRunning()) {
            return;
        }

        if (isEmbedPlayer()) {
            playbackMode = PlayerConstants.PLAYBACK_MODE_CLOSE;
        }

        switch (playbackMode) {
            case PlayerConstants.PLAYBACK_MODE_REVERSE_LIST:
                if (video.hasPlaylist() || video.belongsToChannelUploads() || video.belongsToChannel()) {
                    VideoGroup group = video.getGroup();
                    if (group != null && group.indexOf(video) != 0) { // stop after first
                        onPreviousClicked();
                    }
                    break;
                }
            case PlayerConstants.PLAYBACK_MODE_ALL:
            case PlayerConstants.PLAYBACK_MODE_SHUFFLE:
                loadNext();
                break;
            case PlayerConstants.PLAYBACK_MODE_ONE:
                if (VERSION.SDK_INT <= 19) {
                    // Fix frozen image on Android 4
                    restartEngine();
                } else {
                    getPlayer().setPositionMs(0);
                }
                break;
            case PlayerConstants.PLAYBACK_MODE_CLOSE:
                // Close player if suggestions not shown
                // Except when playing from queue
                if (mPlaylist.getNext() != null && !getPlayerTweaksData().isQueueRespectsPlaybackMode()) {
                    loadNext();
                } else {
                    AppDialogPresenter dialog = getAppDialogPresenter();
                    if (!getPlayer().isSuggestionsShown() && (!dialog.isDialogShown() || dialog.isOverlay())) {
                        dialog.closeDialog();
                        getPlayer().finishReally();
                    }
                }
                break;
            case PlayerConstants.PLAYBACK_MODE_PAUSE:
                // Stop player after each video.
                // Except when playing from queue
                if (mPlaylist.getNext() != null && !getPlayerTweaksData().isQueueRespectsPlaybackMode()) {
                    loadNext();
                } else {
                    stopPlayback();
                }
                break;
            case PlayerConstants.PLAYBACK_MODE_LIST:
                // if video has a playlist load next or restart playlist
                if (video.hasNextPlaylist() || mPlaylist.getNext() != null) {
                    loadNext();
                } else {
                    //restartPlaylistIfNeeded();
                    stopPlayback();
                }
                break;
            default:
                Log.e(TAG, "Undetected repeat mode " + playbackMode);
                break;
        }
    }

    private void stopPlayback() {
        if (getPlayer() == null) {
            return;
        }

        getPlayer().setPositionMs(getPlayer().getDurationMs());
        getPlayer().setPlayWhenReady(false);
        getPlayer().showSuggestions(true);
    }

    private void restartPlaylistIfNeeded() {
        if (getPlayer() == null || getVideo() == null) {
            return;
        }
        
        VideoGroup group = getVideo().getGroup(); // Get the VideoGroup (playlist)

        if (group != null && !group.isEmpty() && getVideo().belongsToSamePlaylistGroup()) {
            openVideoInt(group.get(0));
        } else {
            Log.e(TAG, "VideoGroup is null or empty. Can't restart playlist.");
            stopPlayback();
        }
    }

    private boolean acceptAdaptiveFormats(MediaItemFormatInfo formatInfo) {
        if (getPlayerData().isLegacyCodecsForced() && formatInfo.containsUrlFormats()) {
            return false;
        }

        if (getPlayerTweaksData().isHlsStreamsForced() && formatInfo.isLive() && formatInfo.containsHlsUrl()) {
            return false;
        }

        // Not enough info for full length live streams
        if (formatInfo.isLive() && formatInfo.getStartTimeMs() == 0) {
            return false;
        }

        // Live dash url doesn't work with None buffer
        //if (formatInfo.isLive() && (getPlayerTweaksData().isDashUrlStreamsForced() || getPlayerData().getVideoBufferType() == PlayerData.BUFFER_NONE)) {
        if (formatInfo.isLive() && getPlayerTweaksData().isDashUrlStreamsForced() && formatInfo.containsDashUrl()) {
            return false;
        }

        if (formatInfo.isLive() && getPlayerTweaksData().isHlsStreamsForced() && formatInfo.containsHlsUrl()) {
            return false;
        }

        return true;
    }

    private boolean acceptDashLive(MediaItemFormatInfo formatInfo) {
        if (getPlayerTweaksData().isHlsStreamsForced() && formatInfo.isLive() && formatInfo.containsHlsUrl()) {
            return false;
        }

        return formatInfo.isLive() && formatInfo.containsDashUrl();
    }

    @Override
    public void onMetadata(MediaItemMetadata metadata) {
        initRandomNext();
    }


    private void initRandomNext() {

    @Override
    public void onPlay() {
        Utils.removeCallbacks(mOnLongBuffering);
    }

    @Override
    public void onPause() {
        Utils.removeCallbacks(mOnLongBuffering);
    }



// ** //
    //private void loadRandomNext() {
    private void initRandomNext() {
        Log.d("SHUFFLE", "ENTER loadRandomNext");
        MediaServiceManager.instance().disposeActions();

        /*if (getPlayer() == null || getPlayerData() == null || getVideo() == null || getVideo().playlistInfo == null ||
                getPlayerData().getPlaybackMode() != PlayerConstants.PLAYBACK_MODE_SHUFFLE) {
            Log.d("SHUFFLE", "loadRandomNext - getPlayer() == null || getPlayerData() == null || getVideo() == null || getVideo().playlistInfo == null ||\n" +
                    "                getPlayerData().getPlaybackMode() != PlayerConstants.PLAYBACK_MODE_SHUFFLE");
            return;
        }*/

        if (getPlayerData().getPlaybackMode() != PlayerConstants.PLAYBACK_MODE_SHUFFLE) return;

        loadNextShuffleStrict();

    }

    private int getPlaybackMode() {
        int playbackMode = getPlayerData().getPlaybackMode();

        Video video = getVideo();
        if (video != null && video.finishOnEnded) {
            playbackMode = PlayerConstants.PLAYBACK_MODE_CLOSE;
        } else if (video != null && video.belongsToShortsGroup() && getPlayerTweaksData().isLoopShortsEnabled()) {
            playbackMode = PlayerConstants.PLAYBACK_MODE_ONE;
        }
        return playbackMode;
    }

    /**
     * Fix stretched video for a couple milliseconds (before the onVideoSizeChanged gets called)
     */
    private void applyAspectRatio(MediaItemFormatInfo formatInfo) {
        if (getPlayer() == null) {
            return;
        }

        // Fix stretched video for a couple milliseconds (before the onVideoSizeChanged gets called)
        if (formatInfo.containsDashFormats()) {
            MediaFormat format = formatInfo.getAdaptiveFormats().get(0);
            int width = format.getWidth();
            int height = format.getHeight();
            boolean isShorts = width < height;
            if (width > 0 && height > 0 && (getPlayerData().getAspectRatio() == PlayerData.ASPECT_RATIO_DEFAULT || isShorts)) {
                getPlayer().setAspectRatio((float) width / height);
            } else {
                getPlayer().setAspectRatio(getPlayerData().getAspectRatio());
            }
        }
    }

    private void preloadNextVideoIfNeeded() {
        if (isEmbedPlayer() || getPlayer() == null || getVideo() == null || getVideo().isLive) {
            return;
        }

        if (getPlayer().getDurationMs() - getPlayer().getPositionMs() < 50_000) {
            MediaServiceManager.instance().loadFormatInfo(mSuggestionsController.getNext(), formatInfo -> {});
        }
    }


    private boolean isSubtitlesEnabled() {
        return getPlayer() != null && !FormatItem.SUBTITLE_NONE.equals(getPlayer().getSubtitleFormat());
    }

    private void disableSubtitles() {
        //if (getVideo() != null) {
        //    getPlayerData().disableSubtitlesPerChannel(getVideo().channelId);
        //}

        getPlayerData().setSubtitlesPerChannelEnabled(false); // Important!
        getPlayerData().setFormat(FormatItem.SUBTITLE_NONE);
    }


    private boolean isPlaybackEnded() {
        if (getPlayer() == null || getVideo() == null) {
            return false;
        }

        return (!getVideo().isLive || getVideo().isLiveEnd)
                && getPlayer().getDurationMs() - getPlayer().getPositionMs() < STREAM_END_THRESHOLD_MS;
    }

    private boolean isOfflineVideo() {
        if (getPlayer() == null || getVideo() == null) {
            return false;
        }

        return !getVideo().isLive && !getVideo().isLiveEnd;
    }


    private void lowerVideoQuality() {
        if (getPlayer() == null) {
            return;
        }

        List<FormatItem> videoFormats = getPlayer().getVideoFormats();

        if (videoFormats == null) {
            return;
        }

        int idx = videoFormats.indexOf(getPlayer().getVideoFormat());
        int nextIdx = idx + 1;

        if (videoFormats.size() > nextIdx) {
            getPlayer().setFormat(videoFormats.get(nextIdx));
        }
    }


// ** //
    private void initShuffle(int size, int currentIndex) {
        shuffleOrder = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            shuffleOrder.add(i);
        }

        shuffleOrder.remove(Integer.valueOf(currentIndex));
        Collections.shuffle(shuffleOrder);
        shuffleOrder.add(0, currentIndex);

        shufflePos = 0;

        Log.d("SHUFFLE",
                "initShuffle | order=" + shuffleOrder +
                        " startPos=0 currentIdx=" + currentIndex + " | title=" + getVideo().title
        );
    }

// ** //
    private void reshuffleKeepingCurrent() {
        int currentIdx = shuffleOrder.get(shufflePos);

        shuffleOrder.remove(Integer.valueOf(currentIdx));
        Collections.shuffle(shuffleOrder);
        shuffleOrder.add(0, currentIdx);

        shufflePos = 0;

        Log.d("SHUFFLE",
                "reshuffleKeepingCurrent - NEW SHUFFLE CYCLE order=" + shuffleOrder
        );
    }


// ** //
    private void ensureShuffleInitialized(int size) {
        if (shuffleOrder == null || shuffleOrder.size() != size) {
            int currentIdx = /*getVideo().playlistIndex;*/getVideo().playlistInfo.getCurrentIndex();
            initShuffle(size, currentIdx);
            pendingShuffleNext = null;
        }
    }

// ** //
    @Nullable
    private Video peekNextShuffleVideo() {
        try {
            if (getPlayer() == null ||
                    getVideo() == null ||
                    getPlayerData() == null ||
                    getPlayerData().getPlaybackMode() != PlayerConstants.PLAYBACK_MODE_SHUFFLE) {
                return null;
            }

            // ако вече е готов → връщаме
            if (pendingShuffleNext != null) {
                return pendingShuffleNext;
            }

            int size = 0;
            if (getVideo().playlistInfo != null) {
                size = getVideo().playlistInfo.getSize();
                globalPlaylistSize = size;
            } else {
                size = globalPlaylistSize;
            }
            if (size <= 1) return null;

            ensureShuffleInitialized(size);

            int nextPos = shufflePos + 1;
            if (nextPos >= shuffleOrder.size()) {
                reshuffleKeepingCurrent();
                nextPos = shufflePos + 1;
            }

            int nextIdx = shuffleOrder.get(nextPos);

            Video request = new Video();
            request.playlistId = getVideo().playlistId;
            request.playlistIndex = nextIdx;

            int finalNextPos = nextPos;
            MediaServiceManager.instance().loadMetadata(request, metadata -> {
                if (metadata == null) {
                    Log.d("SHUFFLE", "SKIP: metadata null");
                    pendingShuffleNext = null;
                    return;
                }

                MediaItem mediaItem = SimpleMediaItem.from(metadata);
                Video candidate = Video.from(mediaItem);

                if (candidate == null) {
                    Log.d("SHUFFLE", "SKIP: invalid video");
                    pendingShuffleNext = null;
                    return;
                }

                pendingShuffleNext = candidate;

                getPlayer().setNextTitle(pendingShuffleNext);

                Log.d("SHUFFLE",
                        "peekNextShuffleVideo - LOADED NEXT | nextPos="+ finalNextPos +" | idx=" + nextIdx +
                                " | title=" + pendingShuffleNext.getTitle());
            });
        } catch (Exception e) {
            Log.d("SHUFFLE","Error in peekNextShuffleVideo - "+e.toString());
        }

        return null; // 🔴 важно!
    }

// ** //
    @Nullable
    private Video consumeNextShuffleVideo() {
        try {
            Video next = peekNextShuffleVideo();
            if (next == null) {
                Log.d("SHUFFLE",
                        "CONSUME IS NULL");
                return null;
            }

            shufflePos++;
            pendingShuffleNext = null;

            Log.d("SHUFFLE",
                    "consumeNextShuffleVideo - CONSUME | new shufflePos=" + shufflePos +
                            " title=" + next.title
            );

            return next;
        } catch (Exception e) {
            Log.d("SHUFFLE","Error in consumeNextShuffleVideo - "+e.toString());
            return null;
        }
    }


// ** //
    private void loadNextShuffleStrict() {
        Log.d("SHUFFLE", "ENTER loadNextShuffleStrict");

        int size = getVideo().playlistInfo != null
                ? getVideo().playlistInfo.getSize()
                : 0;

        if (size <= 1) {
            Log.d("SHUFFLE", "Video is not playable. Loading next.");

            // 🔥 fallback към нормално next
            if (shuffleOrder == null || shuffleOrder.isEmpty()) {
                Log.d("SHUFFLE", "Playlist not ready or too small, fallback");
                fallbackToSequential();
                return;
            }
        }

        Video next = peekNextShuffleVideo();
        if (next == null) return;

        getVideo().nextMediaItem = SimpleMediaItem.from(next);
        getPlayer().setNextTitle(next); // UI hint
    }


// ** //
    private void fallbackToSequential() {
        Log.d("SHUFFLE", "FALLBACK → sequential");

        Video next = mSuggestionsController.getNext();

        if (next != null) {
            openVideoInt(next);
        } else {
            waitMetadataSync(getVideo(), true);
        }
    }

    private void loadNextAutoplay() {
        // тук копираш оригиналния код на SmartTube
        // без промени
        Log.d("SHUFFLE", "ENTER loadNextAutoplay");
        VideoGroup topRow = getPlayer().getSuggestionsByIndex(0); // the playlist row
        if (topRow != null) {
            int currentIdx = topRow.indexOf(getVideo());

            // ** //
            int randomIndex = Utils.getRandomIndex(currentIdx, topRow.getSize());

            /*int randomIndex = getNextShuffleIndexNoRepeat(
                    currentIdx,
                    topRow.getSize(),
                    getVideo()
            );*/


            if (randomIndex != -1) {
                Video nextVideo = topRow.get(randomIndex);
                getVideo().nextMediaItem = SimpleMediaItem.from(nextVideo);
                getPlayer().setNextTitle(nextVideo);
            }

            /*Video next = peekNextShuffleVideo();
            if (next == null) return;

            getPlayer().setNextTitle(next); // UI hint*/
        }
    }

    //adb logcat *:S SHUFFLE:D


}
