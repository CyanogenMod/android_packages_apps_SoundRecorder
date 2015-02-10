/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (c) 2012, The Linux Foundation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.soundrecorder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.PowerManager.WakeLock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

/**
 * Calculates remaining recording time based on available disk space and
 * optionally a maximum recording file size.
 *
 * The reason why this is not trivial is that the file grows in blocks
 * every few seconds or so, while we want a smooth countdown.
 */

class RemainingTimeCalculator {
    public static final int UNKNOWN_LIMIT = 0;
    public static final int FILE_SIZE_LIMIT = 1;
    public static final int DISK_SPACE_LIMIT = 2;

    // which of the two limits we will hit (or have fit) first
    private int mCurrentLowerLimit = UNKNOWN_LIMIT;

    private File mSDCardDirectory;
    private File mPhoneCardDirectory;
     // State for tracking file size of recording.
    private File mRecordingFile;
    private long mMaxBytes;

    // Rate at which the file grows
    private int mBytesPerSecond;
    private int mPath = 0;

    // time at which number of free blocks last changed
    private long mBlocksChangedTime;
    // number of available blocks at that time
    private long mLastBlocks;

    // time at which the size of the file has last changed
    private long mFileSizeChangedTime;
    // size of the file at that time
    private long mLastFileSize;

    public RemainingTimeCalculator(Context context) {
        String extPath = SoundRecorder.getSDPath(context);
        if (extPath != null) {
            mSDCardDirectory = new File(extPath);
        }
        mPhoneCardDirectory = Environment.getExternalStorageDirectory();
    }

    /**
     * If called, the calculator will return the minimum of two estimates:
     * how long until we run out of disk space and how long until the file
     * reaches the specified size.
     *
     * @param file the file to watch
     * @param maxBytes the limit
     */

    public void setFileSizeLimit(File file, long maxBytes) {
        mRecordingFile = file;
        mMaxBytes = maxBytes;
    }

    /**
     * Resets the interpolation.
     */
    public void reset() {
        mCurrentLowerLimit = UNKNOWN_LIMIT;
        mBlocksChangedTime = -1;
        mFileSizeChangedTime = -1;
    }

    /**
     * Returns how long (in seconds) we can continue recording.
     */
    public long timeRemaining() {
        // Calculate how long we can record based on free disk space

        long blocks;
        StatFs fs;
        if (mPath == 1) {
            fs = new StatFs(mSDCardDirectory.getAbsolutePath());
            blocks = fs.getAvailableBlocks();
        } else {
            fs = new StatFs(mPhoneCardDirectory.getAbsolutePath());
            blocks = fs.getAvailableBlocks() - fs.getBlockCount() * 5 / 100;
        }
        long blockSize = fs.getBlockSize();
        long now = System.currentTimeMillis();

        if (mBlocksChangedTime == -1 || blocks != mLastBlocks) {
            mBlocksChangedTime = now;
            mLastBlocks = blocks;
        }

        /* The calculation below always leaves one free block, since free space
           in the block we're currently writing to is not added. This
           last block might get nibbled when we close and flush the file, but
           we won't run out of disk. */

        // at mBlocksChangedTime we had this much time
        long result = mLastBlocks*blockSize/mBytesPerSecond;
        // so now we have this much time
        result -= (now - mBlocksChangedTime)/1000;

        if (mRecordingFile == null) {
            mCurrentLowerLimit = DISK_SPACE_LIMIT;
            return result;
        }

        // If we have a recording file set, we calculate a second estimate
        // based on how long it will take us to reach mMaxBytes.

        mRecordingFile = new File(mRecordingFile.getAbsolutePath());
        long fileSize = mRecordingFile.length();
        if (mFileSizeChangedTime == -1 || fileSize != mLastFileSize) {
            mFileSizeChangedTime = now;
            mLastFileSize = fileSize;
        }

        long result2 = (mMaxBytes - fileSize)/mBytesPerSecond;
        result2 -= (now - mFileSizeChangedTime)/1000;
        result2 -= 1; // just for safety

        mCurrentLowerLimit = result < result2
            ? DISK_SPACE_LIMIT : FILE_SIZE_LIMIT;

        return Math.min(result, result2);
    }

    /**
     * Indicates which limit we will hit (or have hit) first, by returning one
     * of FILE_SIZE_LIMIT or DISK_SPACE_LIMIT or UNKNOWN_LIMIT. We need this to
     * display the correct message to the user when we hit one of the limits.
     */
    public int currentLowerLimit() {
        return mCurrentLowerLimit;
    }

    /**
     * Is there any point of trying to start recording?
     */
    public boolean diskSpaceAvailable() {
        boolean result;
        if (mPath == 1) {
            StatFs fs = new StatFs(mSDCardDirectory.getAbsolutePath());
            result = fs.getAvailableBlocks() > 1;
        } else {
            StatFs fs = new StatFs(mPhoneCardDirectory.getAbsolutePath());
            result = fs.getAvailableBlocks() > fs.getBlockCount() * 5 / 100;
        }
        // keep one free block
        return result;
    }

    /**
     * Sets the bit rate used in the interpolation.
     *
     * @param bitRate the bit rate to set in bits/sec.
     */
    public void setBitRate(int bitRate) {
        mBytesPerSecond = bitRate/8;
    }

    public void setStoragePath(int path) {
        mPath = path;
    }

    public boolean hasExternalStorage() {
        return mSDCardDirectory != null;
    }
}

