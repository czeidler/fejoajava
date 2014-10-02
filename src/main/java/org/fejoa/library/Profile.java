/*
 * Copyright 2014.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.library;

import org.fejoa.library.crypto.*;
import org.fejoa.library.database.IDatabaseInterface;
import org.fejoa.library.mailbox.Mailbox;
import org.fejoa.library.remote.RemoteStorageLink;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class Profile extends UserData {
    final private List<KeyStore> keyStoreList = new ArrayList<>();
    final private List<UserIdentity> userIdentityList = new ArrayList<>();
    final private List<Mailbox> mailboxList = new ArrayList<>();
    final private Map<IDatabaseInterface, RemoteStorageLink> remoteStorageLinks = new HashMap<>();
    private UserIdentity mainUserIdentity = null;
    private Mailbox mainMailbox = null;

    private class KeyStoreFinder implements IKeyStoreFinder {
        final List<KeyStore> keyStoreList;

        public KeyStoreFinder(List<KeyStore> keyStoreList) {
            this.keyStoreList = keyStoreList;
        }

        @Override
        public KeyStore find(String keyStoreId) {
            for (KeyStore keyStore : keyStoreList) {
                if (keyStore.getUid().equals(keyStoreId))
                    return keyStore;
            }
            return null;
        }
    }

    public Profile(SecureStorageDir storageDir, String baseDir) {
        this.storageDir = new SecureStorageDir(storageDir, baseDir, true);
    }

    public Map<IDatabaseInterface, RemoteStorageLink> getRemoteStorageLinks() {
        return remoteStorageLinks;
    }

    public UserIdentity getMainUserIdentity() {
        return mainUserIdentity;
    }

    public Mailbox getMainMailbox() {
        return mainMailbox;
    }

    public void createNew(String password) throws IOException, CryptoException {
        ICryptoInterface crypto = Crypto.get();
        uid = CryptoHelper.toHex(crypto.generateInitializationVector(20));

        // init key store and master key
        KeyStore keyStore = new KeyStore(password);
        SecureStorageDir keyStoreBranch = SecureStorageDirBucket.get(storageDir.getDatabase().getPath(),
                "key_stores");
        keyStore.create(new StorageDir(keyStoreBranch, keyStore.getUid(), true));
        addAndWriteKeyStore(keyStore);

        SecretKey key = crypto.generateSymmetricKey(CryptoSettings.SYMMETRIC_KEY_SIZE);
        KeyId keyId = keyStore.writeSymmetricKey(key, crypto.generateInitializationVector(
                CryptoSettings.SYMMETRIC_KEY_IV_SIZE));
        storageDir.setTo(keyStore, keyId);

        UserIdentity userIdentity = new UserIdentity();
        SecureStorageDir userIdBranch = SecureStorageDirBucket.get(storageDir.getDatabase().getPath(),
                "user_identities");
        SecureStorageDir userIdDir = new SecureStorageDir(userIdBranch, keyId.getKeyId(), true);
        userIdDir.setTo(keyStore, keyId);
        userIdentity.write(userIdDir);
        addAndWriteUseIdentity(userIdentity);
        mainUserIdentity = userIdentity;
        storageDir.writeSecureString("main_user_identity", mainUserIdentity.getUid());

        Mailbox mailbox = new Mailbox(mainUserIdentity);
        SecureStorageDir mailboxesBranch = SecureStorageDirBucket.get(storageDir.getDatabase().getPath(),
                "mailboxes");
        SecureStorageDir mailboxDir = new SecureStorageDir(mailboxesBranch, mailbox.getUid());
        mailboxDir.setTo(keyStore, keyId);
        mailbox.write(mailboxDir);
        addAndWriteMailbox(mailbox);
        mainMailbox = mailbox;
        storageDir.writeSecureString("main_mailbox", mainMailbox.getUid());

        addAndWriteRemoteStorageLink(keyStoreBranch.getDatabase(), userIdentity.getMyself());
        addAndWriteRemoteStorageLink(userIdBranch.getDatabase(), userIdentity.getMyself());
        addAndWriteRemoteStorageLink(mailboxDir.getDatabase(), userIdentity.getMyself());

        writeUserData(uid, storageDir);
    }

    @Override
    public void commit() throws IOException {
        super.commit();

        for (KeyStore keyStore : keyStoreList)
            keyStore.getStorageDir().commit();

        for (UserIdentity entry : userIdentityList)
            entry.getStorageDir().commit();

        for (Mailbox entry : mailboxList)
            entry.getStorageDir().commit();
    }

    public void setEmptyRemotes(String server, String serverUser, ContactPrivate myself) throws IOException, CryptoException {
        for (RemoteStorageLink link : remoteStorageLinks.values()) {
            if (link.getConnectionInfo() != null)
                continue;
            link.setConnectionInfo(server, serverUser, myself);
            link.write();
        }
    }

    public boolean open(String password) throws IOException, CryptoException {
        loadKeyStores();

        if (!readUserData(storageDir, getKeyStoreFinder(), password))
            return false;

        loadUserIdentities();

        // must be called after mainUserIdentity has been found
        loadRemoteStorageLinks();

        loadMailboxes();
        return true;
    }

    public IKeyStoreFinder getKeyStoreFinder() {
        return new KeyStoreFinder(keyStoreList);
    }

    public IUserIdentityFinder getUserIdentityFinder() {
        return new IUserIdentityFinder() {
            @Override
            public UserIdentity find(String uid) {
                for (UserIdentity userIdentity : getUserIdentityList()) {
                    if (userIdentity.getUid().equals(uid))
                        return userIdentity;
                }
                return null;
            }
        };
    }

    public List<UserIdentity> getUserIdentityList() {
        return userIdentityList;
    }

    private void addAndWriteKeyStore(KeyStore keyStore) throws IOException {
        String path = "key_stores/";
        path += keyStore.getUid();
        path += "/";

        writeRef(path, keyStore.getStorageDir());
        keyStoreList.add(keyStore);
    }

    private void addAndWriteUseIdentity(UserIdentity userIdentity) throws IOException {
        String path = "user_ids/";
        path += userIdentity.getUid();
        path += "/";

        writeRef(path, userIdentity.getStorageDir());
        userIdentityList.add(userIdentity);
    }

    private void addAndWriteMailbox(Mailbox mailbox) throws IOException {
        String path = "mailboxes/";
        path += mailbox.getUid();
        path += "/";

        writeRef(path, mailbox.getStorageDir());
        mailboxList.add(mailbox);
    }

    private void addAndWriteRemoteStorageLink(IDatabaseInterface databaseInterface, ContactPrivate myself)
            throws IOException, CryptoException {
        if (remoteStorageLinks.containsKey(databaseInterface))
            return;
        RemoteStorageLink remoteStorageLink = new RemoteStorageLink(new SecureStorageDir(storageDir, "remotes"),
                databaseInterface, myself);
        remoteStorageLink.write();
        remoteStorageLinks.put(databaseInterface, remoteStorageLink);
    }

    private void loadKeyStores() throws IOException {
        List<String> keyStores = storageDir.listDirectories("key_stores");

        for (String keyStorePath : keyStores) {
            UserDataRef ref = readRef("key_stores/" + keyStorePath);
            // use a secure storage without a key store (hack to use SecureStorageDirBucket)
            StorageDir dir = new SecureStorageDir(SecureStorageDirBucket.get(ref.path, ref.branch), ref.basedir, true);
            KeyStore keyStore = new KeyStore(dir);
            keyStoreList.add(keyStore);
        }
    }

    private void loadUserIdentities() throws IOException, CryptoException {
        List<String> userIdentities = storageDir.listDirectories("user_ids");

        for (String uidPath : userIdentities) {
            UserDataRef ref = readRef("user_ids/" + uidPath);
            UserIdentity userIdentity = new UserIdentity();
            SecureStorageDir dir = new SecureStorageDir(SecureStorageDirBucket.get(ref.path, ref.branch), ref.basedir,
                    true);
            userIdentity.open(dir, getKeyStoreFinder());
            userIdentityList.add(userIdentity);
        }

        String mainUserIdentityId = storageDir.readSecureString("main_user_identity");
        for (UserIdentity userIdentity : userIdentityList) {
            if (userIdentity.getUid().equals(mainUserIdentityId)) {
                mainUserIdentity = userIdentity;
                break;
            }
        }
    }

    private void loadRemoteStorageLinks() throws IOException, CryptoException {
        String baseDir = "remotes";
        List<String> remoteUids = storageDir.listDirectories(baseDir);

        for (String uidPath : remoteUids) {
            SecureStorageDir linkDir = new SecureStorageDir(storageDir, baseDir + "/" + uidPath);
            RemoteStorageLink link = new RemoteStorageLink(linkDir, mainUserIdentity.getMyself());
            remoteStorageLinks.put(link.getDatabaseInterface(), link);
        }
    }

    private void loadMailboxes() throws IOException, CryptoException {
        String baseDir = "mailboxes";
        List<String> mailboxes = storageDir.listDirectories(baseDir);

        for (String mailboxId : mailboxes) {
            UserDataRef ref = readRef(baseDir + "/" + mailboxId);
            SecureStorageDir dir = new SecureStorageDir(SecureStorageDirBucket.get(ref.path, ref.branch), ref.basedir,
                    true);
            Mailbox mailbox = new Mailbox(dir, getKeyStoreFinder(), getUserIdentityFinder());

            mailboxList.add(mailbox);
        }

        String mainMailboxId = storageDir.readSecureString("main_mailbox");
        for (Mailbox mailbox : mailboxList) {
            if (mailbox.getUid().equals(mainMailboxId)) {
                mainMailbox = mailbox;
                break;
            }
        }
    }

    private void writeRef(String path, StorageDir refTarget) throws IOException {
        IDatabaseInterface databaseInterface = refTarget.getDatabase();
        storageDir.writeString(path + "database_path", databaseInterface.getPath());
        storageDir.writeString(path + "database_branch", databaseInterface.getBranch());
        storageDir.writeString(path + "database_base_dir", refTarget.getBaseDir());
    }

    private class UserDataRef {
        public String path;
        public String branch;
        public String basedir;
    }

    private UserDataRef readRef(String path) throws IOException {
        UserDataRef ref = new UserDataRef();

        IDatabaseInterface databaseInterface = storageDir.getDatabase();
        if (!path.equals(""))
            path += "/";
        ref.path = storageDir.readString(path + "database_path");
        ref.branch = storageDir.readString(path + "database_branch");
        ref.basedir = storageDir.readString(path + "database_base_dir");

        return ref;
    }
}