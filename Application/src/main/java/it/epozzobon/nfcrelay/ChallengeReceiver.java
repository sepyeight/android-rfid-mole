package it.epozzobon.nfcrelay;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;

import android.os.Handler;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Log;

class ChallengeReceiver {
    private final String TAG = "ChallengeReceiver";
    private final String serverEndPoint;
    private DatagramSocket ds = null;
    private InetAddress remoteAddr;
    private int remotePort;
    private boolean stopClient;
    private Challenge mChallenge;
    private Handler handler;
    private WeakReference<ChallengeReceivedCallback> callback;

    public class Challenge {
        private byte[] challenge;
        private long before;  // timestamp of when challenge was created

        Challenge(byte[] src, int offset, int size) {
            before = SystemClock.elapsedRealtime();
            challenge = new byte[size];
            System.arraycopy(src, offset, challenge, 0, size);
        }

        public byte[] getChallenge() {
            return challenge;
        }

        public void answer(@NonNull final byte[] response) {
            String responseMsg = Hex.hex(response);
            // 52 is 'R' (message type = response)
            sendUdp(Hex.bytes("52" + responseMsg));
            long after = SystemClock.elapsedRealtime();
            long delay = after-before;
            Log.i(TAG, String.format("Relaying challenge took %dms", delay));
            callback.get().onChallengeRelayed(this, delay);
        }
    }

    public interface ChallengeReceivedCallback {
        /**
         * 解决aid传递问题
         */
        public void onAIDReceived(Challenge c);
        public void onChallengeReceived(Challenge c);
        public void onChallengeRelayed(Challenge c, long delay);
    }

    ChallengeReceiver(final String serverEndPoint, ChallengeReceivedCallback cb) {
        callback = new WeakReference<ChallengeReceivedCallback>(cb);
        this.serverEndPoint = serverEndPoint;
        this.handler = new android.os.Handler();
        new Thread() { public void run() { recvTask(); }}.start();
    }

    @Override
    public void finalize() {
        stop();
    }

    private void recvTask() {
        Thread t = Thread.currentThread();
        t.setName("UDP Receiver Thread " + t.getName());
        String[] pieces = serverEndPoint.split(":", 2);

        try {
            remoteAddr = InetAddress.getByName(pieces[0]);
            remotePort = Integer.parseInt(pieces[1]);
        } catch (UnknownHostException ex) {
            Log.e(TAG, String.format("UDP socket creation failed: %s", ex.getMessage()));
            return;
        }

        try {
            ds = new DatagramSocket();
        } catch (SocketException ex) {
            Log.e(TAG, String.format("UDP socket creation failed: %s", ex.getMessage()));
            return;
        }

        sendKeepAliveAsync();
        receiveMessagesLoop();
    }

    void stop() {
        stopClient = true;
        if (ds != null) {
            ds.close();
            ds = null;
        }
    }

    private void sendUdp(byte[] bytes) {
        DatagramPacket dp = new DatagramPacket(bytes, bytes.length, remoteAddr, remotePort);
        try {
            ds.send(dp);
            Log.i(TAG, "UDP message sent");
        } catch (IOException ex) {
            Log.e(TAG, String.format("UDP socket send failed: %s", ex.getMessage()));
        }
    }

    /* Message Reception */

    private void receiveMessagesLoop() {
        while (!stopClient) {
            byte[] rxbytes = new byte[2000];
            DatagramPacket rxdp = new DatagramPacket(rxbytes, rxbytes.length);

            try {
                ds.receive(rxdp);
                int l = rxdp.getLength();
                Log.i(TAG, String.format("UDP message received with length %d", l));
                if (l == 0) { continue; }
                byte[] message = rxdp.getData();
                Log.i(TAG, String.format("UDP message received msg %s", Hex.hex(message)));
                if (message[0] == 'C') {
                    byte[] challenge = Arrays.copyOfRange(message, 1, message.length);
                    mChallenge = new Challenge(challenge, 0, l-1);
                    callback.get().onChallengeReceived(mChallenge);
                }else if (message[0] == 'A' || message[0] == 'a') {
                    /**
                     * 解决aid从upd传递
                     */
                    byte[] challenge = Arrays.copyOfRange(message, 1, message.length);
                    mChallenge = new Challenge(challenge, 0, l-1);
                    callback.get().onAIDReceived(mChallenge);
                }

                if (stopClient)
                    return;

            } catch (IOException ex) {
                Log.e(TAG, String.format("UDP socket receive failed: %s", ex.getMessage()));
            }
        }
    }

    /* Periodically send a keep-alive message */

    private void sendKeepAlive() {
        if (stopClient)
            return;

        byte[] bytes = "Keep-Alive\n".getBytes();
        sendUdp(bytes);

        handler.postDelayed(keepAliveRunnable, 3000);
    }

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            sendKeepAliveAsync();
        }
    };

    private void sendKeepAliveAsync() {
        new Thread() { public void run() { sendKeepAlive(); } }.start();
    }
}