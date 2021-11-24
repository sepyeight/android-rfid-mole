/*
 * Copyright (C) 2013 The Android Open Source Project
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
package it.epozzobon.nfcrelay;

import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TagLostException;
import android.nfc.tech.IsoDep;
import android.os.SystemClock;

import com.example.android.common.logger.Log;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Locale;

/**
 * Callback class, invoked when an NFC card is scanned while the device is running in reader mode.
 * <p>
 * Reader mode can be invoked by calling NfcAdapter
 */
public class RelayClient implements NfcAdapter.ReaderCallback {
    private static final String TAG = "RelayClient";
    private static VictimConfig victimConfig = VictimConfig.victimST25TA;
    private static VictimConfig testConfig = VictimConfig.testID;


    private static final byte[] SELECT_OK_SW = {(byte) 0x90, (byte) 0x00};
    private static final String SELECT_APDU_HEADER = "00A40400";
    private static final long SELECT_TIMEOUT = 1000;

    private IsoDep mIsoDep;
    private boolean mCardReady = false;
    private Thread mAidSelectorThread = null;
    private long mStep0, mStep1;
    private long mLastTransceive = 0;
    // Weak reference to prevent retain loop. mResponseCallback is responsible for exiting
    // foreground mode before it becomes invalid (e.g. during onPause() or onStop()).
    private WeakReference<ResponseCallback> mResponseCallback;

    public interface ResponseCallback {
        public void onCardDisconnected();

        public void onTagDiscovered(Tag card);

        public void onAidSelect(boolean ok, String response);

        public void onResponseReceived(boolean ok, String response);
    }

    public RelayClient(ResponseCallback responseCallback) {
        mResponseCallback = new WeakReference<ResponseCallback>(responseCallback);
    }


    /**
     * Callback when a new tag is discovered by the system.
     *
     * <p>Communication with the card should take place here.
     *
     * @param card Discovered tag
     */
    @Override
    public void onTagDiscovered(Tag card) {
        Log.i(TAG, "New tag discovered");
        mResponseCallback.get().onTagDiscovered(card);

        mCardReady = false;

        if (mAidSelectorThread != null) {
            mAidSelectorThread.interrupt();
            mAidSelectorThread = null;
        }

        // Android's Host-based Card Emulation (HCE) feature implements the ISO-DEP (ISO 14443-4)
        // protocol.
        //
        // In order to communicate with a device using HCE, the discovered tag should be processed
        // using the IsoDep class.
        mIsoDep = IsoDep.get(card);
        mIsoDep.setTimeout(1000);
        try {
            mIsoDep.connect();
            mStep0 = SystemClock.elapsedRealtime();
        } catch (IOException ex) {
            Log.e(TAG, ex.getMessage());
        }

        mAidSelectorThread = new Thread() {
            public void run() {
                selectTask();
            }
        };
        mAidSelectorThread.start();
    }


    private void selectTask() {
        // This task keeps the AID selected on the card to keep the card alive,
        // while we wait for the challenge
        Thread t = Thread.currentThread();
        while (!t.isInterrupted() && mIsoDep != null) {
            long now = SystemClock.elapsedRealtime();

            if (now - mLastTransceive >= SELECT_TIMEOUT) {
                selectAID();
            }

            try {
                Thread.sleep(SELECT_TIMEOUT);
            } catch (InterruptedException ex) {
                break;
            }
        }
    }

    public byte[] askAID(byte[] aid) {
        if (mIsoDep == null) {
            Log.e(TAG, "aid received, but no card is present.");
            return null;
        }

        long step2 = SystemClock.elapsedRealtime();
        byte[] response = submitAID(aid);
        if (response == null) {
            return null;
        }

        long step3 = SystemClock.elapsedRealtime();
        Log.i(TAG, String.format(Locale.ENGLISH, "Latencies: %dms %dms %dms", mStep1 - mStep0, step2 - mStep1, step3 - step2));

        return response;
    }


