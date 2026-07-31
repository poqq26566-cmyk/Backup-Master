package android.net.wifi;

import android.content.pm.PackageManager;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;

import java.util.BitSet;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(WifiConfiguration.class)
public class WifiConfigurationHidden {

    public WifiConfigurationHidden() {
        throw new RuntimeException("Stub!");
    }

    public void setSecurityParams(int securityType) {
        throw new RuntimeException("Stub!");
    }

    public int networkId;
    public String SSID;
    public String BSSID;

    /**
     * Pre-shared key for use with WPA-PSK. This is the field that holds the
     * plaintext WiFi password when read via the privileged system API.
     */
    public String preSharedKey;

    public String[] wepKeys;

    @IntRange(from = 0, to = 3)
    public int wepTxKeyIndex;

    public boolean hiddenSSID;

    @NonNull
    public BitSet allowedKeyManagement;
    @NonNull
    public BitSet allowedProtocols;
    @NonNull
    public BitSet allowedAuthAlgorithms;
    @NonNull
    public BitSet allowedPairwiseCiphers;
    @NonNull
    public BitSet allowedGroupCiphers;
    @NonNull
    public BitSet allowedGroupManagementCiphers;
    @NonNull
    public BitSet allowedSuiteBCiphers;

    public boolean shared;
    public String creatorName;
    public String lastUpdateName;
    public boolean allowAutojoin;
    public long lastConnected;
    public long lastDisconnected;

    public boolean ephemeral;

    public boolean isEphemeral() {
        throw new RuntimeException("Stub!");
    }

    public boolean fromWifiNetworkSuggestion;
    public boolean fromWifiNetworkSpecifier;

    @NonNull
    public String getPrintableSsid() {
        throw new RuntimeException("Stub!");
    }

    public int getAuthType() {
        throw new RuntimeException("Stub!");
    }
}
