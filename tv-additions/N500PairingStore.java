package com.gathin.tvhome;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AndroidKeyStore AES-GCM-protected paired-device credentials.
 * Auth tokens are never stored as plaintext preferences or placed in logs.
 * This works with Android 8+ and intentionally does not back up credentials.
 */
final class N500PairingStore {
    private static final String ALIAS = "n500-tv-pair-aesgcm-v1";
    private static final String PREFS = "n500_companion_pairing";
    private static final String TOKEN = "encrypted_token";
    private static final String ADDRESS = "projector_address";
    private N500PairingStore() {}

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            KeyStore.Entry entry = store.getEntry(ALIAS, null);
            if (!(entry instanceof KeyStore.SecretKeyEntry))
                throw new SecurityException("Unexpected AndroidKeyStore entry");
            return ((KeyStore.SecretKeyEntry)entry).getSecretKey();
        }
        KeyGenerator generator=KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    static void save(Context context, String address, String token) throws Exception {
        if (token == null || !token.matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("Invalid pairing secret");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] iv=cipher.getIV();
        byte[] ciphertext=cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString(ADDRESS,address==null?"":address)
                .putString(TOKEN,Base64.encodeToString(iv,Base64.NO_WRAP) + "." +
                        Base64.encodeToString(ciphertext,Base64.NO_WRAP))
                .commit();
    }

    static String readToken(Context context) {
        try {
            String encrypted=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                    .getString(TOKEN,null);
            if(encrypted==null)return null;
            String[] parts=encrypted.split("\\.");
            if(parts.length!=2)return null;
            byte[] iv=Base64.decode(parts[0],Base64.NO_WRAP);
            byte[] bytes=Base64.decode(parts[1],Base64.NO_WRAP);
            if(iv.length!=12)return null;
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            String value=new String(cipher.doFinal(bytes),
                    java.nio.charset.StandardCharsets.US_ASCII);
            return value.matches("[0-9a-fA-F]{64}")?value:null;
        } catch(Exception e) {
            // If device was restored or AndroidKeyStore invalidated the key,
            // require explicit pairing again instead of accepting stale tokens.
            clear(context);
            return null;
        }
    }

    static String readAddress(Context context) {
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getString(ADDRESS,"");
    }

    static boolean hasPairing(Context context) {
        return readToken(context)!=null;
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().commit();
    }
}