    public byte[] askChallenge(byte[] challenge) {
        if (mIsoDep == null) {
            Log.e(TAG, "Challenge received, but no card is present.");
            return null;
        }

        long step2 = SystemClock.elapsedRealtime();
        byte[] response = submitChallenge(challenge);
        if (response == null) {
            return null;
        }

        long step3 = SystemClock.elapsedRealtime();
        Log.i(TAG, String.format(Locale.ENGLISH, "Latencies: %dms %dms %dms", mStep1 - mStep0, step2 - mStep1, step3 - step2));

        return response;
    }

    private byte[] submitAID(byte[] aid) {
        String hexChallenge = Hex.hex(aid);
        Log.i(TAG, "AID is " + hexChallenge);
        if (!selectAID(aid)) {
            return null;
        } else {
            return new byte[]{0x0F};
        }
    }


    private byte[] submitChallenge(byte[] challenge) {
        String hexChallenge = Hex.hex(challenge);
        Log.i(TAG, "Challenge is " + hexChallenge);

        byte[] result = this.transceive(hexChallenge);
        if (result == null) {
            return null;
        }

        int resultLength = result.length;
        byte[] statusWord = new byte[]{result[resultLength - 2], result[resultLength - 1]};

        boolean success = Arrays.equals(SELECT_OK_SW, statusWord);
        byte[] payload = Arrays.copyOf(result, resultLength - 2);

        String hexResult = Hex.hex(result);
        Log.i(TAG, "Received: " + hexResult);
        mResponseCallback.get().onResponseReceived(success, hexResult);
        if (success) {
            return payload;
        }

        return null;
    }

    private boolean selectAID() {
        return selectAID(testConfig.aid);
    }

    private boolean selectAID(byte[] data) {
        // Build SELECT AID command for our loyalty card service.
        // This command tells the remote device which service we wish to communicate with.

        if(!VictimConfig.aidSavedHex.equals("")){
            data = Hex.bytes(VictimConfig.aidSavedHex);
        }

        String aid = Hex.hex(data);
        String command = "";
        if(aid.equals("")){
            return false;
        }

        if (aid.startsWith(SELECT_APDU_HEADER)) {
            Log.i(TAG, "Requesting remote AID: " + aid);
            command = aid;
        } else {
            Log.i(TAG, "Requesting default AID: " + aid);
            command = SELECT_APDU_HEADER + String.format("%02X", aid.length() / 2) + aid;
        }


        // Send command to remote device
        Log.i(TAG, "Sending: " + command);
        byte[] result = this.transceive(command);
        if (result == null) {
            return false;
        }

        // If AID is successfully selected, 0x9000 is returned as the status word (last 2
        // bytes of the result) by convention. Everything before the status word is
        // optional payload, which is used here to hold the account number.
        int resultLength = result.length;
        byte[] statusWord = {result[resultLength - 2], result[resultLength - 1]};
        Log.i(TAG, "Received: " + Hex.hex(result));
        byte[] payload = Arrays.copyOf(result, resultLength - 2);

        String hexPayload = Hex.hex(payload);
        Log.i(TAG, "Received: " + hexPayload);
        if (Arrays.equals(SELECT_OK_SW, statusWord)) {
            mStep1 = SystemClock.elapsedRealtime();
            Log.i(TAG, "Card connected and ready to answer challenges!");
            mCardReady = true;
        } else {
            Log.i(TAG, "AID select failed: " + hexPayload);
            mCardReady = false;
        }
        mResponseCallback.get().onAidSelect(mCardReady, hexPayload);
        return mCardReady;
    }

    public byte[] transceive(String hexPayload) {
        byte[] command = Hex.bytes(hexPayload);
        byte[] result;
        if (mIsoDep == null) {
            Log.e(TAG, "Card not connected");
        }
        try {
            result = mIsoDep.transceive(command);
            mLastTransceive = SystemClock.elapsedRealtime();
            return result;
        } catch (IOException ex) {
            if (ex instanceof TagLostException) {
                Log.e(TAG, "Card lost");
            } else {
                Log.e(TAG, "Error communicating with card: " + ex.getMessage());
            }
            mIsoDep = null;
            mCardReady = false;
            mResponseCallback.get().onCardDisconnected();
            return null;
        }
    }

}
