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

import android.app.Activity;
import android.content.SharedPreferences;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.example.android.common.logger.Log;

/**
 * Generic UI for sample discovery.
 */
public class CardReaderFragment extends Fragment implements RelayClient.ResponseCallback,
        ChallengeReceiver.ChallengeReceivedCallback {

    public static final String TAG = "CardReaderFragment";
    // Recommend NfcAdapter flags for reading from other Android devices. Indicates that this
    // activity is interested in NFC-A devices (including other Android devices), and that the
    // system should not check for the presence of NDEF-formatted data (e.g. Android Beam).
    public static int READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;
    public RelayClient mRelayClient;
    private ChallengeReceiver mChallengeReceiver;
    private TextView mCardStatusField;
    private TextView mLatencyField;
    private TextView mChallengeStatusField;
    private TextView mChallengeField;
    private TextView mResponseField;
    private Thread mUiThread;
    private String serverEndpoint;
    private EditText ipConig;
    private EditText portConfig;
    private Button submitBtn;
    private EditText aidConfig;
    private Button submitAIDBtn;

    private String DEFAULTCONFIG = "192.168.177.1:61017";

    /**
     * Called when sample is created. Displays generic UI with welcome text.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        mUiThread = Thread.currentThread();

        // Inflate the layout for this fragment
        View v = inflater.inflate(R.layout.main_fragment, container, false);
        if (v != null) {
            mCardStatusField = (TextView) v.findViewById(R.id.card_status_field);
            mChallengeStatusField = (TextView) v.findViewById(R.id.challenge_status_field);
            mResponseField = (TextView) v.findViewById(R.id.response_field);
            mChallengeField = (TextView) v.findViewById(R.id.challenge_field);
            mLatencyField = (TextView) v.findViewById(R.id.latency_field);
            ipConig = v.findViewById(R.id.ip_config);
            portConfig = v.findViewById(R.id.port_config);
            submitBtn = v.findViewById(R.id.submitBtn);
            aidConfig = v.findViewById(R.id.aid_config);
            submitAIDBtn = v.findViewById(R.id.submitAIDBtn);

            mCardStatusField.setText("Waiting card...");
            mChallengeStatusField.setText("Waiting challenge from server...");
            mResponseField.setText("-");
            mChallengeField.setText("-");
            mLatencyField.setText("-");
        }

        mRelayClient = new RelayClient(this);

        // Disable Android Beam and register our card reader callback
        enableReaderMode();

        return v;
    }

    @Override
    public void onPause() {
        super.onPause();
        disableReaderMode();
        disableChallengeReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        SharedPreferences sharedPreferences;
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.getContext());

        serverEndpoint = sharedPreferences.getString("server_url", DEFAULTCONFIG);
        VictimConfig.aidSavedHex = sharedPreferences.getString("aid_config", "");
        String ip = serverEndpoint.split(":")[0];
        String port = serverEndpoint.split(":")[1];
        ipConig.setText(ip);
        portConfig.setText(port);
        aidConfig.setText(VictimConfig.aidSavedHex);
        submitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sharedPreferences.edit().putString("server_url", ipConig.getText().toString() + ":" + portConfig.getText().toString()).commit();
            }
        });
        submitAIDBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sharedPreferences.edit().putString("aid_config", aidConfig.getText().toString()).commit();
            }
        });

        enableReaderMode();
        enableChallengeReceiver();
    }

    private void enableChallengeReceiver() {
        //serverEndpoint = "achernar.uberspace.de:61017";
        mChallengeReceiver = new ChallengeReceiver(serverEndpoint, this);
    }

    private void enableReaderMode() {
        Log.i(TAG, "Enabling reader mode");
        Activity activity = getActivity();
        NfcAdapter nfc = NfcAdapter.getDefaultAdapter(activity);
        if (nfc != null) {
            nfc.enableReaderMode(activity, mRelayClient, READER_FLAGS, null);
        }
    }

    private void disableChallengeReceiver() {
        mChallengeReceiver.stop();
        mChallengeReceiver = null;
    }

    private void disableReaderMode() {
        Log.i(TAG, "Disabling reader mode");
        Activity activity = getActivity();
        NfcAdapter nfc = NfcAdapter.getDefaultAdapter(activity);
        if (nfc != null) {
            nfc.disableReaderMode(activity);
        }
    }

    @Override
    public void onCardDisconnected() {
        setTextAsync(mCardStatusField, "Waiting card...");
    }

    @Override
    public void onAidSelect(boolean ok, String response) {
        if (ok) {
            setTextAsync(mCardStatusField, "Card Ready, AID selected!");
        } else {
            setTextAsync(mCardStatusField, "AID not selected: " + response);
        }
    }

    @Override
    public void onTagDiscovered(Tag card) {
        setTextAsync(mCardStatusField, "Card Ready, selecting AID...");
    }

    /**
     * 为了解决udp传递aid的问题
     *
     * @param c
     * @return
     */
    private boolean trySelectAID(ChallengeReceiver.Challenge c) {
        setTextAsync(mChallengeField, Hex.hex(c.getChallenge()));
        byte[] response = mRelayClient.askAID(c.getChallenge());
        if (response == null) {
            setTextAsync(mChallengeStatusField, "AID Unanswered.");
            return false;
        } else {
            c.answer(response);
            setTextAsync(mChallengeStatusField, "AID Answered!");
            return true;
        }
    }

    private boolean tryChallenge(ChallengeReceiver.Challenge c) {
        setTextAsync(mChallengeField, Hex.hex(c.getChallenge()));
        byte[] response = mRelayClient.askChallenge(c.getChallenge());
        if (response == null) {
            setTextAsync(mChallengeStatusField, "Challenge Unanswered.");
            return false;
        } else {
            c.answer(response);
            setTextAsync(mChallengeStatusField, "Challenge Answered!");
            return true;
        }
    }

    @Override
    public void onResponseReceived(boolean success, final String response) {
        setTextAsync(mResponseField, response);
    }

    @Override
    public void onAIDReceived(ChallengeReceiver.Challenge c) {
        Log.i(TAG, "AID Received");
        trySelectAID(c);
    }

    @Override
    public void onChallengeReceived(ChallengeReceiver.Challenge c) {
        Log.i(TAG, "Challenge Received");
        tryChallenge(c);
    }

    @Override
    public void onChallengeRelayed(ChallengeReceiver.Challenge c, long delay) {
        setTextAsync(mLatencyField, Long.toString(delay) + "ms");
    }

    private void setTextAsync(@NonNull final TextView view, @NonNull final String s) {
        if (Thread.currentThread() == mUiThread)
            view.setText(s);

        Activity activity = getActivity();
        if (activity == null)
            return;

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                view.setText(s);
            }
        });
    }
}
