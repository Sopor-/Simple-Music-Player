package com.simplemobiletools.musicplayer;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.support.v4.content.ContextCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.squareup.otto.Bus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;

public class MusicService extends Service
        implements MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener {
    private static final String TAG = MusicService.class.getSimpleName();
    private static final int MIN_DURATION_MS = 20000;
    private HeadsetPlugReceiver headsetPlugReceiver;
    private IncomingCallReceiver incomingCallReceiver;
    private ArrayList<Song> songs;
    private MediaPlayer player;
    private ArrayList<Integer> playedSongIDs;
    private Song currSong;
    private Bus bus;
    private boolean wasPlayingAtCall;
    private Bitmap prevBitmap;
    private Bitmap playBitmap;
    private Bitmap pauseBitmap;
    private Bitmap nextBitmap;
    private Bitmap stopBitmap;
    private Bitmap closeBitmap;

    @Override
    public void onCreate() {
        super.onCreate();
        songs = new ArrayList<>();
        playedSongIDs = new ArrayList<>();

        if (bus == null) {
            bus = BusProvider.getInstance();
            bus.register(this);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            getSortedSongs();
            headsetPlugReceiver = new HeadsetPlugReceiver();
            incomingCallReceiver = new IncomingCallReceiver(this);
            wasPlayingAtCall = false;
            initMediaPlayerIfNeeded();
            createNotificationButtons();
            setupNotification();
        } else {
            Toast.makeText(this, getResources().getString(R.string.no_permissions), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (action != null) {
            switch (action) {
                case Constants.INIT:
                    bus.post(new Events.PlaylistUpdated(songs));
                    bus.post(new Events.SongChanged(currSong));
                    songStateChanged(isPlaying());
                    break;
                case Constants.PREVIOUS:
                    playPreviousSong();
                    break;
                case Constants.PAUSE:
                    pauseSong();
                    break;
                case Constants.PLAYPAUSE:
                    if (isPlaying())
                        pauseSong();
                    else
                        resumeSong();
                    break;
                case Constants.NEXT:
                    playNextSong();
                    break;
                case Constants.STOP:
                    stopSong();
                    break;
                case Constants.PLAYPOS:
                    playSong(intent);
                    break;
                case Constants.CALL_START:
                    incomingCallStart();
                    break;
                case Constants.CALL_STOP:
                    incomingCallStop();
                    break;
                case Constants.FINISH:
                    destroyPlayer();
                    break;
                default:
                    break;
            }
        }

        return START_NOT_STICKY;
    }

    public void initMediaPlayerIfNeeded() {
        if (player != null)
            return;

        player = new MediaPlayer();
        player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        player.setOnPreparedListener(this);
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);
    }

    private void fillPlaylist() {
        songs.clear();
        final Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        final Cursor cursor = getContentResolver().query(uri, null, null, null, null);

        if (cursor != null && cursor.moveToFirst()) {
            final int idIndex = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
            final int titleIndex = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
            final int artistIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            final int durationIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
            do {
                if (cursor.getInt(durationIndex) > MIN_DURATION_MS) {
                    final long id = cursor.getLong(idIndex);
                    final String title = cursor.getString(titleIndex);
                    final String artist = cursor.getString(artistIndex);
                    songs.add(new Song(id, title, artist));
                }
            } while (cursor.moveToNext());
            cursor.close();
        }
    }

    private void getSortedSongs() {
        fillPlaylist();
        Collections.sort(songs, new Comparator<Song>() {
            public int compare(Song a, Song b) {
                return a.getTitle().compareTo(b.getTitle());
            }
        });
    }

    private void createNotificationButtons() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Resources res = getResources();
            prevBitmap = Utils.getColoredIcon(res, Color.BLACK, R.mipmap.previous_white);
            playBitmap = Utils.getColoredIcon(res, Color.BLACK, R.mipmap.play_white);
            pauseBitmap = Utils.getColoredIcon(res, Color.BLACK, R.mipmap.pause_white);
            nextBitmap = Utils.getColoredIcon(res, Color.BLACK, R.mipmap.next_white);
            stopBitmap = Utils.getColoredIcon(res, Color.BLACK, R.mipmap.stop_white);
            closeBitmap = Utils.getColoredIcon(res, Color.BLACK, R.mipmap.close_white);
        }
    }

    private void setupNotification() {
        final Intent intent = new Intent(this, ControlActionsListener.class);

        final RemoteViews remoteViews = new RemoteViews(getPackageName(), R.layout.notification);
        remoteViews.setInt(R.id.widget_holder, "setBackgroundColor", 0);

        final String title = (currSong == null) ? "" : currSong.getTitle();
        final String artist = (currSong == null) ? "" : currSong.getArtist();
        remoteViews.setTextViewText(R.id.songTitle, title);
        remoteViews.setTextViewText(R.id.songArtist, artist);

        updatePlayPauseButton(remoteViews);

        setupIntent(intent, remoteViews, Constants.PREVIOUS, R.id.previousBtn);
        setupIntent(intent, remoteViews, Constants.PLAYPAUSE, R.id.playPauseBtn);
        setupIntent(intent, remoteViews, Constants.NEXT, R.id.nextBtn);
        setupIntent(intent, remoteViews, Constants.STOP, R.id.stopBtn);
        setupIntent(intent, remoteViews, Constants.FINISH, R.id.closeBtn);

        remoteViews.setViewVisibility(R.id.closeBtn, View.VISIBLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            remoteViews.setInt(R.id.songArtist, "setTextColor", Color.BLACK);
            remoteViews.setInt(R.id.songTitle, "setTextColor", Color.BLACK);

            remoteViews.setImageViewBitmap(R.id.previousBtn, prevBitmap);

            if (isPlaying())
                remoteViews.setImageViewBitmap(R.id.playPauseBtn, pauseBitmap);
            else
                remoteViews.setImageViewBitmap(R.id.playPauseBtn, playBitmap);

            remoteViews.setImageViewBitmap(R.id.nextBtn, nextBitmap);
            remoteViews.setImageViewBitmap(R.id.stopBtn, stopBitmap);
            remoteViews.setImageViewBitmap(R.id.closeBtn, closeBitmap);
        }

        final Notification notification =
                new Notification.Builder(this).setContentTitle(title).setContentText(artist).setSmallIcon(R.mipmap.ic_launcher).build();
        notification.bigContentView = remoteViews;

        final Intent contentIntent = new Intent(this, MainActivity.class);
        notification.contentIntent = PendingIntent.getActivity(this, 0, contentIntent, 0);
        notification.flags |= Notification.FLAG_ONGOING_EVENT;

        startForeground(1, notification);
    }

    private void setupIntent(Intent intent, RemoteViews remoteViews, String action, int id) {
        intent.setAction(action);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, intent, 0);

        if (remoteViews != null)
            remoteViews.setOnClickPendingIntent(id, pendingIntent);
    }

    private void updatePlayPauseButton(RemoteViews remoteViews) {
        int playPauseIcon = R.mipmap.play_white;
        if (isPlaying())
            playPauseIcon = R.mipmap.pause_white;

        remoteViews.setImageViewResource(R.id.playPauseBtn, playPauseIcon);
    }

    private int getNewSongId() {
        final int cnt = songs.size();
        if (cnt == 0) {
            return -1;
        } else if (cnt == 1) {
            return 0;
        } else {
            final Random random = new Random();
            int newSongIndex = playedSongIDs.size() - 1;
            // make sure we do not repeat the same song
            while (newSongIndex == playedSongIDs.size() - 1) {
                newSongIndex = random.nextInt(cnt - 1);
            }
            return newSongIndex;
        }
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public void playPreviousSong() {
        if (songs.isEmpty())
            return;

        initMediaPlayerIfNeeded();

        // play the previous song if we are less than 5 secs into the song, else restart
        // remove the latest song from the list
        if (playedSongIDs.size() > 1 && player.getCurrentPosition() < 5000) {
            playedSongIDs.remove(playedSongIDs.size() - 1);
            setSong(playedSongIDs.get(playedSongIDs.size() - 1), false);
        } else {
            restartSong();
        }
    }

    public void pauseSong() {
        if (songs.isEmpty())
            return;

        initMediaPlayerIfNeeded();

        player.pause();
        songStateChanged(false);
    }

    public void resumeSong() {
        if (songs.isEmpty()) {
            fillPlaylist();
        }

        if (songs.isEmpty())
            return;

        initMediaPlayerIfNeeded();

        if (currSong == null)
            playNextSong();
        else
            player.start();

        songStateChanged(true);
    }

    public void playNextSong() {
        setSong(getNewSongId(), true);
    }

    public void stopSong() {
        if (player != null && isPlaying()) {
            // .stop() seems to misbehave weirdly
            player.pause();
            player.seekTo(0);
            songStateChanged(false);
        }
    }

    private void restartSong() {
        player.seekTo(0);
    }

    private void playSong(Intent intent) {
        final int pos = intent.getIntExtra(Constants.SONG_POS, 0);
        setSong(pos, true);
    }

    public void setSong(int songId, boolean addNewSong) {
        if (songs.isEmpty())
            return;

        final boolean wasPlaying = isPlaying();
        initMediaPlayerIfNeeded();

        player.reset();
        if (addNewSong)
            playedSongIDs.add(songId);

        currSong = songs.get(songId);

        try {
            final Uri trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, currSong.getId());
            player.setDataSource(getApplicationContext(), trackUri);
        } catch (IOException e) {
            Log.e(TAG, "setSong IOException " + e.getMessage());
        }

        player.prepareAsync();
        bus.post(new Events.SongChanged(currSong));

        if (!wasPlaying) {
            songStateChanged(true);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (player.getCurrentPosition() > 0) {
            player.reset();
            playNextSong();
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        player.reset();
        return false;
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        mp.start();
        setupNotification();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        destroyPlayer();
    }

    private void destroyPlayer() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }

        if (bus != null) {
            songStateChanged(false);
            bus.post(new Events.SongChanged(null));
            bus.unregister(this);
        }

        final TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(incomingCallReceiver, PhoneStateListener.LISTEN_NONE);

        stopForeground(true);
        stopSelf();
    }

    public void incomingCallStart() {
        if (isPlaying()) {
            wasPlayingAtCall = true;
            pauseSong();
        } else {
            wasPlayingAtCall = false;
        }
    }

    public void incomingCallStop() {
        if (wasPlayingAtCall)
            resumeSong();

        wasPlayingAtCall = false;
    }

    private void songStateChanged(boolean isPlaying) {
        setupNotification();
        bus.post(new Events.SongStateChanged(isPlaying));

        if (isPlaying) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
            registerReceiver(headsetPlugReceiver, filter);

            final TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            telephonyManager.listen(incomingCallReceiver, PhoneStateListener.LISTEN_CALL_STATE);
        } else {
            try {
                unregisterReceiver(headsetPlugReceiver);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "IllegalArgumentException " + e.getMessage());
            }
        }
    }
}