package it.epozzobon.nfcrelay;

public class VictimConfig {
    public final String name;
    public final byte[] aid;
    public final int aidRespLen;
    public static String aidSavedHex;

    public VictimConfig(
            String name,
            byte[] aid,
            int aidRespLen
    ) {
        this.name = name;
        this.aid = aid;
        this.aidRespLen = aidRespLen;
    }

    public static final VictimConfig victimST25TA = new VictimConfig(
            "ST25TA",
            new byte[]{(byte) 0xd2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01},
            0
    );
    public static final VictimConfig testID = new VictimConfig(
            "test",
            new byte[]{0x31, 0x50, 0x41},
            0
    );
}
