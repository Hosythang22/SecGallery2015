package com.cooliris.cache;

import com.cooliris.media.LocalDataSource;
import com.cooliris.media.SingleDataSource;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    private final Handler mHandler = new Handler();
    private boolean mListenersInitialized = false;

    @Override
    public void onReceive(final Context context, Intent intent) {
        final String action = intent.getAction();
        final Uri fileUri = intent.getData();
        Log.i(TAG, "Got intent with action " + action);
        if (Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(action)) {
            /*
             * Start CacheService only for external media
             */
            if (Environment.getExternalStorageDirectory().getPath().equals(fileUri.getPath())) {
                CacheService.markDirty(context);
                CacheService.startCache(context, true);
            }
        } else if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
            if (!mListenersInitialized) {
                // We add special listeners for the MediaProvider
                mListenersInitialized = true;
                final Handler handler = mHandler;
                final ContentObserver localObserver = new ContentObserver(handler) {
                    public void onChange(boolean selfChange) {
                        if (!LocalDataSource.sObserverActive) {
                            CacheService.senseDirty(context, null);
                        }
                    }
                };
                // Start listening perpetually.
                Uri uriImages = Images.Media.EXTERNAL_CONTENT_URI;
                Uri uriVideos = Video.Media.EXTERNAL_CONTENT_URI;
                ContentResolver cr = context.getContentResolver();
                cr.registerContentObserver(uriImages, false, localObserver);
                cr.registerContentObserver(uriVideos, false, localObserver);
            }
        } else if (action.equals(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)) {
            final long bucketId = SingleDataSource.parseBucketIdFromFileUri(fileUri.toString());
            if (!CacheService.isPresentInCache(bucketId)) {
                CacheService.markDirty(context);
            }
        } else if (action.equals(Intent.ACTION_MEDIA_EJECT)) {
            LocalDataSource.sThumbnailCache.close();
            LocalDataSource.sThumbnailCacheVideo.close();
            CacheService.sAlbumCache.close();
            CacheService.sMetaAlbumCache.close();
            CacheService.sSkipThumbnailIds.flush();
        }
    }
}
