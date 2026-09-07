import br.com.iptvcaseiro.util.BackupCrypto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Builds the encrypted catalog asset without storing its password in source control. */
public final class EncryptCatalog {
    private EncryptCatalog() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Use: EncryptCatalog <catalog.json> <catalog.iptvbak>");
        String secret = System.getenv("IPTV_CATALOG_PASSWORD");
        if (secret == null || secret.isBlank()) throw new IllegalStateException("Defina IPTV_CATALOG_PASSWORD no ambiente.");
        char[] password = secret.toCharArray();
        try {
            byte[] plain = Files.readAllBytes(Path.of(args[0]));
            byte[] encrypted = BackupCrypto.encrypt(plain, password);
            Files.write(Path.of(args[1]), encrypted);
            if (!Arrays.equals(plain, BackupCrypto.decrypt(encrypted, password))) {
                throw new IllegalStateException("A verificação do catálogo criptografado falhou.");
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
