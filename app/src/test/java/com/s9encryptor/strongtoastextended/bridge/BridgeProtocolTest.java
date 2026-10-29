package com.s9encryptor.strongtoastextended.bridge;

import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class BridgeProtocolTest {
    private Bundle start() {
        Bundle b = new Bundle();
        b.putInt("protocol", 1);
        b.putString("requestId", "00000000-0000-4000-8000-000000000001");
        b.putLong("sequence", 100L);
        b.putString("command", "START_STATUS");
        b.putString("text", "S9_Encryptor");
        b.putInt("durationSeconds", 60);
        return b;
    }
    private void rejected(Bundle b) {
        assertThrows(RuntimeException.class, () -> BridgeProtocol.validate(b));
    }
    @Test public void protocolKeepsUnicodeAndTrailingWhitespaceExactly() {
        for (String text : new String[]{"S9_Encryptor", "S9_Encryptor ", "@S9_Encryptor", "বাংলা 😀 👨‍👩‍👧‍👦"}) {
            Bundle b = start(); b.putString("text", text);
            for (String style : new String[]{"NORMAL", "BOLD", "ITALIC"}) {
                b.putString("style", style);
                assertEquals(text, BridgeProtocol.validate(b).getString("text"));
            }
        }
    }
    @Test public void staleOrUnframedRequestsAreNotValidCommands() {
        Bundle b = start(); b.putInt("protocol", 2); rejected(b);
        b = start(); b.putString("requestId", "not-a-request"); rejected(b);
        b = start(); b.putLong("sequence", 0); rejected(b);
        b = start(); b.putString("command", "EXECUTE_SHELL"); rejected(b);
    }
    @Test public void rejectsInvalidDurationsStylesAndOversizedText() {
        for (int seconds : new int[]{-1, 0, 3601}) {
            Bundle b = start(); b.putInt("durationSeconds", seconds); rejected(b);
        }
        Bundle b = start(); b.putString("style", "BOLD;anything"); rejected(b);
        b = start(); b.putString("text", "x".repeat(8193)); rejected(b);
        b = start(); b.putString("color", "#0BFC03"); rejected(b);
    }
    @Test public void onlySelectedDrawableResourcesAreAllowed() {
        Bundle b = start(); b.putString("iconPackage", "other.application"); rejected(b);
        b = start(); b.putString("iconName", "../../secret"); rejected(b);
        b = start(); b.putString("iconPackage", "com.sevtinge.hyperceiler");
        b.putString("iconName", "ic_hyperceiler");
        assertEquals("ic_hyperceiler", BridgeProtocol.validate(b).getString("iconName"));
    }
    @Test public void settingsAndNonceCannotCarryShellOrParcelableCommands() {
        Bundle b = start(); b.putString("command", "PREPARE_ROOT_BOOTSTRAP");
        b.putString("nonce", "' ; arbitrary"); rejected(b);
        b.putString("nonce", "a".repeat(64));
        assertEquals("a".repeat(64), BridgeProtocol.validate(b).getString("nonce"));
        b = start(); b.putString("command", "UPDATE_SETTINGS");
        b.putString("vibration", "true"); rejected(b);
        b = start(); b.putString("command", "UPDATE_SETTINGS"); b.putBoolean("vibration", false);
        assertFalse(BridgeProtocol.validate(b).getBoolean("vibration"));
        assertTrue(BridgeProtocol.isMutation("STOP_STATUS"));
        assertFalse(BridgeProtocol.isMutation("GET_CURRENT_STATE"));
    }
}
