/*
 * Copyright 2015.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.library.remote2;

import org.eclipse.jgit.util.Base64;
import org.fejoa.library.crypto.*;
import org.json.JSONObject;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;


public class CreateAccountJob extends SimpleJsonRemoteJob {
    static final public String METHOD = "createAccount";

    static final public String USER_NAME_KEY = "userName";
    static final public String PASSWORD_KEY = "password";
    static final public String SALT_BASE64_KEY = "saltBase64";
    static final public String KDF_ALGORITHM_KEY = "kdfAlgorithm";
    static final public String KEY_SIZE_KEY = "keySize";
    static final public String KDF_ITERATIONS_KEY = "kdfIterations";

    final private String userName;
    final private String password;
    final private CryptoSettings.Password settings;

    public CreateAccountJob(String userName, String password, CryptoSettings.Password settings) {
        super(false);

        this.userName = userName;
        this.password = password;
        this.settings = settings;
    }

    @Override
    public String getJsonHeader(JsonRPC jsonRPC) throws IOException {
        ICryptoInterface crypto = Crypto.get();
        byte[] salt = crypto.generateSalt();
        String saltBase64 = Base64.encodeBytes(salt);
        String derivedPassword;

        try {
            SecretKey secretKey = crypto.deriveKey(password, salt, settings.kdfAlgorithm, settings.keySize,
                    settings.kdfIterations);
            derivedPassword = CryptoHelper.sha256HashHex(secretKey.getEncoded());
        } catch (CryptoException e) {
            e.printStackTrace();
            throw new IOException(e.getMessage());
        }

        return jsonRPC.call(METHOD, new JsonRPC.Argument(USER_NAME_KEY, userName),
                new JsonRPC.Argument(PASSWORD_KEY, derivedPassword),
                new JsonRPC.Argument(SALT_BASE64_KEY, saltBase64),
                new JsonRPC.Argument(KDF_ALGORITHM_KEY, settings.kdfAlgorithm),
                new JsonRPC.Argument(KEY_SIZE_KEY, settings.keySize),
                new JsonRPC.Argument(KDF_ITERATIONS_KEY, settings.kdfIterations));
    }

    @Override
    protected Result handleJson(JSONObject returnValue, InputStream binaryData) {
        return getResult(returnValue);
    }
}
