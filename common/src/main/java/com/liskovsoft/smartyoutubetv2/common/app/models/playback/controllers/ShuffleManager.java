package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.util.Log;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ShuffleManager {
    private List<Integer> shuffleOrder = null;
    private int shufflePos = 0;

    @Nullable
    private Video pendingShuffleNext;

    private int globalPlaylistSize = 0;

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
                        " startPos=0 currentIdx=" + currentIndex);
    }

    private void reshuffleKeepingCurrent() {
        int currentIdx = shuffleOrder.get(shufflePos);

        shuffleOrder.remove(Integer.valueOf(currentIdx));
        Collections.shuffle(shuffleOrder);
        shuffleOrder.add(0, currentIdx);

        shufflePos = 0;

        Log.d("SHUFFLE",
                "reshuffleKeepingCurrent - NEW SHUFFLE CYCLE order=" + shuffleOrder);
    }

    /*public void ensureInitialized(int size, int currentIndex) {
        if (shuffleOrder == null || shuffleOrder.size() != size) {
            initShuffle(size, currentIndex);
            pendingShuffleNext = null;
        }
    }*/

    public boolean isInitialized(int size) {
        return shuffleOrder != null && shuffleOrder.size() == size;
    }

    public void ensureInitialized(int size, int currentIndex) {
        if (!isInitialized(size)) {
            initShuffle(size, currentIndex);
            pendingShuffleNext = null;
        }
    }

    public int getNextIndex(int size, int currentIndex) {
        ensureInitialized(size, currentIndex);

        int nextPos = shufflePos + 1;

        if (nextPos >= shuffleOrder.size()) {
            reshuffleKeepingCurrent();
            nextPos = shufflePos + 1;
        }

        return shuffleOrder.get(nextPos);
    }

    public void advance() {
        shufflePos++;
        pendingShuffleNext = null;
    }

    @Nullable
    public Video getPendingShuffleNext() {
        return pendingShuffleNext;
    }

    public void setPendingShuffleNext(@Nullable Video video) {
        pendingShuffleNext = video;
    }

    public void reset() {
        if (shuffleOrder != null) {
            shuffleOrder.clear();
        }

        shuffleOrder = null;
        shufflePos = 0;
        pendingShuffleNext = null;
        globalPlaylistSize = 0;
    }

    public int getGlobalPlaylistSize() {
        return globalPlaylistSize;
    }

    public void setGlobalPlaylistSize(int size) {
        globalPlaylistSize = size;
    }

    public int getNextPosition(int size, int currentIndex) {
        ensureInitialized(size, currentIndex);

        int nextPos = shufflePos + 1;

        if (nextPos >= shuffleOrder.size()) {
            reshuffleKeepingCurrent();
            nextPos = shufflePos + 1;
        }

        return nextPos;
    }

    public boolean isEmpty() {
        return shuffleOrder == null || shuffleOrder.isEmpty();
    }

    @Nullable
    public Video consumePending() {
        if (pendingShuffleNext == null) {
            Log.d("SHUFFLE", "CONSUME IS NULL");
            return null;
        }

        Video next = pendingShuffleNext;

        shufflePos++;
        pendingShuffleNext = null;

        Log.d("SHUFFLE",
                "consumePending - CONSUME | new shufflePos=" + shufflePos +
                        " title=" + next.title);

        return next;
    }

    @Nullable
    public Video createNextRequest(Video currentVideo) {
        if (currentVideo == null) {
            return null;
        }

        int size;

        if (currentVideo.playlistInfo != null) {
            size = currentVideo.playlistInfo.getSize();
            globalPlaylistSize = size;
        } else {
            size = globalPlaylistSize;
        }

        if (size <= 1) {
            return null;
        }

        int currentIndex = currentVideo.playlistInfo.getCurrentIndex();

        int nextIdx = getNextIndex(size, currentIndex);

        Video request = new Video();
        request.playlistId = currentVideo.playlistId;
        request.playlistIndex = nextIdx;

        return request;
    }
}
