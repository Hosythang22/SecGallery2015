package com.cooliris.media;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.URISyntaxException;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import android.os.Process;

import com.cooliris.cache.CacheService;

public final class MediaItemTexture extends Texture {
    public static final int MAX_FACES = 1;
    private static final String TAG = "MediaItemTexture";
    private static final int CACHE_HEADER_SIZE = 12;

    private final Config mConfig;
    private final MediaItem mItem;
    private Context mContext;
    private boolean mIsRetrying;
    private boolean mCached;

    public static final class Config {
        public int thumbnailWidth;
        public int thumbnailHeight;
    }

    public MediaItemTexture(Context context, Config config, MediaItem item) {
        mConfig = config;
        mContext = context;
        mItem = item;
        mCached = computeCache();
    }

    private boolean computeCache() {
        final Config config = mConfig;
        final MediaItem item = mItem;
        DiskCache cache = null;
        MediaSet parentMediaSet = item.mParentMediaSet;
        if (config != null && parentMediaSet != null && parentMediaSet.mDataSource != null) {
            cache = parentMediaSet.mDataSource.getThumbnailCache();
            if (cache == LocalDataSource.sThumbnailCache) {
                if (item.mMimeType != null && item.mMimeType.contains("video")) {
                    cache = LocalDataSource.sThumbnailCacheVideo;
                }
            }
        }
        if (cache == null) {
            return false;
        }
        synchronized (cache) {
            long id = Utils.Crc64Long(item.mFilePath);
            return cache.isDataAvailable(id, item.mDateModifiedInSec * 1000);
        }
    }

    @Override
    public boolean isUncachedVideo() {
        if (isCached())
            return false;
        if (mItem.mParentMediaSet == null || mItem.mMimeType == null)
            return false;
        if (mItem.mMimeType.contains("video")) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean isCached() {
        return mCached;
    }

    protected Bitmap load(RenderView view) {

        final Config config = mConfig;
        final MediaItem item = mItem;

        // Special case for non-MediaStore content URIs, do not cache the
        // thumbnail.
        String uriString = item.mContentUri;
        if (uriString != null) {
            Uri uri = Uri.parse(uriString);
            if (uri.getScheme().equals("content") && !uri.getAuthority().equals("media")) {
                try {
                    return UriTexture.createFromUri(mContext, item.mThumbnailUri, 128, 128, 0, null);
                } catch (IOException e) {
                    return null;
                } catch (URISyntaxException e) {
                    return null;
                }
            }
        }

        // Look up the thumbnail in the disk cache.
        if (config == null) {
            Bitmap retVal = null;
            try {
                if (mItem.getMediaType() == MediaItem.MEDIA_TYPE_IMAGE) {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    try {
                        // We first dirty the cache if the timestamp has changed
                        DiskCache cache = null;
                        MediaSet parentMediaSet = item.mParentMediaSet;
                        if (parentMediaSet != null && parentMediaSet.mDataSource != null) {
                            cache = parentMediaSet.mDataSource.getThumbnailCache();
                            if (cache == LocalDataSource.sThumbnailCache) {
                                if (item.mMimeType != null && item.mMimeType.contains("video")) {
                                    cache = LocalDataSource.sThumbnailCacheVideo;
                                }
                                final long crc64 = Utils.Crc64Long(item.mFilePath);
                                if (!cache.isDataAvailable(crc64, item.mDateModifiedInSec * 1000)) {
                                    UriTexture.invalidateCache(crc64, UriTexture.MAX_RESOLUTION);
                                }
                            }
                        }
                        retVal = UriTexture.createFromUri(mContext, mItem.mContentUri, UriTexture.MAX_RESOLUTION,
                                UriTexture.MAX_RESOLUTION, Utils.Crc64Long(item.mFilePath), null);
                    } catch (IOException e) {
                        ;
                    } catch (URISyntaxException e) {
                        ;
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                } else {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    new Thread() {
                        public void run() {
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException e) {
                                ;
                            }
                            try {
                                MediaStore.Video.Thumbnails.cancelThumbnailRequest(mContext.getContentResolver(), mItem.mId);
                            } catch (Exception e) {
                                ;
                            }
                        }
                    }.start();
                    retVal = MediaStore.Video.Thumbnails.getThumbnail(mContext.getContentResolver(), mItem.mId,
                            MediaStore.Video.Thumbnails.MINI_KIND, null);
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                }
            } catch (OutOfMemoryError e) {
                Log.i(TAG, "Bitmap creation fail, outofmemory");
                view.handleLowMemory();
                try {
                    if (!mIsRetrying) {
                        Thread.sleep(1000);
                        mIsRetrying = true;
                        retVal = load(view);
                    }
                } catch (InterruptedException eInterrupted) {

                }
            }
            return retVal;
        } else {
            byte[] data = null;
            data = CacheService.queryThumbnail(mContext, Utils.Crc64Long(item.mFilePath), item.mId,
                    item.getMediaType() == MediaItem.MEDIA_TYPE_VIDEO, item.mDateModifiedInSec * 1000);
            if (data != null) {
                try {
                    // Parse record header.
                    final ByteArrayInputStream cacheInput = new ByteArrayInputStream(data);
                    final DataInputStream dataInput = new DataInputStream(cacheInput);
                    item.mThumbnailId = dataInput.readLong();
                    item.mThumbnailFocusX = dataInput.readShort();
                    item.mThumbnailFocusY = dataInput.readShort();
                    // Decode the thumbnail.
                    final BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inDither = false;
                    options.inScaled = false;
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    final Bitmap bitmap = BitmapFactory.decodeByteArray(data, CACHE_HEADER_SIZE, data.length - CACHE_HEADER_SIZE,
                            options);
                    return bitmap;
                } catch (IOException e) {
                    // Fall through to regenerate the cached thumbnail.
                }
            }
        }
        return null;
    }
}
