package co.bskim.confluence.codequality.service;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.inject.Named;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Keeps access tokens out of the database in plain text.
 *
 * <p>Honest about what this is: the key sits in plugin settings on the same instance, so it
 * stops a token from being readable in a database dump or a support export, not a Confluence
 * administrator who is determined to read it. Tokens are never sent back to any client - the
 * REST layer reports only whether one is set.</p>
 */
@Named
public class TokenCipher
{
    private static final String KEY_SETTING = "co.bskim.confluence.code-quality.cipher-key";
    private static final String TRANSFORM = "AES/CBC/PKCS5Padding";
    private static final int IV_BYTES = 16;

    private final PluginSettingsFactory pluginSettingsFactory;
    private volatile SecretKeySpec key;

    @Inject
    public TokenCipher(@ComponentImport PluginSettingsFactory pluginSettingsFactory)
    {
        this.pluginSettingsFactory = pluginSettingsFactory;
    }

    public String encrypt(String plain)
    {
        if (plain == null || plain.isEmpty())
        {
            return "";
        }
        try
        {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Cannot encrypt access token", e);
        }
    }

    public String decrypt(String stored)
    {
        if (stored == null || stored.isEmpty())
        {
            return "";
        }
        try
        {
            byte[] combined = Base64.getDecoder().decode(stored);
            if (combined.length <= IV_BYTES)
            {
                return "";
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] encrypted = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key(), new IvParameterSpec(iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            // A key that no longer matches means the token has to be re-entered, which is
            // better than failing the whole repository list.
            return "";
        }
    }

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
                PluginSettings settings = pluginSettingsFactory.createGlobalSettings();
                Object stored = settings.get(KEY_SETTING);
                String encoded = stored instanceof String ? (String) stored : null;
                if (encoded == null || encoded.isEmpty())
                {
                    encoded = Base64.getEncoder().encodeToString(newKey());
                    settings.put(KEY_SETTING, encoded);
                }
                key = new SecretKeySpec(Base64.getDecoder().decode(encoded), "AES");
            }
            return key;
        }
    }

    private static byte[] newKey()
    {
        try
        {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(128);
            return generator.generateKey().getEncoded();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Cannot create encryption key", e);
        }
    }
}
