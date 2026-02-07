/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.download;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.Analytics;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.lib.image.Image;
//import com.hippo.lib.image.Image1;
import com.hippo.unifile.UniFile;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.lib.yorozuya.ConcurrentPool;
import com.hippo.lib.yorozuya.MathUtils;
import com.hippo.lib.yorozuya.ObjectUtils;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.lib.yorozuya.collect.SparseIJArray;
import com.hippo.lib.yorozuya.collect.SparseJLArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class DownloadManager implements SpiderQueen.OnSpiderListener {

    private static final String TAG = DownloadManager.class.getSimpleName();

    public static final String DOWNLOAD_INFO_FILENAME = ".ehviewer";
    public static final String DOWNLOAD_INFO_HEADER = "gid,token,title,title_jpn,thumb,category,posted,uploader,rating,rated,simple_lang,simple_tags,thumb_width,thumb_height,span_size,span_index,span_group_index,favorite_slot,favorite_name,pages";

    private final Context mContext;

    // All download info list
    private final LinkedList<DownloadInfo> mAllInfoList;
    // All download info map
    private final SparseJLArray<DownloadInfo> mAllInfoMap;
    // label and info list map, without default label info list
    private final Map<String, LinkedList<DownloadInfo>> mMap;

    private final Map<String, Long> mLabelCountMap;
    // All labels without default label
    private final List<DownloadLabel> mLabelList;
    // Store download info with default label
    private final LinkedList<DownloadInfo> mDefaultInfoList;
    // Store download info wait to start
    private final LinkedList<DownloadInfo> mWaitList;

    private final SpeedReminder mSpeedReminder;

    @Nullable
    private DownloadListener mDownloadListener;
    private final List<DownloadInfoListener> mDownloadInfoListeners;

    // 多任务并发下载支持
    private final Map<Long, DownloadInfo> mCurrentTasks = new HashMap<>();
    private final Map<Long, SpiderQueen> mCurrentSpiders = new HashMap<>();
    private final Map<Long, SpiderListenerWrapper> mListenerWrappers = new HashMap<>(); // GID到Listener的映射
    private final Map<SpiderQueen, Long> mSpiderToGidMap = new HashMap<>(); // Spider反向查找GID

    // 保留旧的单任务变量用于兼容性（已废弃）
    @Nullable
    @Deprecated
    private DownloadInfo mCurrentTask;
    @Nullable
    @Deprecated
    private SpiderQueen mCurrentSpider;

    private final ConcurrentPool<NotifyTask> mNotifyTaskPool = new ConcurrentPool<>(5);

    public DownloadManager(Context context) {
        mContext = context;

        // Get all labels
        List<DownloadLabel> labels = EhDB.getAllDownloadLabelList();
        mLabelList = labels;

        // Create list for each label
        HashMap<String, LinkedList<DownloadInfo>> map = new HashMap<>();
        mMap = map;
        for (DownloadLabel label : labels) {
            map.put(label.getLabel(), new LinkedList<>());
        }

        // Create default for non tag
        mDefaultInfoList = new LinkedList<>();

        // Get all info
        List<DownloadInfo> allInfoList = EhDB.getAllDownloadInfo();
        mAllInfoList = new LinkedList<>(allInfoList);

        // Create all info map
        SparseJLArray<DownloadInfo> allInfoMap = new SparseJLArray<>(allInfoList.size() + 10);
        mAllInfoMap = allInfoMap;

        for (int i = 0, n = allInfoList.size(); i < n; i++) {
            DownloadInfo info = allInfoList.get(i);

            if (info.archiveUri != null && info.archiveUri.startsWith("content://")) {
                try {
                    Uri uri = Uri.parse(info.archiveUri);
                    mContext.getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    // Permission might already be taken or URI might be invalid
                    android.util.Log.w("DownloadManager", "Failed to restore URI permission for " + info.archiveUri, e);
                }
            }

            // Add to all info map
            allInfoMap.put(info.gid, info);

            // Add to each label list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list == null) {
                // Can't find the label in label list
                list = new LinkedList<>();
                map.put(info.label, list);
                if (!containLabel(info.label)) {
                    // Add label to DB and list
                    labels.add(EhDB.addDownloadLabel(info.label));
                }
            }
            list.add(info);
        }

        mLabelCountMap = new HashMap<>();

        for (Map.Entry<String, LinkedList<DownloadInfo>> entry : map.entrySet()) {
            mLabelCountMap.put(entry.getKey(), (long) entry.getValue().size());
        }

        mWaitList = new LinkedList<>();
        mSpeedReminder = new SpeedReminder();
        mDownloadInfoListeners = new ArrayList<>();
    }

    public void replaceInfo(DownloadInfo newInfo, DownloadInfo oldInfo) {

        for (int i = 0; i < mAllInfoList.size(); i++) {
            if (oldInfo.gid == mAllInfoList.get(i).gid) {
                mAllInfoList.set(i, newInfo);
                break;
            }
        }
        final List<DownloadInfo> infoList = getInfoListForLabel(oldInfo.label);
        if (infoList != null) {
            for (int i = 0; i < infoList.size(); i++) {
                if (oldInfo.gid == infoList.get(i).gid) {
                    infoList.set(i, newInfo);
                    break;
                }
            }
        }

        mAllInfoMap.remove(oldInfo.gid);
        mAllInfoMap.put(newInfo.gid, newInfo);


        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReplace(newInfo, oldInfo);
        }
    }

    @Nullable
    private LinkedList<DownloadInfo> getInfoListForLabel(String label) {
        if (label == null) {
            return mDefaultInfoList;
        } else {
            return mMap.get(label);
        }
    }

    public boolean containLabel(String label) {
        if (label == null) {
            return false;
        }

        for (DownloadLabel raw : mLabelList) {
            if (label.equals(raw.getLabel())) {
                return true;
            }
        }

        return false;
    }

    public boolean containDownloadInfo(long gid) {
        return mAllInfoMap.indexOfKey(gid) >= 0;
    }

    @NonNull
    public List<DownloadLabel> getLabelList() {
        return mLabelList;
    }

    @Nullable
    public long getLabelCount(String label) {
        try {
            if (mLabelCountMap.containsKey(label)) {
                return mLabelCountMap.get(label);
            } else {
                return 0;
            }
        } catch (NullPointerException e) {
            Analytics.recordException(e);
            return 0;
        }
    }

    public List<DownloadInfo> getAllDownloadInfoList() {
        return mAllInfoList;
    }

    @NonNull
    public List<DownloadInfo> getDefaultDownloadInfoList() {
        return mDefaultInfoList;
//        List<DownloadInfo> infoList = new ArrayList<>();
//        int i = 0;
//        while (infoList.size() < 30000) {
//            if (i == mDefaultInfoList.size()) {
//                i = 0;
//            }
//            infoList.add(mDefaultInfoList.get(i));
//            i++;
//        }
//        return infoList;
    }

    @Nullable
    public List<DownloadInfo> getLabelDownloadInfoList(String label) {
        return mMap.get(label);
    }

    public List<GalleryInfo> getDownloadInfoList() {
        return new ArrayList<>(mAllInfoList);
    }

    @Nullable
    public DownloadInfo getDownloadInfo(long gid) {
        return mAllInfoMap.get(gid);
    }

    @Nullable
    public DownloadInfo getNoneDownloadInfo(long gid) {
        if (mCurrentTask != null && mCurrentTask.gid == gid) {
            // Stop current
            stopCurrentDownloadInternal();
        } else {
            // Remove wait
            for (Iterator<DownloadInfo> iterator = mWaitList.iterator(); iterator.hasNext(); ) {
                DownloadInfo info = iterator.next();
                if (info.gid == gid) {
                    info.state = DownloadInfo.STATE_NONE;
                    // Remove from wait list
                    iterator.remove();
                    break;
                }
            }
        }
        return mAllInfoMap.get(gid);
    }

    public int getDownloadState(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (null != info) {
            return info.state;
        } else {
            return DownloadInfo.STATE_INVALID;
        }
    }

    public void addDownloadInfoListener(@Nullable DownloadInfoListener downloadInfoListener) {
        mDownloadInfoListeners.add(downloadInfoListener);
    }

    public void removeDownloadInfoListener(@Nullable DownloadInfoListener downloadInfoListener) {
        mDownloadInfoListeners.remove(downloadInfoListener);
    }

    public void setDownloadListener(@Nullable DownloadListener listener) {
        mDownloadListener = listener;
    }

    private void ensureDownload() {
        // 获取并发下载数量设置
        int maxConcurrent = Settings.getConcurrentGalleryDownload();

        Log.d(TAG, "ensureDownload: maxConcurrent=" + maxConcurrent + ", current=" + mCurrentTasks.size() + ", waiting=" + mWaitList.size());

        // 检查当前正在下载的任务数
        if (mCurrentTasks.size() >= maxConcurrent) {
            // 已达到最大并发数
            Log.d(TAG, "ensureDownload: Already at max concurrent limit");
            return;
        }

        // 从等待列表中获取下载任务，直到达到最大并发数
        while (mCurrentTasks.size() < maxConcurrent && !mWaitList.isEmpty()) {
            DownloadInfo info = mWaitList.removeFirst();

            // 检查下载信息有效性
            if (info == null || info.gid <= 0) {
                Log.w(TAG, "Invalid download info, skipping");
                continue; // 跳过并尝试下一个
            }

            Log.d(TAG, "ensureDownload: Starting new download gid=" + info.gid + ", title=" + info.title);

            try {
                SpiderQueen spider = SpiderQueen.obtainSpiderQueen(mContext, info, SpiderQueen.MODE_DOWNLOAD);

                // 为每个 Spider 创建独立的 Listener 包装器
                SpiderListenerWrapper listenerWrapper = new SpiderListenerWrapper(info.gid, this);

                // 添加到当前任务映射
                mCurrentTasks.put(info.gid, info);
                mCurrentSpiders.put(info.gid, spider);
                mListenerWrappers.put(info.gid, listenerWrapper);
                mSpiderToGidMap.put(spider, info.gid); // 添加反向映射

                // 兼容旧代码 - 保持第一个任务在旧变量中
                if (mCurrentTask == null) {
                    mCurrentTask = info;
                    mCurrentSpider = spider;
                }

                spider.addOnSpiderListener(listenerWrapper);
                info.state = DownloadInfo.STATE_DOWNLOAD;
                info.speed = -1;
                info.remaining = -1;
                info.total = -1;
                info.finished = 0;
                info.downloaded = 0;
                info.legacy = -1;
                // Update in DB
                EhDB.putDownloadInfo(info);
                // Start speed count
                mSpeedReminder.start();
                // Notify start downloading
                if (mDownloadListener != null) {
                    mDownloadListener.onStart(info);
                }
                // Notify state update
                List<DownloadInfo> list = getInfoListForLabel(info.label);
                if (list != null) {
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onUpdate(info, list, mWaitList);
                    }
                }

                Log.d(TAG, "Started download: gid=" + info.gid + ", current tasks=" + mCurrentTasks.size());
            } catch (Exception e) {
                Log.e(TAG, "Failed to start download for gid: " + info.gid, e);
                Analytics.recordException(e);
                // 标记为失败
                info.state = DownloadInfo.STATE_FAILED;
                EhDB.putDownloadInfo(info);
                // 不需要清理映射，因为这个任务从未被添加
            }
        }

        Log.d(TAG, "ensureDownload: After loop, current tasks=" + mCurrentTasks.size());
    }

    void startDownload(GalleryInfo galleryInfo, @Nullable String label) {
        // 检查是否已在当前下载任务中
        if (mCurrentTasks.containsKey(galleryInfo.gid)) {
            // It is current task
            return;
        }

        // Do nothing in the case of a local compressed file.
        if (galleryInfo instanceof DownloadInfo downloadInfo) {
            if (downloadInfo.archiveUri != null && downloadInfo.archiveUri.startsWith("content://")){
                return;
            }
        }

        // Check in download list
        DownloadInfo info = mAllInfoMap.get(galleryInfo.gid);

        if (info != null) { // Get it in download list
            if (info.state != DownloadInfo.STATE_WAIT) {
                // Set state DownloadInfo.STATE_WAIT
                info.state = DownloadInfo.STATE_WAIT;
                // Add to wait list
                mWaitList.add(info);
                // Update in DB
                EhDB.putDownloadInfo(info);
                // Notify state update
                List<DownloadInfo> list = getInfoListForLabel(info.label);
                if (list != null) {
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onUpdate(info, list, mWaitList);
                    }
                }
                // Make sure download is running
                ensureDownload();
            }
        } else {
            // It is new download info
            info = new DownloadInfo(galleryInfo);
            info.label = label;
            info.state = DownloadInfo.STATE_WAIT;
            info.time = System.currentTimeMillis();

            // Add to label download list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list == null) {
                Log.e(TAG, "Can't find download info list with label: " + label);
                return;
            }
            list.addFirst(info);

            // Add to all download list and map
            mAllInfoList.addFirst(info);
            mAllInfoMap.put(galleryInfo.gid, info);

            // Add to wait list
            mWaitList.add(info);

            // Save to
            EhDB.putDownloadInfo(info);

            // Notify
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onAdd(info, list, list.size() - 1);
            }
            // Make sure download is running
            ensureDownload();

            // Add it to history
            EhDB.putHistoryInfo(info);
        }
    }

    void startRangeDownload(LongList gidList) {
        boolean update = false;
        boolean downloadOrder = Settings.getDownloadOrder();
        if (downloadOrder) {
            for (int i = 0, n = gidList.size(); i < n; i++) {
                long gid = gidList.get(i);
                DownloadInfo info = mAllInfoMap.get(gid);
                if (null == info) {
                    Log.d(TAG, "Can't get download info with gid: " + gid);
                    continue;
                }

                if (info.state == DownloadInfo.STATE_NONE ||
                        info.state == DownloadInfo.STATE_FAILED ||
                        info.state == DownloadInfo.STATE_FINISH) {
                    update = true;
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT;
                    // Add to wait list
                    mWaitList.add(info);
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        } else {
            for (int i = gidList.size(), n = 0; i > n; i--) {
                long gid = gidList.get(i - 1);
                DownloadInfo info = mAllInfoMap.get(gid);
                if (null == info) {
                    Log.d(TAG, "Can't get download info with gid: " + gid);
                    continue;
                }

                if (info.state == DownloadInfo.STATE_NONE ||
                        info.state == DownloadInfo.STATE_FAILED ||
                        info.state == DownloadInfo.STATE_FINISH) {
                    update = true;
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT;
                    // Add to wait list
                    mWaitList.add(info);
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        }


        if (update) {
            // Notify Listener
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdateAll();
            }
            // Ensure download
            ensureDownload();
        }
    }

    void startAllDownload() {
        boolean update = false;
        // Start all STATE_NONE and STATE_FAILED item
        LinkedList<DownloadInfo> allInfoList = mAllInfoList;
        LinkedList<DownloadInfo> waitList = mWaitList;
        boolean downloadOrder = Settings.getDownloadOrder();
        if (downloadOrder) {
            for (DownloadInfo info : allInfoList) {
                if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                    update = true;
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT;
                    // Add to wait list
                    waitList.add(info);
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        } else {
            for (DownloadInfo info : allInfoList) {
                if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                    update = true;
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT;
                    // Add to wait list
                    waitList.addFirst(info);
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        }


        if (update) {
            // Notify Listener
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdateAll();
            }
            // Ensure download
            ensureDownload();
        }
    }

    public void addDownload(List<DownloadInfo> downloadInfoList) {
        for (DownloadInfo info : downloadInfoList) {
            if (containDownloadInfo(info.gid)) {
                // Contain
                continue;
            }

            // Ensure download state
            if (DownloadInfo.STATE_WAIT == info.state ||
                    DownloadInfo.STATE_DOWNLOAD == info.state) {
                info.state = DownloadInfo.STATE_NONE;
            }

            // Add to label download list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (null == list) {
                // Can't find the label in label list
                list = new LinkedList<>();
                mMap.put(info.label, list);
                if (!containLabel(info.label)) {
                    // Add label to DB and list
                    mLabelList.add(EhDB.addDownloadLabel(info.label));
                }
            }
            list.add(info);
            // Sort
            Collections.sort(list, DATE_DESC_COMPARATOR);

            // Add to all download list and map
            mAllInfoList.add(info);
            mAllInfoMap.put(info.gid, info);

            // Save to
            EhDB.putDownloadInfo(info);
        }

        // Sort all download list
        Collections.sort(mAllInfoList, DATE_DESC_COMPARATOR);

        // Notify
        new Handler(Looper.getMainLooper()).post(() -> {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onReload();
            }
        });
    }

    public void addDownloadLabel(List<DownloadLabel> downloadLabelList) {
        for (DownloadLabel label : downloadLabelList) {
            String labelString = label.getLabel();
            if (!containLabel(labelString)) {
                mMap.put(labelString, new LinkedList<>());
                mLabelList.add(EhDB.addDownloadLabel(label));
            }
        }
    }

    public void addDownload(GalleryInfo galleryInfo, @Nullable String label, int state) {
        if (containDownloadInfo(galleryInfo.gid)) {
            // Contain
            return;
        }

        // It is new download info
        DownloadInfo info = new DownloadInfo(galleryInfo);
        info.label = label;
        info.state = state;
        info.time = System.currentTimeMillis();

        // Add to label download list
        LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
        if (!mLabelCountMap.containsKey(label)) {
            mLabelCountMap.put(label, 1L);
        } else {
            long value = mLabelCountMap.get(label) + 1L;
            mLabelCountMap.put(label, value);
        }
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: " + label);
            return;
        }
        list.addFirst(info);

        // Add to all download list and map
        mAllInfoList.addFirst(info);
        mAllInfoMap.put(galleryInfo.gid, info);

        // Save to
        EhDB.putDownloadInfo(info);

        // Notify
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onAdd(info, list, list.size() - 1);
        }
    }

    public void addDownload(GalleryInfo galleryInfo, @Nullable String label) {
        addDownload(galleryInfo, label, DownloadInfo.STATE_NONE);
    }

    public void addDownloadInfo(GalleryInfo galleryInfo, @Nullable String label) {
        if (containDownloadInfo(galleryInfo.gid)) {
            // Contain
            return;
        }

        // It is new download info
        DownloadInfo info = new DownloadInfo(galleryInfo);
        info.label = label;
        info.state = DownloadInfo.STATE_NONE;
        if (info.time == 0) {
            info.time = System.currentTimeMillis();
        }

        // Add to label download list
        LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: " + label);
            return;
        }
        list.addFirst(info);

        // Save to
        EhDB.putDownloadInfo(info);
        mAllInfoMap.put(galleryInfo.gid, info);
    }


    public void stopDownload(long gid) {
        DownloadInfo info = stopDownloadInternal(gid);
        if (info != null) {
            // Update listener
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                for (DownloadInfoListener l : mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList);
                }
            }
            // Ensure download
            ensureDownload();
        }
    }

    void stopCurrentDownload() {
        DownloadInfo info = stopCurrentDownloadInternal();
        if (info != null) {
            // Update listener
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                for (DownloadInfoListener l : mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList);
                }
            }
            // Ensure download
            ensureDownload();
        }
    }

    public void stopRangeDownload(LongList gidList) {
        stopRangeDownloadInternal(gidList);

        // Update listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }

        // Ensure download
        ensureDownload();
    }

    public void stopAllDownload() {
        // Stop all in wait list
        for (DownloadInfo info : mWaitList) {
            info.state = DownloadInfo.STATE_NONE;
            // Update in DB
            EhDB.putDownloadInfo(info);
        }
        mWaitList.clear();

        // Stop all current tasks
        List<Long> gidsToStop = new ArrayList<>(mCurrentTasks.keySet());
        for (Long gid : gidsToStop) {
            stopCurrentDownloadInternal(gid);
        }

        // Notify mDownloadInfoListener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }
    }

    public void deleteDownload(long gid) {
        stopDownloadInternal(gid);
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info != null) {
            // Remove from DB
            EhDB.removeDownloadInfo(info.gid);

            // Remove all list and map
            mAllInfoList.remove(info);
            mAllInfoMap.remove(info.gid);

            // Remove label list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                int index = list.indexOf(info);
                if (index >= 0) {
                    list.remove(info);
                    // Update listener
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onRemove(info, list, index);
                    }
                }
            }

            // Ensure download
            ensureDownload();
        }
    }

    public void deleteRangeDownload(LongList gidList) {
        stopRangeDownloadInternal(gidList);

        for (int i = 0, n = gidList.size(); i < n; i++) {
            long gid = gidList.get(i);
            DownloadInfo info = mAllInfoMap.get(gid);
            if (null == info) {
                Log.d(TAG, "Can't get download info with gid: " + gid);
                continue;
            }

            // Remove from DB
            EhDB.removeDownloadInfo(info.gid);

            // Remove from all info map
            mAllInfoList.remove(info);
            mAllInfoMap.remove(info.gid);

            // Remove from label list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                list.remove(info);
            }
        }

        // Update listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReload();
        }

        // Ensure download
        ensureDownload();
    }

    @SuppressLint("StaticFieldLeak")
    public void resetAllReadingProgress() {
        LinkedList<DownloadInfo> list = new LinkedList<>(mAllInfoList);

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                GalleryInfo galleryInfo = new GalleryInfo();
                for (DownloadInfo downloadInfo : list) {
                    galleryInfo.gid = downloadInfo.gid;
                    galleryInfo.token = downloadInfo.token;
                    galleryInfo.title = downloadInfo.title;
                    galleryInfo.thumb = downloadInfo.thumb;
                    galleryInfo.category = downloadInfo.category;
                    galleryInfo.posted = downloadInfo.posted;
                    galleryInfo.uploader = downloadInfo.uploader;
                    galleryInfo.rating = downloadInfo.rating;

                    UniFile downloadDir = SpiderDen.getGalleryDownloadDir(galleryInfo);
                    if (downloadDir == null) {
                        continue;
                    }
                    UniFile file = downloadDir.findFile(".ehviewer");
                    if (file == null) {
                        continue;
                    }
                    SpiderInfo spiderInfo = SpiderInfo.read(file);
                    if (spiderInfo == null) {
                        continue;
                    }
                    spiderInfo.startPage = 0;

                    try {
                        spiderInfo.write(file.openOutputStream());
                    } catch (IOException e) {
                        Log.e(TAG, "Can't write SpiderInfo", e);
                    }
                }
                return null;
            }
        }.executeOnExecutor(IoThreadPoolExecutor.getInstance());
    }

    // Update in DB
    // Update listener
    // No ensureDownload
    private DownloadInfo stopDownloadInternal(long gid) {
        // Check current tasks
        if (mCurrentTasks.containsKey(gid)) {
            // Stop current task
            return stopCurrentDownloadInternal(gid);
        }

        for (Iterator<DownloadInfo> iterator = mWaitList.iterator(); iterator.hasNext(); ) {
            DownloadInfo info = iterator.next();
            if (info.gid == gid) {
                // Remove from wait list
                iterator.remove();
                // Update state
                info.state = DownloadInfo.STATE_NONE;
                // Update in DB
                EhDB.putDownloadInfo(info);
                return info;
            }
        }
        return null;
    }

    // Update in DB
    // Update mDownloadListener
    // 停止指定GID的当前下载任务
    private DownloadInfo stopCurrentDownloadInternal(long gid) {
        DownloadInfo info = mCurrentTasks.get(gid);
        SpiderQueen spider = mCurrentSpiders.get(gid);
        SpiderListenerWrapper wrapper = mListenerWrappers.get(gid);

        if (info == null) {
            return null;
        }

        // Release spider
        if (spider != null) {
            if (wrapper != null) {
                spider.removeOnSpiderListener(wrapper);
            }
            SpiderQueen.releaseSpiderQueen(spider, SpiderQueen.MODE_DOWNLOAD);
            mSpiderToGidMap.remove(spider); // 清理反向映射
        }

        // 从当前任务映射中移除
        mCurrentTasks.remove(gid);
        mCurrentSpiders.remove(gid);
        mListenerWrappers.remove(gid);

        // 更新兼容性变量
        if (mCurrentTask != null && mCurrentTask.gid == gid) {
            mCurrentTask = null;
            mCurrentSpider = null;
        }

        // 如果所有任务都停止了，停止速度提醒
        if (mCurrentTasks.isEmpty()) {
            mSpeedReminder.stop();
        }

        // Update state
        info.state = DownloadInfo.STATE_NONE;
        // Update in DB
        EhDB.putDownloadInfo(info);
        // Listener
        if (mDownloadListener != null) {
            mDownloadListener.onCancel(info);
        }
        return info;
    }

    // 停止所有当前下载任务（保留用于stopAllDownload）
    @Deprecated
    private DownloadInfo stopCurrentDownloadInternal() {
        // 停止第一个任务（兼容旧代码）
        if (mCurrentTask != null) {
            return stopCurrentDownloadInternal(mCurrentTask.gid);
        }
        // 如果没有旧的任务，停止第一个新任务
        if (!mCurrentTasks.isEmpty()) {
            long firstGid = mCurrentTasks.keySet().iterator().next();
            return stopCurrentDownloadInternal(firstGid);
        }
        return null;
    }

    // Update in DB
    // Update mDownloadListener
    private void stopRangeDownloadInternal(LongList gidList) {
        // Two way
        if (gidList.size() < mWaitList.size()) {
            for (int i = 0, n = gidList.size(); i < n; i++) {
                stopDownloadInternal(gidList.get(i));
            }
        } else {
            // Check current tasks
            for (int i = 0, n = gidList.size(); i < n; i++) {
                long gid = gidList.get(i);
                if (mCurrentTasks.containsKey(gid)) {
                    // Stop current task
                    stopCurrentDownloadInternal(gid);
                }
            }

            // Check all in wait list
            for (Iterator<DownloadInfo> iterator = mWaitList.iterator(); iterator.hasNext(); ) {
                DownloadInfo info = iterator.next();
                if (gidList.contains(info.gid)) {
                    // Remove from wait list
                    iterator.remove();
                    // Update state
                    info.state = DownloadInfo.STATE_NONE;
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        }
    }

    /**
     * @param label Not allow new label
     */
    public void changeLabel(List<DownloadInfo> list, String label) {
        if (null != label && !containLabel(label)) {
            Log.e(TAG, "Not exits label: " + label);
            return;
        }

        List<DownloadInfo> dstList = getInfoListForLabel(label);
        if (dstList == null) {
            Log.e(TAG, "Can't find label with label: " + label);
            return;
        }

        for (DownloadInfo info : list) {
            if (ObjectUtils.equal(info.label, label)) {
                continue;
            }

            List<DownloadInfo> srcList = getInfoListForLabel(info.label);
            if (srcList == null) {
                Log.e(TAG, "Can't find label with label: " + info.label);
                continue;
            }

            srcList.remove(info);
            dstList.add(info);
            info.label = label;
            Collections.sort(dstList, DATE_DESC_COMPARATOR);

            // Save to DB
            EhDB.putDownloadInfo(info);
        }

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReload();
        }
    }

    public void addLabel(String label) {
        if (label == null || containLabel(label)) {
            return;
        }

        mLabelList.add(EhDB.addDownloadLabel(label));
        mMap.put(label, new LinkedList<>());

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateLabels();
        }
    }

    public void addLabelInSyncThread(String label) {
        if (label == null || containLabel(label)) {
            return;
        }

        mLabelList.add(EhDB.addDownloadLabel(label));
        mMap.put(label, new LinkedList<>());
    }

    public void moveLabel(int fromPosition, int toPosition) {
        final DownloadLabel item = mLabelList.remove(fromPosition);
        mLabelList.add(toPosition, item);
        EhDB.moveDownloadLabel(fromPosition, toPosition);

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateLabels();
        }
    }

    public void renameLabel(@NonNull String from, @NonNull String to) {
        // Find in label list
        boolean found = false;
        for (DownloadLabel raw : mLabelList) {
            if (from.equals(raw.getLabel())) {
                found = true;
                raw.setLabel(to);
                // Update in DB
                EhDB.updateDownloadLabel(raw);
                break;
            }
        }
        if (!found) {
            return;
        }

        LinkedList<DownloadInfo> list = mMap.remove(from);
        if (list == null) {
            return;
        }

        // Update info label
        for (DownloadInfo info : list) {
            info.label = to;
            // Update in DB
            EhDB.putDownloadInfo(info);
        }
        // Put list back with new label
        mMap.put(to, list);

        // Notify listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onRenameLabel(from, to);
        }
    }

    public void deleteLabel(@NonNull String label) {
        // Find in label list and remove
        boolean found = false;
        for (Iterator<DownloadLabel> iterator = mLabelList.iterator(); iterator.hasNext(); ) {
            DownloadLabel raw = iterator.next();
            if (label.equals(raw.getLabel())) {
                found = true;
                iterator.remove();
                EhDB.removeDownloadLabel(raw);
                break;
            }
        }
        if (!found) {
            return;
        }

        LinkedList<DownloadInfo> list = mMap.remove(label);
        if (list == null) {
            return;
        }

        // Update info label
        for (DownloadInfo info : list) {
            info.label = null;
            // Update in DB
            EhDB.putDownloadInfo(info);
            mDefaultInfoList.add(info);
        }

        // Sort
        Collections.sort(mDefaultInfoList, DATE_DESC_COMPARATOR);

        // Notify listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onChange();
        }
    }

    boolean isIdle() {
        return mCurrentTasks.isEmpty() && mWaitList.isEmpty();
    }

    @Override
    public void onGetPages(int pages) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnGetPagesData(pages);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onGet509(int index) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnGet509Data(index);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnPageDownloadData(index, contentLength, receivedSize, bytesRead);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onPageSuccess(int index, int finished, int downloaded, int total) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnPageSuccessData(index, finished, downloaded, total);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onPageFailure(int index, String error, int finished, int downloaded, int total) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnPageFailureDate(index, error, finished, downloaded, total);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onFinish(int finished, int downloaded, int total) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnFinishDate(finished, downloaded, total);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onGetImageSuccess(int index, Image image) {
        // Ignore
    }

    @Override
    public void onGetImageFailure(int index, String error) {
        // Ignore
    }

    private class NotifyTask implements Runnable {

        public static final int TYPE_ON_GET_PAGES = 0;
        public static final int TYPE_ON_GET_509 = 1;
        public static final int TYPE_ON_PAGE_DOWNLOAD = 2;
        public static final int TYPE_ON_PAGE_SUCCESS = 3;
        public static final int TYPE_ON_PAGE_FAILURE = 4;
        public static final int TYPE_ON_FINISH = 5;

        private int mType;
        private int mPages;
        private int mIndex;
        private long mContentLength;
        private long mReceivedSize;
        private int mBytesRead;
        @SuppressWarnings("unused")
        private String mError;
        private int mFinished;
        private int mDownloaded;
        private int mTotal;

        public void setOnGetPagesData(int pages) {
            mType = TYPE_ON_GET_PAGES;
            mPages = pages;
        }

        public void setOnGet509Data(int index) {
            mType = TYPE_ON_GET_509;
            mIndex = index;
        }

        public void setOnPageDownloadData(int index, long contentLength, long receivedSize, int bytesRead) {
            mType = TYPE_ON_PAGE_DOWNLOAD;
            mIndex = index;
            mContentLength = contentLength;
            mReceivedSize = receivedSize;
            mBytesRead = bytesRead;
        }

        public void setOnPageSuccessData(int index, int finished, int downloaded, int total) {
            mType = TYPE_ON_PAGE_SUCCESS;
            mIndex = index;
            mFinished = finished;
            mDownloaded = downloaded;
            mTotal = total;
        }

        public void setOnPageFailureDate(int index, String error, int finished, int downloaded, int total) {
            mType = TYPE_ON_PAGE_FAILURE;
            mIndex = index;
            mError = error;
            mFinished = finished;
            mDownloaded = downloaded;
            mTotal = total;
        }

        public void setOnFinishDate(int finished, int downloaded, int total) {
            mType = TYPE_ON_FINISH;
            mFinished = finished;
            mDownloaded = downloaded;
            mTotal = total;
        }

        @Override
        public void run() {
            switch (mType) {
                case TYPE_ON_GET_PAGES: {
                    DownloadInfo info = mCurrentTask;
                    if (info == null) {
                        Log.e(TAG, "Current task is null, but it should not be");
                    } else {
                        info.total = mPages;
                        List<DownloadInfo> list = getInfoListForLabel(info.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList);
                            }
                        }
                    }
                    break;
                }
                case TYPE_ON_GET_509: {
                    if (mDownloadListener != null) {
                        mDownloadListener.onGet509();
                    }
                    break;
                }
                case TYPE_ON_PAGE_DOWNLOAD: {
                    mSpeedReminder.onDownload(mIndex, mContentLength, mReceivedSize, mBytesRead);
                    break;
                }
                case TYPE_ON_PAGE_SUCCESS: {
                    mSpeedReminder.onDone(mIndex);
                    DownloadInfo info = mCurrentTask;
                    if (info == null) {
                        Log.e(TAG, "Current task is null, but it should not be");
                    } else {
                        info.finished = mFinished;
                        info.downloaded = mDownloaded;
                        info.total = mTotal;
                        if (mDownloadListener != null) {
                            mDownloadListener.onGetPage(info);
                        }
                        List<DownloadInfo> list = getInfoListForLabel(info.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList);
                            }
                        }
                    }
                    break;
                }
                case TYPE_ON_PAGE_FAILURE: {
                    mSpeedReminder.onDone(mIndex);
                    DownloadInfo info = mCurrentTask;
                    if (info == null) {
                        Log.e(TAG, "Current task is null, but it should not be");
                    } else {
                        info.finished = mFinished;
                        info.downloaded = mDownloaded;
                        info.total = mTotal;
                        List<DownloadInfo> list = getInfoListForLabel(info.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList);
                            }
                        }
                    }
                    break;
                }
                case TYPE_ON_FINISH: {
                    mSpeedReminder.onFinish();
                    // Download done
                    DownloadInfo info = mCurrentTask;
                    SpiderQueen spider = mCurrentSpider;

                    // Check null
                    if (info == null || spider == null) {
                        Log.e(TAG, "Current stuff is null, but it should not be");
                        break;
                    }

                    long gid = info.gid;

                    // Release spider and remove listener wrapper
                    SpiderListenerWrapper wrapper = mListenerWrappers.get(gid);
                    if (wrapper != null) {
                        spider.removeOnSpiderListener(wrapper);
                    }
                    SpiderQueen.releaseSpiderQueen(spider, SpiderQueen.MODE_DOWNLOAD);

                    // 从映射中移除
                    mCurrentTasks.remove(gid);
                    mCurrentSpiders.remove(gid);
                    mListenerWrappers.remove(gid);
                    mSpiderToGidMap.remove(spider);

                    // 更新兼容性变量 - 如果还有其他任务,设置为第一个任务
                    if (!mCurrentTasks.isEmpty()) {
                        Long firstGid = mCurrentTasks.keySet().iterator().next();
                        mCurrentTask = mCurrentTasks.get(firstGid);
                        mCurrentSpider = mCurrentSpiders.get(firstGid);
                    } else {
                        mCurrentTask = null;
                        mCurrentSpider = null;
                        // 所有任务都完成,停止速度计数
                        mSpeedReminder.stop();
                    }

                    // Update state
                    info.finished = mFinished;
                    info.downloaded = mDownloaded;
                    info.total = mTotal;
                    info.legacy = mTotal - mFinished;

                    // 改进的完成状态判断
                    if (info.legacy == 0 && mFinished == mTotal && mTotal > 0) {
                        // 完全成功
                        info.state = DownloadInfo.STATE_FINISH;
                    } else if (mFinished > 0 && info.legacy < mTotal / 2) {
                        // 大部分成功，仍标记为完成但记录部分失败
                        info.state = DownloadInfo.STATE_FINISH;
                        Log.w(TAG, String.format("Download finished with some failures: %d/%d pages, %d failed",
                                mFinished, mTotal, info.legacy));
                    } else {
                        // 失败或大部分失败
                        info.state = DownloadInfo.STATE_FAILED;
                        Log.e(TAG, String.format("Download failed: %d/%d pages, %d failed",
                                mFinished, mTotal, info.legacy));
                    }

                    // Update in DB
                    EhDB.putDownloadInfo(info);
                    // Notify
                    if (mDownloadListener != null) {
                        mDownloadListener.onFinish(info);
                    }
                    List<DownloadInfo> list = getInfoListForLabel(info.label);
                    if (list != null) {
                        for (DownloadInfoListener l : mDownloadInfoListeners) {
                            l.onUpdate(info, list, mWaitList);
                        }
                    }

                    Log.d(TAG, "Download finished: gid=" + gid + ", remaining tasks=" + mCurrentTasks.size());

                    // Start next download - 这会启动等待队列中的任务
                    ensureDownload();
                    break;
                }
            }

            mNotifyTaskPool.push(this);
        }
    }


    class SpeedReminder implements Runnable {

        private boolean mStop = true;

        private long mBytesRead;
        private long oldSpeed = -1;
        private long mLastUpdateTime = 0;

        private final SparseIJArray mContentLengthMap = new SparseIJArray();
        private final SparseIJArray mReceivedSizeMap = new SparseIJArray();

        // 为每个任务单独追踪字节数和速度
        private final Map<Long, Long> mTaskBytesRead = new HashMap<>();
        private final Map<Long, Long> mTaskOldSpeed = new HashMap<>();
        private final Map<Long, long[]> mTaskSpeedSamples = new HashMap<>();
        private final Map<Long, Integer> mTaskSpeedSampleIndex = new HashMap<>();
        private final Map<Long, Integer> mTaskSpeedSampleCount = new HashMap<>();

        // 使用移动平均来平滑速度计算
        private static final int SPEED_SAMPLE_SIZE = 5;
        private final long[] mSpeedSamples = new long[SPEED_SAMPLE_SIZE];
        private int mSpeedSampleIndex = 0;
        private int mSpeedSampleCount = 0;

        public void start() {
            if (mStop) {
                mStop = false;
                mLastUpdateTime = System.currentTimeMillis();
                mSpeedSampleIndex = 0;
                mSpeedSampleCount = 0;
                SimpleHandler.getInstance().post(this);
            }
        }

        public void stop() {
            if (!mStop) {
                mStop = true;
                mBytesRead = 0;
                oldSpeed = -1;
                mContentLengthMap.clear();
                mReceivedSizeMap.clear();
                mSpeedSampleIndex = 0;
                mSpeedSampleCount = 0;
                mTaskBytesRead.clear();
                mTaskOldSpeed.clear();
                mTaskSpeedSamples.clear();
                mTaskSpeedSampleIndex.clear();
                mTaskSpeedSampleCount.clear();
                SimpleHandler.getInstance().removeCallbacks(this);
            }
        }

        public void onDownload(int index, long contentLength, long receivedSize, int bytesRead) {
            mContentLengthMap.put(index, contentLength);
            mReceivedSizeMap.put(index, receivedSize);
            mBytesRead += bytesRead;

            // 为当前任务累加字节数
            if (mCurrentTask != null) {
                long gid = mCurrentTask.gid;
                long taskBytes = mTaskBytesRead.getOrDefault(gid, 0L);
                mTaskBytesRead.put(gid, taskBytes + bytesRead);
            }
        }

        public void onDone(int index) {
            mContentLengthMap.delete(index);
            mReceivedSizeMap.delete(index);
        }

        public void onFinish() {
            mContentLengthMap.clear();
            mReceivedSizeMap.clear();
        }

        @Override
        public void run() {
            long currentTime = System.currentTimeMillis();
            long timeElapsed = currentTime - mLastUpdateTime;
            if (timeElapsed <= 0) {
                timeElapsed = 2000; // 默认2秒
            }
            mLastUpdateTime = currentTime;

            // 为每个正在下载的任务计算速度
            for (Map.Entry<Long, DownloadInfo> entry : mCurrentTasks.entrySet()) {
                long gid = entry.getKey();
                DownloadInfo info = entry.getValue();

                // 获取这个任务的字节数
                long taskBytes = mTaskBytesRead.getOrDefault(gid, 0L);

                // 计算当前速度 (字节/秒)
                long currentSpeed = (taskBytes * 1000L) / timeElapsed;

                // 初始化速度样本数组
                if (!mTaskSpeedSamples.containsKey(gid)) {
                    mTaskSpeedSamples.put(gid, new long[SPEED_SAMPLE_SIZE]);
                    mTaskSpeedSampleIndex.put(gid, 0);
                    mTaskSpeedSampleCount.put(gid, 0);
                }

                // 使用移动平均平滑速度
                long[] speedSamples = mTaskSpeedSamples.get(gid);
                int sampleIndex = mTaskSpeedSampleIndex.get(gid);
                int sampleCount = mTaskSpeedSampleCount.get(gid);

                speedSamples[sampleIndex] = currentSpeed;
                sampleIndex = (sampleIndex + 1) % SPEED_SAMPLE_SIZE;
                if (sampleCount < SPEED_SAMPLE_SIZE) {
                    sampleCount++;
                }

                mTaskSpeedSampleIndex.put(gid, sampleIndex);
                mTaskSpeedSampleCount.put(gid, sampleCount);

                long speedSum = 0;
                for (int i = 0; i < sampleCount; i++) {
                    speedSum += speedSamples[i];
                }
                long avgSpeed = speedSum / sampleCount;

                // 使用平滑算法
                long taskOldSpeed = mTaskOldSpeed.getOrDefault(gid, -1L);
                long newSpeed;
                if (taskOldSpeed == -1) {
                    newSpeed = avgSpeed;
                } else {
                    newSpeed = (long) MathUtils.lerp(taskOldSpeed, avgSpeed, 0.6f);
                }
                mTaskOldSpeed.put(gid, newSpeed);
                info.speed = Math.max(0, newSpeed);

                // 计算剩余时间 - 改进算法
                if (info.total <= 0 || info.downloaded < 0) {
                    info.remaining = -1;
                } else if (newSpeed <= 0) {
                    info.remaining = 300L * 24L * 60L * 60L * 1000L; // 300 days
                } else {
                    int downloadingCount = 0;
                    long downloadingContentLengthSum = 0;
                    long remainingBytes = 0;

                    // 计算当前正在下载的文件的剩余大小
                    for (int i = 0, n = Math.max(mContentLengthMap.size(), mReceivedSizeMap.size()); i < n; i++) {
                        long contentLength = mContentLengthMap.valueAt(i);
                        long receivedSize = mReceivedSizeMap.valueAt(i);
                        if (contentLength > 0) {
                            downloadingCount++;
                            downloadingContentLengthSum += contentLength;
                            remainingBytes += Math.max(0, contentLength - receivedSize);
                        }
                    }

                    // 估算未下载文件的大小
                    int remainingFiles = info.total - info.downloaded - downloadingCount;
                    if (downloadingCount > 0 && remainingFiles > 0) {
                        long avgFileSize = downloadingContentLengthSum / downloadingCount;
                        remainingBytes += avgFileSize * remainingFiles;
                    }

                    // 计算剩余时间(毫秒)
                    if (remainingBytes > 0 && newSpeed > 0) {
                        info.remaining = (remainingBytes * 1000L) / newSpeed;
                    } else {
                        info.remaining = -1;
                    }
                }

                if (mDownloadListener != null) {
                    mDownloadListener.onDownload(info);
                }
                List<DownloadInfo> list = getInfoListForLabel(info.label);
                if (list != null) {
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onUpdate(info, list, mWaitList);
                    }
                }

                // 重置这个任务的字节计数
                mTaskBytesRead.put(gid, 0L);
            }

            mBytesRead = 0;

            if (!mStop) {
                SimpleHandler.getInstance().postDelayed(this, 2000);
            }
        }
    }

    /**
     * Spider Listener 包装器 - 用于区分不同任务的回调
     * 每个 Spider 有独立的 Listener 实例,记住对应的 GID
     */
    private class SpiderListenerWrapper implements SpiderQueen.OnSpiderListener {
        private final long mGid;
        private final DownloadManager mManager;

        public SpiderListenerWrapper(long gid, DownloadManager manager) {
            this.mGid = gid;
            this.mManager = manager;
        }

        @Override
        public void onGetPages(int pages) {
            // 确保使用正确的任务
            DownloadInfo info = mCurrentTasks.get(mGid);
            if (info != null) {
                // 临时设置为 mCurrentTask 以兼容现有代码
                DownloadInfo oldTask = mCurrentTask;
                mCurrentTask = info;
                mManager.onGetPages(pages);
                mCurrentTask = oldTask;
            }
        }

        @Override
        public void onGet509(int index) {
            mManager.onGet509(index);
        }

        @Override
        public void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead) {
            // 确保使用正确的任务
            DownloadInfo info = mCurrentTasks.get(mGid);
            if (info != null) {
                DownloadInfo oldTask = mCurrentTask;
                mCurrentTask = info;
                mManager.onPageDownload(index, contentLength, receivedSize, bytesRead);
                mCurrentTask = oldTask;
            }
        }

        @Override
        public void onPageSuccess(int index, int finished, int downloaded, int total) {
            // 确保使用正确的任务
            DownloadInfo info = mCurrentTasks.get(mGid);
            if (info != null) {
                DownloadInfo oldTask = mCurrentTask;
                mCurrentTask = info;
                mManager.onPageSuccess(index, finished, downloaded, total);
                mCurrentTask = oldTask;
            }
        }

        @Override
        public void onPageFailure(int index, String error, int finished, int downloaded, int total) {
            // 确保使用正确的任务
            DownloadInfo info = mCurrentTasks.get(mGid);
            if (info != null) {
                DownloadInfo oldTask = mCurrentTask;
                mCurrentTask = info;
                mManager.onPageFailure(index, error, finished, downloaded, total);
                mCurrentTask = oldTask;
            }
        }

        @Override
        public void onFinish(int finished, int downloaded, int total) {
            // 确保使用正确的任务
            DownloadInfo info = mCurrentTasks.get(mGid);
            if (info != null) {
                DownloadInfo oldTask = mCurrentTask;
                SpiderQueen oldSpider = mCurrentSpider;
                mCurrentTask = info;
                mCurrentSpider = mCurrentSpiders.get(mGid);
                mManager.onFinish(finished, downloaded, total);
                mCurrentTask = oldTask;
                mCurrentSpider = oldSpider;
            }
        }

        @Override
        public void onGetImageSuccess(int index, Image image) {
            // 直接委托给 DownloadManager,这个回调通常被忽略
            mManager.onGetImageSuccess(index, image);
        }

        @Override
        public void onGetImageFailure(int index, String error) {
            // 直接委托给 DownloadManager,这个回调通常被忽略
            mManager.onGetImageFailure(index, error);
        }
    }

    private static final Comparator<DownloadInfo> DATE_DESC_COMPARATOR = new Comparator<>() {
        @Override
        public int compare(DownloadInfo lhs, DownloadInfo rhs) {
            long dif = lhs.time - rhs.time;
            if (dif > 0) {
                return -1;
            } else if (dif < 0) {
                return 1;
            } else {
                return 0;
            }
//            return  > 0 ? -1 : 1;
        }
    };

    public interface DownloadInfoListener {

        /**
         * Add the special info to the special position
         */
        void onAdd(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position);

        /**
         * delete Old replace new
         */
        void onReplace(@NonNull DownloadInfo newInfo, @NonNull DownloadInfo oldInfo);

        /**
         * The special info is changed
         */
        void onUpdate(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, LinkedList<DownloadInfo> mWaitList);

        /**
         * Maybe all data is changed, but size is the same
         */
        void onUpdateAll();

        /**
         * Maybe all data is changed, maybe list is changed
         */
        void onReload();

        /**
         * The list is gone, use default list please
         */
        void onChange();

        /**
         * Rename label
         */
        void onRenameLabel(String from, String to);

        /**
         * Remove the special info from the special position
         */
        void onRemove(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position);

        void onUpdateLabels();
    }

    public interface DownloadListener {

        /**
         * Get 509 error
         */
        void onGet509();

        /**
         * Start download
         */
        void onStart(DownloadInfo info);

        /**
         * Update download speed
         */
        void onDownload(DownloadInfo info);

        /**
         * Update page downloaded
         */
        void onGetPage(DownloadInfo info);

        /**
         * Download done
         */
        void onFinish(DownloadInfo info);

        /**
         * Download done
         */
        void onCancel(DownloadInfo info);
    }

}
