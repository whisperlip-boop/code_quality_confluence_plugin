package co.bskim.confluence.codequality.service;

import com.atlassian.confluence.setup.BootstrapManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * Encrypts access tokens at rest.
 *
 * <p><b>What this protects.</b> A database dump, a support export or read access to the AO
 * tables no longer yields the tokens: the key is not in the database. It lives in a file under
 * the Confluence <em>shared</em> home, so every node in a Data Center cluster reads the same
 * one and a failover does not turn every stored token into garbage.</p>
 *
 * <p><b>What it does not protect.</b> Anyone who can read the Confluence home directory, run
 * code in the JVM, or install a plugin can decrypt these tokens. Confluence Server has no
 * secret store to hide a key from its own administrators, and pretending otherwise would be
 * worse than saying so. Treat the tokens as readable by whoever can reach the server's disk,
 * and scope them accordingly - a read-only, repository-scoped token is the right thing to
 * register here.</p>
 *
 * <p>The first version of this kept the key in {@code PluginSettings}, which is the BANDANA
 * table - the same database as the ciphertext - so its stated goal was not actually met. It
 * also used CBC with no MAC and swallowed every decryption failure as an empty string, which
 * turned a rotated key or a damaged row into a silent "clone without credentials" that only
 * surfaced as a 401 from GitHub. Both are fixed: GCM authenticates, and a failure is raised.</p>
 */
@Named
public class TokenCipher
{
    private static final Logger log = LoggerFactory.getLogger(TokenCipher.class);

    /** Raised instead of quietly returning no token. */
    public static final class TokenUnreadableException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        TokenUnreadableException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    private static final String LEGACY_KEY_SETTING =
            "co.bskim.confluence.code-quality.cipher-key";
    private static final String GCM = "AES/GCM/NoPadding";
    private static final String CBC = "AES/CBC/PKCS5Padding";
    /** Marks the authenticated format; anything without it is a legacy CBC blob. */
    private static final String V2 = "2:";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int CBC_IV_BYTES = 16;

    private final PluginSettingsFactory pluginSettingsFactory;
    private final BootstrapManager bootstrapManager;
    private volatile SecretKeySpec key;
    private volatile SecretKeySpec legacyKey;

    @Inject
    public TokenCipher(@ComponentImport PluginSettingsFactory pluginSettingsFactory,
                       @ComponentImport BootstrapManager bootstrapManager)
    {
        this.pluginSettingsFactory = pluginSettingsFactory;
        this.bootstrapManager = bootstrapManager;
    }

    public String encrypt(String plain)
    {
        if (plain == null || plain.isEmpty())
        {
            return "";
        }
        try
        {
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(GCM);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(sealed, 0, combined, iv.length, sealed.length);
            return V2 + Base64.getEncoder().encodeToString(combined);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Cannot encrypt access token", e);
        }
    }

    /**
     * @throws TokenUnreadableException when a stored token cannot be decrypted, so the caller
     *         reports it rather than silently attempting an anonymous clone
     */
    public String decrypt(String stored)
    {
        if (stored == null || stored.isEmpty())
        {
            return "";
        }
        try
        {
            return stored.startsWith(V2)
                    ? openV2(stored.substring(V2.length()))
                    : openLegacy(stored);
        }
        catch (TokenUnreadableException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new TokenUnreadableException(
                    "Stored access token could not be decrypted; re-enter it", e);
        }
    }

    private String openV2(String encoded) throws Exception
    {
        byte[] combined = Base64.getDecoder().decode(encoded);
        if (combined.length <= GCM_IV_BYTES)
        {
            throw new TokenUnreadableException("Stored access token is truncated", null);
        }
        byte[] iv = new byte[GCM_IV_BYTES];
        byte[] sealed = new byte[combined.length - GCM_IV_BYTES];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_BYTES);
        System.arraycopy(combined, GCM_IV_BYTES, sealed, 0, sealed.length);

