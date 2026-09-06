package br.com.iptvcaseiro.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class BackupCrypto {
    private static final byte[] MAGIC = "IPTVCBK1".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCrypto() {}

    public static byte[] encrypt(byte[] plain, char[] password) throws GeneralSecurityException, IOException {
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(iv);
        SecretKey key = derive(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plain);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(MAGIC);
        output.write(salt);
        output.write(iv);
        output.write(encrypted);
        return output.toByteArray();
    }

    public static byte[] decrypt(byte[] data, char[] password) throws GeneralSecurityException, IOException {
        if (data.length < MAGIC.length + 16 + 12 + 16) throw new IOException("Backup inválido ou incompleto.");
        ByteBuffer buffer = ByteBuffer.wrap(data);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) throw new IOException("Este arquivo não é um backup do IPTV Caseiro.");
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        buffer.get(salt);
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        SecretKey key = derive(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(encrypted);
    }

    private static SecretKey derive(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            byte[] encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(encoded, "AES");
        } finally {
            spec.clearPassword();
        }
    }
}
