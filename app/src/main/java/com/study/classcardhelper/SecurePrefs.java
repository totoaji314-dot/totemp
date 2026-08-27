package com.study.classcardhelper;
import android.content.*; import android.security.keystore.*; import android.util.Base64; import java.nio.charset.StandardCharsets; import java.security.KeyStore; import javax.crypto.*; import javax.crypto.spec.GCMParameterSpec;
public final class SecurePrefs {
 private static final String PREF="secure_settings",ALIAS="classcard_helper_key",KEY_API="api",KEY_MODEL="model"; private final SharedPreferences prefs;
 public SecurePrefs(Context c){prefs=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);ensureKey();}
 public void saveApiKey(String v){prefs.edit().putString(KEY_API,encrypt(v==null?"":v)).apply();}
 public String getApiKey(){String e=prefs.getString(KEY_API,"");return e.isEmpty()?"":decrypt(e);}
 public void saveModel(String v){prefs.edit().putString(KEY_MODEL,v==null||v.trim().isEmpty()?"gpt-5.6":v.trim()).apply();}
 public String getModel(){return prefs.getString(KEY_MODEL,"gpt-5.6");}
 private static void ensureKey(){try{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(!ks.containsAlias(ALIAS)){KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());kg.generateKey();}}catch(Exception e){throw new IllegalStateException(e);}}
 private static SecretKey key()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);return(SecretKey)ks.getKey(ALIAS,null);}
 private static String encrypt(String p){try{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());byte[]iv=c.getIV(),ct=c.doFinal(p.getBytes(StandardCharsets.UTF_8)),all=new byte[iv.length+ct.length];System.arraycopy(iv,0,all,0,iv.length);System.arraycopy(ct,0,all,iv.length,ct.length);return Base64.encodeToString(all,Base64.NO_WRAP);}catch(Exception e){return"";}}
 private static String decrypt(String e){try{byte[]all=Base64.decode(e,Base64.NO_WRAP),iv=new byte[12],ct=new byte[all.length-12];System.arraycopy(all,0,iv,0,12);System.arraycopy(all,12,ct,0,ct.length);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));return new String(c.doFinal(ct),StandardCharsets.UTF_8);}catch(Exception ex){return"";}}
}
