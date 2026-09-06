package br.com.iptvcaseiro.util;

import static org.junit.Assert.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import javax.crypto.AEADBadTagException;
import org.junit.Test;

public class BackupCryptoTest {
    @Test public void roundTripUsesAuthenticatedEncryption() throws Exception {
        byte[] clear = "catálogo privado".getBytes(StandardCharsets.UTF_8);
        char[] password = "senha-forte".toCharArray();
        byte[] encrypted = BackupCrypto.encrypt(clear, password);
        assertArrayEquals(clear, BackupCrypto.decrypt(encrypted, password));
    }

    @Test(expected = AEADBadTagException.class)
    public void rejectsWrongPassword() throws Exception {
        byte[] encrypted = BackupCrypto.encrypt("dados".getBytes(StandardCharsets.UTF_8), "senha-correta".toCharArray());
        BackupCrypto.decrypt(encrypted, "senha-errada".toCharArray());
    }

    @Test(expected = AEADBadTagException.class)
    public void rejectsTamperedFile() throws Exception {
        byte[] encrypted = BackupCrypto.encrypt("dados".getBytes(StandardCharsets.UTF_8), "senha-correta".toCharArray());
        encrypted[encrypted.length - 1] ^= 1;
        BackupCrypto.decrypt(encrypted, "senha-correta".toCharArray());
    }
}