public class SoundRecorder extends Activity
        implements Button.OnClickListener, Recorder.OnStateChangedListener {
    static final String TAG = "SoundRecorder";
    static final String STATE_FILE_NAME = "soundrecorder.state";
    static final String RECORDER_STATE_KEY = "recorder_state";
    static final String SAMPLE_INTERRUPTED_KEY = "sample_interrupted";
    static final String MAX_FILE_SIZE_KEY = "max_file_size";
    private static final String EXIT_AFTER_RECORD = "exit_after_record";

    static final String AUDIO_3GPP = "audio/3gpp";
    static final String AUDIO_AMR = "audio/amr";
    static final String AUDIO_EVRC = "audio/evrc";
    static final String AUDIO_QCELP = "audio/qcelp";
    static final String AUDIO_AAC_MP4 = "audio/aac_mp4";
    static final String AUDIO_WAVE_6CH_LPCM = "audio/wave_6ch_lpcm";
    static final String AUDIO_WAVE_2CH_LPCM = "audio/wave_2ch_lpcm";
    static final String AUDIO_AAC_5POINT1_CHANNEL = "audio/aac_5point1_channel";
    static final String AUDIO_AMR_WB = "audio/amr-wb";
    static final String AUDIO_ANY = "audio/*";
    static final String ANY_ANY = "*/*";

    static final int SETTING_TYPE_STORAGE_LOCATION = 0;
    static final int SETTING_TYPE_FILE_TYPE = 1;
    static final int FOCUSCHANGE = 2;
    static final String STORAGE_PATH_LOCAL_PHONE = Environment.getExternalStorageDirectory()
            .toString() + "/SoundRecorder";

    static final int BITRATE_AMR =  12800; // bits/sec
    static final int BITRATE_EVRC = 8500;
    static final int BITRATE_QCELP = 13300;
    static final int BITRATE_3GPP = 12800;
    static final int SAMPLERATE_MULTI_CH = 48000;
    static final int BITRATE_AMR_WB = 16000;
    static final int SAMPLERATE_AMR_WB = 16000;
    static final int SAMPLERATE_8000 = 8000;
    static final long STOP_WAIT = 300;
    static final long BACK_KEY_WAIT = 400;
    int mAudioOutputFormat = MediaRecorder.OutputFormat.AMR_WB;
    String mAmrWidebandExtension = ".awb";
    private AudioManager mAudioManager;
    private boolean mRecorderStop = false;
    private boolean mRecorderProcessed = false;
    private boolean mDataExist = false;
    private boolean mWAVSupport = true;
    private boolean mExitAfterRecord = false;

    int mAudioSourceType = MediaRecorder.AudioSource.MIC;
    static int sOldCallState = TelephonyManager.CALL_STATE_IDLE;
    WakeLock mWakeLock;
    String mRequestedType = AUDIO_ANY;
    Recorder mRecorder;
    boolean mSampleInterrupted = false;
    static boolean bSSRSupported;
    private String mLastFileName;
    String mErrorUiMessage = null; // Some error messages are displayed in the UI,
                                   // not a dialog. This happens when a recording
                                   // is interrupted for some reason.

    long mMaxFileSize = -1;        // can be specified in the intent
    RemainingTimeCalculator mRemainingTimeCalculator;

    String mTimerFormat;
    final Handler mHandler = new Handler();
    Runnable mUpdateTimer = new Runnable() {
        public void run() { updateTimerView(); }
    };

    ImageButton mRecordButton;
    ImageButton mPlayButton;
    ImageButton mStopButton;

    ImageView mStateLED;
    TextView mStateMessage1;
    TextView mStateMessage2;
    ProgressBar mStateProgressBar;
    TextView mTimerView;

    LinearLayout mExitButtons;
    Button mAcceptButton;
    Button mDiscardButton;
    VUMeter mVUMeter;
    private BroadcastReceiver mSDCardMountEventReceiver = null;
    private BroadcastReceiver mPowerOffReceiver = null;
    private TelephonyManager mTelephonyManager;
    private PhoneStateListener mPhoneStateListener;
    private int mFileType = 0;
    private int mPath = 0;
    private String mStoragePath = STORAGE_PATH_LOCAL_PHONE;
    private SharedPreferences mSharedPreferences;
    private Editor mPrefsStoragePathEditor;

    private PhoneStateListener getPhoneStateListener() {
        PhoneStateListener phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String ignored) {
               switch (state) {
                      case TelephonyManager.CALL_STATE_IDLE:
                      if ((sOldCallState == TelephonyManager.CALL_STATE_OFFHOOK)
                               && !(mAudioSourceType == MediaRecorder.AudioSource.MIC)){
                         mRecorder.stop();
                         mAudioSourceType = MediaRecorder.AudioSource.MIC;
                      }
                      break;

                      case TelephonyManager.CALL_STATE_OFFHOOK:
                      sOldCallState = TelephonyManager.CALL_STATE_OFFHOOK;
                      break;

                      default:
                      // The control should not come here
                      Log.e(TAG,"Unknown call state");
                      break;
                }
            }
        };
        return phoneStateListener;
    }

    @Override
    public void onCreate(Bundle icycle) {
        super.onCreate(icycle);
        mSharedPreferences = getSharedPreferences("storage_Path", Context.MODE_PRIVATE);
        mPrefsStoragePathEditor = mSharedPreferences.edit();

        Intent i = getIntent();
        if (i != null) {
            String s = i.getType();
            if (AUDIO_AMR.equals(s) || AUDIO_3GPP.equals(s) || AUDIO_ANY.equals(s)
                    || ANY_ANY.equals(s)) {
                mRequestedType = s;
                mWAVSupport = false;
            } else if (s != null) {
                // we only support amr and 3gpp formats right now
                setResult(RESULT_CANCELED);
                finish();
                return;
            }

            mWAVSupport = getResources().getBoolean(R.bool.wave_recording_supported);

            final String EXTRA_MAX_BYTES
                = android.provider.MediaStore.Audio.Media.EXTRA_MAX_BYTES;
            mMaxFileSize = i.getLongExtra(EXTRA_MAX_BYTES, -1);

            mExitAfterRecord = i.getBooleanExtra(EXIT_AFTER_RECORD, false);
        }

        if (AUDIO_ANY.equals(mRequestedType) || ANY_ANY.equals(mRequestedType)) {
            mRequestedType = AUDIO_AMR;
        }

        mPath = mSharedPreferences.getInt("path", mPath);
        mRequestedType = mSharedPreferences.getString("requestedType", mRequestedType);
        mFileType = mSharedPreferences.getInt("fileType", mFileType);
        mStoragePath = mSharedPreferences.getString("storagePath", mStoragePath);
        if (!mWAVSupport && mRequestedType == AUDIO_WAVE_2CH_LPCM) {
            mRequestedType = AUDIO_AMR;
            mFileType = 0;
        }

        setContentView(R.layout.main);
        mAudioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        mRecorder = new Recorder();
        mRecorder.setOnStateChangedListener(this);
        mRemainingTimeCalculator = new RemainingTimeCalculator(SoundRecorder.this);

        PowerManager pm
            = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,
                                    "SoundRecorder");

        initResourceRefs();
        mRecorderStop = false;
        mRecorderProcessed = false;
        mDataExist = false;

        setResult(RESULT_CANCELED);
        registerExternalStorageListener();
        registerPowerOffListener();
        if (icycle != null) {
            Bundle recorderState = icycle.getBundle(RECORDER_STATE_KEY);
            if (recorderState != null) {
                mRecorder.restoreState(recorderState);
                mSampleInterrupted = recorderState.getBoolean(SAMPLE_INTERRUPTED_KEY, false);
                mMaxFileSize = recorderState.getLong(MAX_FILE_SIZE_KEY, -1);
            }
        }
        mTelephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        mPhoneStateListener = getPhoneStateListener();

        String ssrRet = SystemProperties.get("ro.qc.sdk.audio.ssr","false");
        if (ssrRet.contains("true")) {
            Log.d(TAG,"Surround sound recording is supported");
            bSSRSupported = true;
        } else {
            Log.d(TAG,"Surround sound recording is not supported");
            bSSRSupported = false;
        }

        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // While we're in the foreground, listen for phone state changes.
        mTelephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        setContentView(R.layout.main);
        initResourceRefs();
        updateUi();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mRecorder.sampleLength() == 0)
            return;

        Bundle recorderState = new Bundle();

        mRecorder.saveState(recorderState);
        recorderState.putBoolean(SAMPLE_INTERRUPTED_KEY, mSampleInterrupted);
        recorderState.putLong(MAX_FILE_SIZE_KEY, mMaxFileSize);

        outState.putBundle(RECORDER_STATE_KEY, recorderState);
    }

    /*
     * Whenever the UI is re-created (due f.ex. to orientation change) we have
     * to reinitialize references to the views.
     */
    private void initResourceRefs() {
        mRecordButton = (ImageButton) findViewById(R.id.recordButton);
        mPlayButton = (ImageButton) findViewById(R.id.playButton);
        mStopButton = (ImageButton) findViewById(R.id.stopButton);

        mStateLED = (ImageView) findViewById(R.id.stateLED);
        mStateMessage1 = (TextView) findViewById(R.id.stateMessage1);
        mStateMessage2 = (TextView) findViewById(R.id.stateMessage2);
        mStateProgressBar = (ProgressBar) findViewById(R.id.stateProgressBar);
        mTimerView = (TextView) findViewById(R.id.timerView);

        mExitButtons = (LinearLayout) findViewById(R.id.exitButtons);
        mAcceptButton = (Button) findViewById(R.id.acceptButton);
        mDiscardButton = (Button) findViewById(R.id.discardButton);
        mVUMeter = (VUMeter) findViewById(R.id.uvMeter);

        mRecordButton.setOnClickListener(this);
        mPlayButton.setOnClickListener(this);
        mStopButton.setOnClickListener(this);
        mAcceptButton.setOnClickListener(this);
        mDiscardButton.setOnClickListener(this);

        mTimerFormat = getResources().getString(R.string.timer_format);

        mVUMeter.setRecorder(mRecorder);
    }

    /*
     * Make sure we're not recording music playing in the background, ask
     * the MediaPlaybackService to pause playback.
     */
    private void stopAudioPlayback() {
        // Shamelessly copied from MediaPlaybackService.java, which
        // should be public, but isn't.
        Intent i = new Intent("com.android.music.musicservicecommand");
        i.putExtra("command", "pause");
        mAudioManager.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        sendBroadcast(i);
        try{
           Thread.sleep(STOP_WAIT);
        } catch (InterruptedException ex) {
        }
    }

    private OnAudioFocusChangeListener mAudioFocusListener = new OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int focusChange) {
            mRecorderHandler.obtainMessage(FOCUSCHANGE, focusChange, 0).sendToTarget();
        }
    };

    private Handler mRecorderHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case FOCUSCHANGE:
                    switch (msg.arg1) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                            if (mRecorder.state() == Recorder.RECORDING_STATE) {
                                mRecorder.stop();
                                if (mRecorder.sampleLength() > 0) {
                                    mRecorderStop = true;
                                }
                                mVUMeter.resetAngle();
                                invalidateOptionsMenu();
                            } else if (mRecorder.state() == Recorder.PLAYING_STATE) {
                                mRecorder.stopPlayback();
                            }
                            break;
                    }
                    break;
                default:
                    break;
            }
        }
    };

    /*
     * Handle the buttons.
     */
    public void onClick(View button) {
        if (!button.isEnabled())
            return;

        switch (button.getId()) {
            case R.id.recordButton:
                if (mRecorder.state() == Recorder.PAUSE_STATE) {
                    mRecorder.resumeRecording();
                    updateUi();
                    return;
                } else if (mRecorder.state() == Recorder.RECORDING_STATE) {
                    mRecorder.pauseRecording();
                    updateUi();
                    return;
                }
                mRemainingTimeCalculator.reset();
                mRemainingTimeCalculator.setStoragePath(mPath);
                mRecorder.setStoragePath(mStoragePath);
                if (mPath == 0 &&
                        !Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)){
                    mSampleInterrupted = true;
                    mErrorUiMessage = getResources().getString(R.string.no_phonestorage);
                    updateUi();
                } else if (mPath ==1 &&
                        !getSDState(SoundRecorder.this).equals(Environment.MEDIA_MOUNTED)) {
                    mSampleInterrupted = true;
                    mErrorUiMessage = getResources().getString(R.string.insert_sd_card);
                    updateUi();
                } else if (!mRemainingTimeCalculator.diskSpaceAvailable()) {
                    mSampleInterrupted = true;
                    mErrorUiMessage = getResources().getString(R.string.storage_is_full);
                    updateUi();
                } else {
                    stopAudioPlayback();

                    if ((mAudioManager.getMode() == AudioManager.MODE_IN_CALL) &&
                        (mAudioSourceType == MediaRecorder.AudioSource.MIC)) {
                        mAudioSourceType = MediaRecorder.AudioSource.VOICE_UPLINK;
                        Log.e(TAG, "Selected Voice Tx only Source: sourcetype" + mAudioSourceType);
                    }
                    if (AUDIO_AMR.equals(mRequestedType)) {
                        mRemainingTimeCalculator.setBitRate(BITRATE_AMR);
                        mRecorder.setChannels(1);
                        mRecorder.setSamplingRate(SAMPLERATE_8000);
                        mRecorder.startRecording(MediaRecorder.OutputFormat.RAW_AMR, ".amr", this,
                                                mAudioSourceType, MediaRecorder.AudioEncoder.AMR_NB);
                    } else if (AUDIO_EVRC.equals(mRequestedType)) {
                        mRemainingTimeCalculator.setBitRate(BITRATE_EVRC);
                        mRecorder.setSamplingRate(SAMPLERATE_8000);
                        mRecorder.startRecording(MediaRecorder.OutputFormat.QCP, ".qcp", this,
                                              mAudioSourceType, MediaRecorder.AudioEncoder.EVRC);
                    } else if (AUDIO_QCELP.equals(mRequestedType)) {
                        mRemainingTimeCalculator.setBitRate(BITRATE_QCELP);
                        mRecorder.setSamplingRate(SAMPLERATE_8000);
                        mRecorder.startRecording(MediaRecorder.OutputFormat.QCP, ".qcp", this,
                                             mAudioSourceType, MediaRecorder.AudioEncoder.QCELP);
                    } else if (AUDIO_3GPP.equals(mRequestedType)) {
                        mRemainingTimeCalculator.setBitRate(BITRATE_3GPP);
                        mRecorder.setChannels(1);
                        mRecorder.startRecording(MediaRecorder.OutputFormat.THREE_GPP, ".3gpp", this,
                                                   mAudioSourceType, MediaRecorder.AudioEncoder.AMR_NB);
                    } else if (AUDIO_AAC_MP4.equals(mRequestedType)) {
                        mRemainingTimeCalculator.setBitRate(BITRATE_3GPP);
                        mRecorder.setSamplingRate(SAMPLERATE_MULTI_CH);
                        mRecorder.startRecording(MediaRecorder.OutputFormat.THREE_GPP, ".3gpp", this,
                                                      mAudioSourceType, MediaRecorder.AudioEncoder.AAC);
                    } else if (AUDIO_AAC_5POINT1_CHANNEL.equals(mRequestedType)) {//AAC  6-channel recording
                        if (true == bSSRSupported) {
                          mRemainingTimeCalculator.setBitRate(BITRATE_3GPP);
                          mRecorder.setChannels(6);
                          mRecorder.setSamplingRate(SAMPLERATE_MULTI_CH);
                          mAudioSourceType = MediaRecorder.AudioSource.MIC;
                          mRecorder.startRecording(MediaRecorder.OutputFormat.THREE_GPP, ".3gpp", this,
                                                         mAudioSourceType, MediaRecorder.AudioEncoder.AAC);
                        } else {
                          throw new IllegalArgumentException("Invalid output file type requested");
                        }
                    } else if (AUDIO_WAVE_6CH_LPCM.equals(mRequestedType)) {//WAVE LPCM  6-channel recording
                        if (true == bSSRSupported) {
                          mRemainingTimeCalculator.setBitRate(BITRATE_3GPP);
                          mRecorder.setChannels(6);
                          mRecorder.setSamplingRate(SAMPLERATE_MULTI_CH);
                          mAudioSourceType = MediaRecorder.AudioSource.MIC;
                          mRecorder.startRecording(MediaRecorder.OutputFormat.WAVE, ".wav", this, mAudioSourceType, MediaRecorder.AudioEncoder.LPCM);
                        } else {
                          throw new IllegalArgumentException("Invalid output file type requested");
                        }
                    } else if (AUDIO_WAVE_2CH_LPCM.equals(mRequestedType)) {
                        //WAVE LPCM  2-channel recording
                        mRemainingTimeCalculator.setBitRate(BITRATE_3GPP);
                        mRecorder.setChannels(2);
                        mRecorder.setSamplingRate(SAMPLERATE_MULTI_CH);
                        mRecorder.startRecording(MediaRecorder.OutputFormat.WAVE,
                                ".wav", this, mAudioSourceType, MediaRecorder.AudioEncoder.LPCM);
                    } else if (AUDIO_AMR_WB.equals(mRequestedType)) {
                        mRemainingTimeCalculator.setBitRate(BITRATE_AMR_WB);
                        mRecorder.setSamplingRate(BITRATE_AMR_WB);
                        mRecorder.startRecording(mAudioOutputFormat, mAmrWidebandExtension, this, mAudioSourceType, MediaRecorder.AudioEncoder.AMR_WB);

                    } else {
                        throw new IllegalArgumentException("Invalid output file type requested");
                    }

                    if (mMaxFileSize != -1) {
                        mRemainingTimeCalculator.setFileSizeLimit(
                                mRecorder.sampleFile(), mMaxFileSize);
                    }
                    mRecorderStop = false;
                    mRecorderProcessed = false;
                }
                invalidateOptionsMenu();
                break;
            case R.id.playButton:
                if (!mRecorder.sampleFile().exists()) {
                    Toast.makeText(SoundRecorder.this, R.string.file_deleted,
                            Toast.LENGTH_SHORT).show();
                    finish();
                }
                stopAudioPlayback();
                mRecorder.startPlayback();
                break;
            case R.id.stopButton:
                mRecorder.stop();
                if (mRecorder.sampleLength() > 0) {
                    mRecorderStop = true;
                }
                mVUMeter.resetAngle();
                invalidateOptionsMenu();
                break;
            case R.id.acceptButton:
                if (!mRecorder.sampleFile().exists()) {
                    Toast.makeText(SoundRecorder.this, R.string.file_deleted,
                            Toast.LENGTH_SHORT).show();
                    finish();
                }
                mRecorder.stop();
                mRecorderProcessed = true;
                saveSample();
                mVUMeter.resetAngle();
                if (mExitAfterRecord)
                    finish();
                break;
            case R.id.discardButton:
                mRecorder.delete();
                mRecorderProcessed = true;
                mVUMeter.resetAngle();
                break;
        }
    }

    private void openOptionDialog(int optionType) {
        final Context dialogContext = new ContextThemeWrapper(this,
                android.R.style.Theme_Holo_Dialog);
        final Resources res = dialogContext.getResources();
        final LayoutInflater dialogInflater = (LayoutInflater) dialogContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        final ArrayAdapter<Integer> adapter = new ArrayAdapter<Integer>(this,
                android.R.layout.simple_list_item_single_choice) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = dialogInflater.inflate(
                            android.R.layout.simple_list_item_single_choice, parent, false);
                }

                final int resId = this.getItem(position);
                ((TextView)convertView).setText(resId);
                return convertView;
            }
        };
        if (optionType == SETTING_TYPE_FILE_TYPE) {
            adapter.add(R.string.format_setting_amr_item);
            adapter.add(R.string.format_setting_3gpp_item);
            if (mWAVSupport) {
                adapter.add(R.string.format_setting_wav_item);
            }
        } else if (optionType == SETTING_TYPE_STORAGE_LOCATION) {
            adapter.add(R.string.storage_setting_local_item);
            adapter.add(R.string.storage_setting_sdcard_item);
        }

        final DialogInterface.OnClickListener clickListener =
                new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                final int resId = adapter.getItem(which);
                switch (resId) {
                    case R.string.format_setting_amr_item:
                        mRequestedType = AUDIO_AMR;
                        mFileType = 0;
                        mPrefsStoragePathEditor.putString("requestedType", mRequestedType);
                        mPrefsStoragePathEditor.putInt("fileType", mFileType);
                        mPrefsStoragePathEditor.commit();
                        break;
                    case R.string.format_setting_3gpp_item:
                        mRequestedType = AUDIO_3GPP;
                        mFileType = 1;
                        mPrefsStoragePathEditor.putString("requestedType", mRequestedType);
                        mPrefsStoragePathEditor.putInt("fileType", mFileType);
                        mPrefsStoragePathEditor.commit();
                        //Keep 40KB size in the Recording file for Mpeg4Writer to write Moov.
                        if((mMaxFileSize !=-1)&&(mMaxFileSize>40*1024))
                            mMaxFileSize =mMaxFileSize-40*1024;
                        break;
                    case R.string.format_setting_wav_item:
                        mRequestedType = AUDIO_WAVE_2CH_LPCM;
                        mFileType = 2;
                        mPrefsStoragePathEditor.putString("requestedType", mRequestedType);
                        mPrefsStoragePathEditor.putInt("fileType", mFileType);
                        mPrefsStoragePathEditor.commit();
                        break;
                    case R.string.storage_setting_sdcard_item:
                        mStoragePath = getSDPath(SoundRecorder.this) + "/SoundRecorder";
                        mPath = 1;
                        mPrefsStoragePathEditor.putString("storagePath", mStoragePath);
                        mPrefsStoragePathEditor.putInt("path", mPath);
                        mPrefsStoragePathEditor.commit();
                        break;
                    case R.string.storage_setting_local_item:
                        mStoragePath = STORAGE_PATH_LOCAL_PHONE;
                        mPath = 0;
                        mPrefsStoragePathEditor.putString("storagePath", mStoragePath);
                        mPrefsStoragePathEditor.putInt("path", mPath);
                        mPrefsStoragePathEditor.commit();
                        break;

                    default: {
                        Log.e(TAG, "Unexpected resource: "
                                + getResources().getResourceEntryName(resId));
                    }
                }
            }
        };

        AlertDialog ad = null;
        if (optionType == SETTING_TYPE_STORAGE_LOCATION) {
            ad = new AlertDialog.Builder(this)
                    .setTitle(R.string.storage_setting)
                    .setSingleChoiceItems(adapter, mPath, clickListener)
                    .create();
        } else if (optionType == SETTING_TYPE_FILE_TYPE) {
            ad = new AlertDialog.Builder(this)
                    .setTitle(R.string.format_setting)
                    .setSingleChoiceItems(adapter, mFileType, clickListener)
                    .create();
        }
        ad.setCanceledOnTouchOutside(true);
        ad.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        if (!mRemainingTimeCalculator.hasExternalStorage()) {
            menu.removeItem(R.id.menu_item_storage);
        }

        // Remove view recordings if there isn't an activity that can handle it
        Uri startDir = Uri.fromFile(Environment.getExternalStorageDirectory());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(startDir, "resource/folder");
        final PackageManager packageManager = getPackageManager();
        List<ResolveInfo> info = packageManager.queryIntentActivities(intent, 0);
        if (info.size() == 0) {
            menu.removeItem(R.id.menu_item_view_recordings);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        menu.findItem(R.id.menu_item_keyboard).setEnabled(mRecorder.state() == Recorder.IDLE_STATE);
        menu.findItem(R.id.menu_item_filetype).setEnabled(mRecorder.state() == Recorder.IDLE_STATE);
        if (mRemainingTimeCalculator.hasExternalStorage()) {
            menu.findItem(R.id.menu_item_storage).setEnabled(mRecorder.state() == Recorder.IDLE_STATE);
        }
        if (SystemProperties.getBoolean("debug.soundrecorder.enable", false)) {
            menu.findItem(R.id.menu_item_keyboard).setVisible(true);
        } else {
            menu.findItem(R.id.menu_item_keyboard).setVisible(false);
        }

        if (mRecorderStop && !mRecorderProcessed) {
            menu.findItem(R.id.menu_item_keyboard).setEnabled(false);
            menu.findItem(R.id.menu_item_filetype).setEnabled(false);
            if (mRemainingTimeCalculator.hasExternalStorage()) {
                menu.findItem(R.id.menu_item_storage).setEnabled(false);
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_item_keyboard:
                if(mRecorder.state() == Recorder.IDLE_STATE) {
                    InputMethodManager inputMgr =
                            (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                    inputMgr.toggleSoftInput(0, 0);
                }
                break;
            case R.id.menu_item_filetype:
                if(mRecorder.state() == Recorder.IDLE_STATE) {
                    openOptionDialog(SETTING_TYPE_FILE_TYPE);
                }
                break;
            case R.id.menu_item_storage:
                if(mRecorder.state() == Recorder.IDLE_STATE) {
                    openOptionDialog(SETTING_TYPE_STORAGE_LOCATION);
                }
                break;
            case R.id.menu_item_view_recordings:
                try {
                    Uri startDir = Uri.fromFile(new File(mStoragePath));
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(startDir, "resource/folder");
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                } catch (ActivityNotFoundException ex) {
                    // Ignore. This shouldn't happen. we have checked previously.
                }
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    /*
     * Handle the "back" hardware key.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            switch (mRecorder.state()) {
                case Recorder.IDLE_STATE:
                    if (mRecorderProcessed || !mRecorderStop) {
                        finish();
                    } else {
                        saveSample();
                    }
                    break;
                case Recorder.PLAYING_STATE:
                    mRecorder.stop();
                    break;
                case Recorder.PAUSE_STATE:
                case Recorder.RECORDING_STATE:
                    try {
                        Thread.sleep(BACK_KEY_WAIT);
                    } catch (InterruptedException ex) {
                    }
                    mRecorder.stop();
                    saveSample();
                    break;
            }
            return true;
        } else {
            return super.onKeyDown(keyCode, event);
        }
    }

    // Voicememo Adding UI choice for the user to get the format needed
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
         Log.v(TAG, "dispatchKeyEvent with key event" + event);

    if(event.getKeyCode() == KeyEvent.KEYCODE_6 && event.getAction() == event.ACTION_UP){
       //Ignore ACTION_DOWN to avoid showing error dialog twice
       if((mAudioSourceType == MediaRecorder.AudioSource.VOICE_CALL) ||
          (mAudioSourceType == MediaRecorder.AudioSource.VOICE_DOWNLINK)||
          (mAudioSourceType == MediaRecorder.AudioSource.VOICE_UPLINK ) ||
          ((mAudioSourceType == MediaRecorder.AudioSource.MIC) &&
           (mAudioManager.getMode() == AudioManager.MODE_IN_CALL))) {
          mAudioSourceType = MediaRecorder.AudioSource.MIC;//Default type
          Resources res = getResources();
          String message = null;
          message = res.getString(R.string.error_mediadb_aacincall);
          new AlertDialog.Builder(this)
          .setTitle(R.string.app_name)
          .setMessage(message)
          .setPositiveButton(R.string.button_ok, null)
          .setCancelable(false)
          .show();
          return super.dispatchKeyEvent(event);
       }
    }

    if((event.getKeyCode() == KeyEvent.KEYCODE_1 || event.getKeyCode() == KeyEvent.KEYCODE_2)
         && (event.getAction() == event.ACTION_UP)){
       //Ignore ACTION_DOWN to avoid showing error dialog twice
       if((mAudioManager.getMode() != AudioManager.MODE_IN_CALL) ||
         (mRequestedType == AUDIO_AAC_MP4)) {
          mAudioSourceType = MediaRecorder.AudioSource.MIC;//Default type
          Resources res = getResources();
          String message = null;
          if(mAudioManager.getMode() != AudioManager.MODE_IN_CALL) {
            message = res.getString(R.string.error_mediadb_incall);
          } else {
            message = res.getString(R.string.error_mediadb_aacincall);
          }
          new AlertDialog.Builder(this)
          .setTitle(R.string.app_name)
          .setMessage(message)
          .setPositiveButton(R.string.button_ok, null)
          .setCancelable(false)
          .show();
          return super.dispatchKeyEvent(event);
       }
    }
        // Intercept some events before they get dispatched to our views.
        boolean ret = false;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_0: // MIC source (Camcorder)
            {
              Log.e(TAG, "Selected MIC Source: Key Event" + KeyEvent.KEYCODE_0);
              mAudioSourceType = MediaRecorder.AudioSource.MIC;
              if ((mAudioManager.getMode() == AudioManager.MODE_IN_CALL) &&
                  (event.getAction() == event.ACTION_UP)) {
                  mAudioSourceType = MediaRecorder.AudioSource.VOICE_UPLINK;
                  Log.e(TAG, "Selected Voice Tx only Source: sourcetype" + mAudioSourceType);
              }
              ret = true;
              break;
            }

            case KeyEvent.KEYCODE_1: // Voice Rx Only (Only during Call(
            {
              Log.e(TAG, "Selected Voice Rx only Source: Key Event" + KeyEvent.KEYCODE_1);
              mAudioSourceType = MediaRecorder.AudioSource.VOICE_DOWNLINK;
              ret = true;
              break;
            }

            case KeyEvent.KEYCODE_2: // Voice Rx+Tx (Only during Call)
            {
              Log.e(TAG, "Selected Voice Tx+Rx Source: Key Event" + KeyEvent.KEYCODE_2);
              mAudioSourceType = MediaRecorder.AudioSource.VOICE_CALL;
              ret = true;
              break;
            }

            case KeyEvent.KEYCODE_3: // Selected AMR codec type
            {
              Log.e(TAG, "Selected AUDIO_AMR Codec: Key Event" + KeyEvent.KEYCODE_3);
              mRequestedType = AUDIO_AMR;
              ret = true;
              break;
            }

            case KeyEvent.KEYCODE_4: // Selected EVRC codec type
            {
              Log.e(TAG, "Selected Voice AUDIO_EVRC Codec: Key Event" + KeyEvent.KEYCODE_4);
              mRequestedType = AUDIO_EVRC;
              ret = true;
              break;
            }

            case KeyEvent.KEYCODE_5: // Selected QCELP codec type
            {
              Log.e(TAG, "Selected AUDIO_QCELP Codec: Key Event" + KeyEvent.KEYCODE_5);
              mRequestedType = AUDIO_QCELP;
              ret = true;
              break;
            }
            case KeyEvent.KEYCODE_6: // Selected AAC codec type
            {
              Log.e(TAG, "Selected AUDIO_AAC_MP4 Codec: Key Event" + KeyEvent.KEYCODE_6);
              mRequestedType = AUDIO_AAC_MP4;
              ret = true;
              break;
            }
            case KeyEvent.KEYCODE_7: // Selected 6 channel wave lpcm codec type
            {
              if (true == bSSRSupported) {
                Log.e(TAG, "Selected multichannel AAC Codec: Key Event" + KeyEvent.KEYCODE_7);
                mRequestedType = AUDIO_AAC_5POINT1_CHANNEL;
                ret = true;
              }
              break;
            }
            case KeyEvent.KEYCODE_8: // Selected 6 channel AAC recording
            {
                if (true == bSSRSupported) {
                Log.e(TAG, "Selected linear pcm Codec: Key Event" + KeyEvent.KEYCODE_7);
                mRequestedType = AUDIO_WAVE_6CH_LPCM;
                ret = true;
              }
              break;
            }
            case KeyEvent.KEYCODE_9: // Selected amr-wb codec type in .awb file format
            {
              Log.e(TAG, "### Selected amr wb Codec in .awb: Key Event" + KeyEvent.KEYCODE_8);
              mRequestedType = AUDIO_AMR_WB;
              mAudioOutputFormat = MediaRecorder.OutputFormat.AMR_WB;
              mAmrWidebandExtension = ".awb";
              ret = true;
              break;
            }
            case KeyEvent.KEYCODE_A: // Selected amr-wb codec type in .3gpp file format
            {
              Log.e(TAG, "### Selected awr wb Codec in 3gp: Key Event" + KeyEvent.KEYCODE_9);
              mRequestedType = AUDIO_AMR_WB;
              mAmrWidebandExtension = ".3gpp";
              mAudioOutputFormat = MediaRecorder.OutputFormat.THREE_GPP;
              ret = true;
              break;
            }

            default:
                break;
        }

        return ret?ret:super.dispatchKeyEvent(event);
    }

    @Override
    public void onStop() {
        mRecorder.stop();
        super.onStop();
    }

    @Override
    protected void onPause() {
        // Stop listening for phone state changes.
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        mSampleInterrupted = mRecorder.state() == Recorder.RECORDING_STATE;
        mRecorder.stop();
        if (mRecorder.sampleLength() > 0) {
            mRecorderStop = true;
        }
        mAudioManager.abandonAudioFocus(mAudioFocusListener);
        super.onPause();
    }

    /*
     * If we have just recorded a smaple, this adds it to the media data base
     * and sets the result to the sample's URI.
     */
    private boolean saveSample() {
        Uri uri = null;

        if (mRecorder.sampleLength() <= 0) {
            mRecorder.delete();
            return false;
        }

        try {
            uri = this.addToMediaDB(mRecorder.sampleFile());
        } catch(UnsupportedOperationException ex) {  // Database manipulation failure
            return false;
        } finally {
            if (uri == null && !mDataExist) {
                return false;
            }
        }

        // reset mRecorder and restore UI.
        mRecorder.clear();
        updateUi();
        setResult(RESULT_OK, new Intent().setData(uri));
        return true;
    }

    /*
     * Called on destroy to unregister the SD card mount event receiver.
     */
    @Override
    public void onDestroy() {
        if (mSDCardMountEventReceiver != null) {
            unregisterReceiver(mSDCardMountEventReceiver);
            mSDCardMountEventReceiver = null;
        }
        if (mPowerOffReceiver != null) {
            unregisterReceiver(mPowerOffReceiver);
            mPowerOffReceiver = null;
        }
        super.onDestroy();
    }

    /*
     * Registers an intent to listen for ACTION_SHUTDOWN notifications.
     */
    private void registerPowerOffListener() {
        if (mPowerOffReceiver == null) {
            mPowerOffReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action.equals(Intent.ACTION_SHUTDOWN)) {
                        if (mRecorder != null) {
                            mRecorder.stop();
                            mRecorder.delete();
                        }
                    }
                }
            };
            IntentFilter iFilter = new IntentFilter();
            iFilter.addAction(Intent.ACTION_SHUTDOWN);
            registerReceiver(mPowerOffReceiver, iFilter);
        }
    }

    /*
     * Registers an intent to listen for ACTION_MEDIA_EJECT/ACTION_MEDIA_MOUNTED
     * notifications.
     */
    private void registerExternalStorageListener() {
        if (mSDCardMountEventReceiver == null) {
            mSDCardMountEventReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action.equals(Intent.ACTION_MEDIA_EJECT)) {
                        mRecorder.delete();
                    } else if (action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
                        mSampleInterrupted = false;
                        updateUi();
                    }
                }
            };
            IntentFilter iFilter = new IntentFilter();
            iFilter.addAction(Intent.ACTION_MEDIA_EJECT);
            iFilter.addAction(Intent.ACTION_MEDIA_MOUNTED);
            iFilter.addDataScheme("file");
            registerReceiver(mSDCardMountEventReceiver, iFilter);
        }
    }

    /*
     * A simple utility to do a query into the databases.
     */
    private Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            ContentResolver resolver = getContentResolver();
            if (resolver == null) {
                return null;
            }
            return resolver.query(uri, projection, selection, selectionArgs, sortOrder);
         } catch (UnsupportedOperationException ex) {
            return null;
        }
    }

    /*
     * Add the given audioId to the playlist with the given playlistId; and maintain the
     * play_order in the playlist.
     */
    private void addToPlaylist(ContentResolver resolver, int audioId, long playlistId) {
        String[] cols = new String[] {
                "count(*)"
        };
        Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId);
        Cursor cur = resolver.query(uri, cols, null, null, null);
        cur.moveToFirst();
        final int base = cur.getInt(0);
        cur.close();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, Integer.valueOf(base + audioId));
        values.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, audioId);
        resolver.insert(uri, values);
    }

    /*
     * Obtain the id for the default play list from the audio_playlists table.
     */
    private int getPlaylistId(Resources res) {
        Uri uri = MediaStore.Audio.Playlists.getContentUri("external");
        final String[] ids = new String[] { MediaStore.Audio.Playlists._ID };
        final String where = MediaStore.Audio.Playlists.NAME + "=?";
        final String[] args = new String[] { "My recordings" };
        Cursor cursor = query(uri, ids, where, args, null);
        if (cursor == null) {
            Log.v(TAG, "query returns null");
        }
        int id = -1;
        if (cursor != null) {
            cursor.moveToFirst();
            if (!cursor.isAfterLast()) {
                id = cursor.getInt(0);
            }
        }
        cursor.close();
        return id;
    }

    /*
     * Create a playlist with the given default playlist name, if no such playlist exists.
     */
    private Uri createPlaylist(Resources res, ContentResolver resolver) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Playlists.NAME, "My recordings");
        Uri uri = resolver.insert(MediaStore.Audio.Playlists.getContentUri("external"), cv);
        if (uri == null) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.error_mediadb_new_record)
                .setPositiveButton(R.string.button_ok, null)
                .setCancelable(false)
                .show();
        }
        return uri;
    }

    /*
     * Adds file and returns content uri.
     */
    private Uri addToMediaDB(File file) {
        Resources res = getResources();
        ContentValues cv = new ContentValues();
        long current = System.currentTimeMillis();
        long modDate = file.lastModified();
        String title = mRecorder.getStartRecordingTime();
        long sampleLengthMillis = mRecorder.sampleLength() * 1000L;
        mLastFileName = file.getAbsolutePath().substring(
                file.getAbsolutePath().lastIndexOf("/")+1, file.getAbsolutePath().length()).replace("-", "");

        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        final String[] ids = new String[] { MediaStore.Audio.Playlists._ID };
        final String where = MediaStore.Audio.Playlists.DATA + "=?";
        final String[] args = new String[] { file.getAbsolutePath() };
        Cursor cursor = query(uri, ids, where, args, null);

        if (cursor != null && cursor.getCount() > 0) {
            mDataExist = true;
            cursor.close();
            return null;
        }

        // Label the recorded audio file as MUSIC so that the file
        // will be displayed automatically
        cv.put(MediaStore.Audio.Media.IS_MUSIC, "1");

        cv.put(MediaStore.Audio.Media.TITLE, title);
        cv.put(MediaStore.Audio.Media.DATA, file.getAbsolutePath());
        cv.put(MediaStore.Audio.Media.DATE_ADDED, (int) (current / 1000));
        cv.put(MediaStore.Audio.Media.DATE_MODIFIED, (int) (modDate / 1000));
        cv.put(MediaStore.Audio.Media.DURATION, sampleLengthMillis);
        cv.put(MediaStore.Audio.Media.MIME_TYPE, mRequestedType);
        cv.put(MediaStore.Audio.Media.ARTIST,
                res.getString(R.string.audio_db_artist_name));
        cv.put(MediaStore.Audio.Media.ALBUM,
                res.getString(R.string.audio_db_album_name));
        Log.d(TAG, "Inserting audio record: " + cv.toString());
        ContentResolver resolver = getContentResolver();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Log.d(TAG, "ContentURI: " + base);
        Uri result;
        try {
            result = resolver.insert(base, cv);
        } catch (Exception exception) {
            result = null;
        }
        if (result == null) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.error_mediadb_new_record)
                .setPositiveButton(R.string.button_ok, null)
                .setCancelable(false)
                .show();
            return null;
        }
        if (getPlaylistId(res) == -1) {
            createPlaylist(res, resolver);
        }
        int audioId = Integer.valueOf(result.getLastPathSegment());
        addToPlaylist(resolver, audioId, getPlaylistId(res));

        // Notify those applications such as Music listening to the
        // scanner events that a recorded audio file just created.
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, result));
        return result;
    }

    /**
     * Update the big MM:SS timer. If we are in playback, also update the
     * progress bar.
     */
    private void updateTimerView() {
        Resources res = getResources();
        int state = mRecorder.state();

        boolean ongoing = state == Recorder.RECORDING_STATE || state == Recorder.PLAYING_STATE;

        long time = ongoing ? mRecorder.progress() : mRecorder.sampleLength();
        String timeStr = String.format(mTimerFormat, time/60, time%60);
        mTimerView.setText(timeStr);

        if (state == Recorder.PLAYING_STATE) {
            mStateProgressBar.setProgress((int)(100*time/mRecorder.sampleLength()));
        } else if (state == Recorder.RECORDING_STATE) {
            updateTimeRemaining();
        }

        if (ongoing)
            mHandler.postDelayed(mUpdateTimer, 1000);
    }

    /*
     * Called when we're in recording state. Find out how much longer we can
     * go on recording. If it's under 5 minutes, we display a count-down in
     * the UI. If we've run out of time, stop the recording.
     */
    private void updateTimeRemaining() {
        long t = mRemainingTimeCalculator.timeRemaining();

        if (t <= 0) {
            mSampleInterrupted = true;

            int limit = mRemainingTimeCalculator.currentLowerLimit();
            switch (limit) {
                case RemainingTimeCalculator.DISK_SPACE_LIMIT:
                    mErrorUiMessage
                        = getResources().getString(R.string.storage_is_full);
                    break;
                case RemainingTimeCalculator.FILE_SIZE_LIMIT:
                    mErrorUiMessage
                        = getResources().getString(R.string.max_length_reached);
                    break;
                default:
                    mErrorUiMessage = null;
                    break;
            }

            mRecorder.stop();
            return;
        }

        Resources res = getResources();
        String timeStr = "";

        // display available time, if less than 10 minutes
        if (t < 599)
        {
            timeStr = String.format(mTimerFormat, t/60, t%60);
        }

        mStateMessage1.setText(timeStr);
    }

    /**
     * Shows/hides the appropriate child views for the new state.
     */
    private void updateUi() {
        Resources res = getResources();

        switch (mRecorder.state()) {
            case Recorder.IDLE_STATE:
                if (mRecorder.sampleLength() == 0) {
                    mRecordButton.setImageResource(R.drawable.record);
                    mRecordButton.setEnabled(true);
                    mRecordButton.setFocusable(false);
                    mPlayButton.setEnabled(false);
                    mPlayButton.setFocusable(false);
                    mStopButton.setEnabled(false);
                    mStopButton.setFocusable(false);

                    mStateMessage1.setVisibility(View.INVISIBLE);
                    // No idle led res available, so just inactive mStateLED.
                    mStateLED.setVisibility(View.INVISIBLE);
                    //mStateLED.setImageResource(R.drawable.idle_led);
                    mStateMessage2.setVisibility(View.INVISIBLE);

                    mExitButtons.setVisibility(View.INVISIBLE);

                    mStateProgressBar.setVisibility(View.INVISIBLE);
                } else {
                    mRecordButton.setImageResource(R.drawable.record);
                    mRecordButton.setEnabled(false);
                    mRecordButton.setFocusable(false);
                    mPlayButton.setEnabled(true);
                    mPlayButton.setFocusable(true);
                    mStopButton.setEnabled(false);
                    mStopButton.setFocusable(false);

                    mStateMessage1.setVisibility(View.INVISIBLE);
                    mStateLED.setVisibility(View.VISIBLE);
                    mStateLED.setImageResource(R.drawable.stopped_led);
                    mStateMessage2.setVisibility(View.VISIBLE);
                    mStateMessage2.setText(res.getString(R.string.recording_finished));

                    mExitButtons.setVisibility(View.VISIBLE);

                    mStateProgressBar.setVisibility(View.INVISIBLE);
                }

                if (mSampleInterrupted) {
                    //TODO: Set decent message and icon resources
                    mStateMessage2.setVisibility(View.VISIBLE);
                    mStateMessage2.setText(res.getString(R.string.recording_stopped));
                }

                if (mErrorUiMessage != null) {
                    mStateMessage1.setText(mErrorUiMessage);
                    mStateMessage1.setVisibility(View.VISIBLE);
                }

                break;
            case Recorder.RECORDING_STATE:
                mRecordButton.setImageResource(R.drawable.pause);
                mRecordButton.setEnabled(true);
                mRecordButton.setFocusable(true);
                mPlayButton.setEnabled(false);
                mPlayButton.setFocusable(false);
                mStopButton.setEnabled(true);
                mStopButton.setFocusable(true);

                mStateMessage1.setVisibility(View.VISIBLE);
                mStateLED.setVisibility(View.VISIBLE);
                mStateLED.setImageResource(R.drawable.recording_led);
                mStateMessage2.setVisibility(View.VISIBLE);
                mStateMessage2.setText(res.getString(R.string.recording));

                mExitButtons.setVisibility(View.INVISIBLE);

                mStateProgressBar.setVisibility(View.INVISIBLE);

                break;
            case Recorder.PLAYING_STATE:
                mRecordButton.setEnabled(false);
                mRecordButton.setFocusable(false);
                mPlayButton.setEnabled(false);
                mPlayButton.setFocusable(false);
                mStopButton.setEnabled(true);
                mStopButton.setFocusable(true);

                mStateMessage1.setVisibility(View.INVISIBLE);
                mStateLED.setVisibility(View.INVISIBLE);
                mStateMessage2.setVisibility(View.INVISIBLE);

                mExitButtons.setVisibility(View.VISIBLE);

                mStateProgressBar.setVisibility(View.VISIBLE);

                break;
            case Recorder.PAUSE_STATE:
                mRecordButton.setImageResource(R.drawable.record);
                mRecordButton.setEnabled(true);
                mRecordButton.setFocusable(true);
                mPlayButton.setEnabled(false);
                mPlayButton.setFocusable(false);
                mStopButton.setEnabled(true);
                mStopButton.setFocusable(true);

                mStateMessage1.setVisibility(View.VISIBLE);
                mStateLED.setVisibility(View.VISIBLE);
                mStateLED.setImageResource(R.drawable.paused_led);
                mStateMessage2.setVisibility(View.VISIBLE);
                mStateMessage2.setText(res.getString(R.string.recording_paused));

                mExitButtons.setVisibility(View.INVISIBLE);
                mVUMeter.resetAngle();

                mStateProgressBar.setVisibility(View.INVISIBLE);

                break;
        }

        updateTimerView();
        mVUMeter.invalidate();
    }

    /*
     * Called when Recorder changed it's state.
     */
    public void onStateChanged(int state) {
        if (state == Recorder.PLAYING_STATE || state == Recorder.RECORDING_STATE) {
            mSampleInterrupted = false;
            mErrorUiMessage = null;
        }

        if (state == Recorder.RECORDING_STATE) {
            mWakeLock.acquire(); // we don't want to go to sleep while recording
        } else {
            if (mWakeLock.isHeld())
                mWakeLock.release();
        }

        updateUi();
        invalidateOptionsMenu();
    }

    /*
     * Called when MediaPlayer encounters an error.
     */
    public void onError(int error) {
        Resources res = getResources();
        boolean isExit = false;

        String message = null;
        switch (error) {
            case Recorder.SDCARD_ACCESS_ERROR:
                message = res.getString(R.string.error_sdcard_access);
                break;
            case Recorder.IN_CALL_RECORD_ERROR:
                message = res.getString(R.string.in_call_record_error);
                isExit = true;
                break;
            case Recorder.INTERNAL_ERROR:
                message = res.getString(R.string.error_app_internal);
                isExit = true;
                break;
            case Recorder.INTERNAL_ERROR_MAYBE_IN_USE:
                message = res.getString(R.string.error_app_internal_recorder);
                isExit = true;
                break;
            case Recorder.UNSUPPORTED_FORMAT:
                message = res.getString(R.string.error_app_unsupported);
                isExit = true;
                break;
        }
        if (message != null) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(message)
                .setPositiveButton(R.string.button_ok, (true==isExit)?
                    (new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            finish();
                        }}):null)
                .setCancelable(false)
                .show();
        }
    }

    static String getSDPath(Context context) {
        String sd = null;
        StorageManager mStorageManager = (StorageManager) context
                .getSystemService(Context.STORAGE_SERVICE);
        StorageVolume[] volumes = mStorageManager.getVolumeList();
        for (int i = 0; i < volumes.length; i++) {
            if (volumes[i].isRemovable() && volumes[i].allowMassStorage()
                    && volumes[i].getDescription(context).contains("SD")) {
                sd = volumes[i].getPath();
            }
        }
        return sd;
    }

    private String getSDState(Context context) {
        StorageManager mStorageManager = (StorageManager) context
                .getSystemService(Context.STORAGE_SERVICE);
        return mStorageManager.getVolumeState(getSDPath(context));
    }
}