        Cipher cipher = Cipher.getInstance(GCM);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
    }

    /**
     * Reads a token written by the first version. Any save re-encrypts it in the new format, so
     * this path retires itself without anyone having to re-enter a token.
     */
    private String openLegacy(String encoded) throws Exception
    {
        SecretKeySpec previous = legacyKey();
        if (previous == null)
        {
            throw new TokenUnreadableException(
                    "Access token was stored with a key that is no longer present; re-enter it",
                    null);
        }
        byte[] combined = Base64.getDecoder().decode(encoded);
        if (combined.length <= CBC_IV_BYTES)
        {
            throw new TokenUnreadableException("Stored access token is truncated", null);
        }
        byte[] iv = new byte[CBC_IV_BYTES];
        byte[] encrypted = new byte[combined.length - CBC_IV_BYTES];
        System.arraycopy(combined, 0, iv, 0, CBC_IV_BYTES);
        System.arraycopy(combined, CBC_IV_BYTES, encrypted, 0, encrypted.length);

        Cipher cipher = Cipher.getInstance(CBC);
        cipher.init(Cipher.DECRYPT_MODE, previous, new IvParameterSpec(iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ key material

    private SecretKeySpec key()
    {
        SecretKeySpec cached = key;
        if (cached != null)
        {
            return cached;
        }
        synchronized (this)
        {
            if (key == null)
            {
                key = new SecretKeySpec(loadOrCreateKeyFile(), "AES");
            }
            return key;
        }
    }

    private SecretKeySpec legacyKey()
    {
        SecretKeySpec cached = legacyKey;
        if (cached != null)
        {
            return cached;
        }
        synchronized (this)
        {
            if (legacyKey == null)
            {
                PluginSettings settings = pluginSettingsFactory.createGlobalSettings();
                Object stored = settings.get(LEGACY_KEY_SETTING);
                if (!(stored instanceof String) || ((String) stored).isEmpty())
                {
                    return null;
                }
                legacyKey = new SecretKeySpec(
                        Base64.getDecoder().decode((String) stored), "AES");
            }
            return legacyKey;
        }
    }

    private byte[] loadOrCreateKeyFile()
    {
        File file = keyFile();
        try
        {
            if (file.isFile())
            {
                byte[] encoded = Files.readAllBytes(file.toPath());
                return Base64.getDecoder().decode(new String(encoded, StandardCharsets.UTF_8)
                        .trim());
            }
            byte[] fresh = newKey();
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs())
            {
                throw new IOException("Cannot create " + parent);
            }
            writeKeyFile(file, fresh);
            log.info("Created a new access-token encryption key at {}", file);
            return fresh;
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Cannot read or create the encryption key at "
                    + file, e);
        }
    }

    /**
     * The shared home, so a Data Center cluster shares one key. Falling back to the local home
     * keeps a single-node instance working if the shared home is not configured.
     */
    private File keyFile()
    {
        File home = null;
        try
        {
            home = bootstrapManager.getSharedHome();
        }
        catch (RuntimeException e)
        {
            log.debug("Shared home unavailable; falling back to the local home", e);
        }
        if (home == null)
        {
            home = bootstrapManager.getLocalHome();
        }
        if (home == null)
        {
            throw new IllegalStateException("Cannot locate the Confluence home directory");
        }
        return new File(new File(new File(home, "plugin-data"), "code-quality"), "token.key");
    }

    /** Best effort: a key file the whole machine can read defeats the point of moving it. */
    /**
     * Writes the key so that it is never briefly readable by anyone else.
     *
     * <p>{@code Files.write} then {@code chmod} leaves a window: the file exists with the
     * umask's permissions - usually 0644 - and holds the key, and only afterwards is it
     * restricted. Short, but a key only has to be read once. Where POSIX permissions are
     * available the file is created 0600 <b>before</b> anything is written to it; elsewhere
     * (Windows) it falls back to the old order, which is the best the platform offers.</p>
     */
    private static void writeKeyFile(File file, byte[] fresh) throws IOException
    {
        byte[] encoded =
                Base64.getEncoder().encodeToString(fresh).getBytes(StandardCharsets.UTF_8);
        Path path = file.toPath();
        try
        {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.createFile(path, PosixFilePermissions.asFileAttribute(ownerOnly));
            Files.write(path, encoded);
            return;
        }
        catch (UnsupportedOperationException e)
        {
            log.debug("POSIX permissions unavailable; creating the key file then restricting it",
                    e);
        }
        catch (FileAlreadyExistsException e)
        {
            // Another node or thread got there first; its content is the key to use, and the
            // caller re-reads. Writing over it would strand every token already encrypted.
            throw new IOException("The key file appeared while it was being created", e);
        }
        Files.write(path, encoded);
        restrictToOwner(file);
    }

    private static void restrictToOwner(File file)
    {
        if (!file.setReadable(false, false) || !file.setWritable(false, false)
                || !file.setReadable(true, true) || !file.setWritable(true, true))
        {
            log.warn("Could not restrict permissions on {}; check them by hand", file);
        }
    }

    private static byte[] newKey()
    {
        try
        {
            int bits = Cipher.getMaxAllowedKeyLength("AES") >= 256 ? 256 : 128;
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(bits);
            return generator.generateKey().getEncoded();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Cannot create an encryption key", e);
        }
    }
}
