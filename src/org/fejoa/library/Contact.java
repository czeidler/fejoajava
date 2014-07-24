/*
 * Copyright 2014.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.library;


import org.fejoa.library.crypto.CryptoException;

import java.io.IOException;


public class Contact {
    protected String uid = "";
    protected String server = "";
    protected String serverUser = "";

    final protected SecureStorageDir storageDir;

    public Contact(SecureStorageDir storageDir) {
        this.storageDir = storageDir;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public void write() throws IOException, CryptoException {
        storageDir.writeSecureString("uid", uid);
        storageDir.writeSecureString("server", server);
        storageDir.writeSecureString("server_user", serverUser);
    }

    public void open() throws IOException, CryptoException {
        uid = storageDir.readSecureString("uid");
        server = storageDir.readSecureString("server");
        serverUser = storageDir.readSecureString("server_user");
    }

    public String getAddress() {
        return serverUser + "@" + server;
    }

    public boolean setAddress(String address) {
        String[] parts = address.split("@");
        if (parts.length != 2)
            return false;
        server = parts[1];
        serverUser = parts[0];
        return true;
    }
}